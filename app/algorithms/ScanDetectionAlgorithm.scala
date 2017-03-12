package algorithms

import java.lang.Double

import com.google.inject.{Inject, Singleton}
import models.{IterationResultHistory, Packet}
import play.api.Logger
import services.{HoneypotService, IterationResultHistoryService, PacketService}
import worker.ScanDetectWorker
import AlgorithmUtils._
import akka.actor.ActorSystem
import neuralnetwork.CheckingContext
import neuralnetwork.CheckingContext._
import utils.Constants.SettingsKeys
import utils.{Constants, Protocols}

import collection.JavaConverters._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration._

trait Algorithm {
  def detect(worker: ScanDetectWorker, packets: Seq[Packet]): Future[Unit]
  def fetchPacketsFromThisConnection(flowKey: Long): Future[Seq[Packet]]
  def filterIterationResult[A <: IterationResult](iterationResult: A): Future[IterationResult]
  def checkForAttack[A <: IterationResult](iterationResult: A)(old: Seq[Packet], analyzed: Seq[Packet])
  def createCheckingContext[A <: IterationResult](sourceAddress: String, iterationResult: A): Future[CheckingContext]
  def checkForAttackWithNeuralNetwork[A <: IterationResult](iterationResult: A): Future[String]
  def createIterationResultHistory[A <: IterationResult](iterationResult: A)
}

object ScanDetectionAlgorithm {

  val PORT_SCAN_CONTEXT_LABELS = Set(
    IterationResultHistoryLabels.didNotSendData,

    IterationResultHistoryLabels.portClosed,

    IterationResultHistoryLabels.suspiciousFinScanAttack,

    IterationResultHistoryLabels.suspiciousAckWinScanAttack,

    IterationResultHistoryLabels.suspiciousMaimonScanAttack
  )

  val OPEN_PORTS_COUNTER_LABELS = Set(
    IterationResultHistoryLabels.sendData,

    IterationResultHistoryLabels.removeFinePackets,

    IterationResultHistoryLabels.didNotSendData
  )

  val CLOSED_PORTS_COUNTER_LABELS = Set(
    IterationResultHistoryLabels.portClosed,

    IterationResultHistoryLabels.suspiciousFinScanAttack,

    IterationResultHistoryLabels.suspiciousAckWinScanAttack,

    IterationResultHistoryLabels.suspiciousMaimonScanAttack
  )

  object IterationResultHistoryLabels {
    val didNotSendData = DidNotSendData.getClass.getName.split("\\$").last
    val initializingConnection = InitializingConnection.getClass.getName.split("\\$").last
    val sendData = SendData.getClass.getName.split("\\$").last
    val initializingRemoveFinePackets = InitializingRemoveFinePackets.getClass.getName.split("\\$").last
    val portClosed = PortClosed.getClass.getName.split("\\$").last
    val suspiciousNetworkScan = SuspiciousNetworkScan.getClass.getName.split("\\$").last
    val removeFinePackets = RemoveFinePackets.getClass.getName.split("\\$").last
    val suspiciousFinScanAttack = SuspiciousFinScanAttack.getClass.getName.split("\\$").last
    val suspiciousAckWinScanAttack = SuspiciousAckWinScanAttack.getClass.getName.split("\\$").last
    val suspiciousMaimonScanAttack = SuspiciousMaimonScanAttack.getClass.getName.split("\\$").last
  }

}

/**
  * Created by Marcin on 2016-10-22.
  */
@Singleton
class ScanDetectionAlgorithm @Inject() (packetService: PacketService,
                                        iterationResultHistoryService: IterationResultHistoryService,
                                        honeypotService: HoneypotService,
                                        akkaSystem: ActorSystem) {

  val log = Logger

  implicit val workerContext: ExecutionContext = akkaSystem.dispatchers.lookup("worker-context")

  var worker: ScanDetectWorker = _

  def detect(worker: ScanDetectWorker, packets: Seq[Packet]): Seq[Future[Any]] = {
    this.worker = worker

    val groupByForNetworkLayer = packets.sortBy(_.timestamp).groupBy(p =>
      (p.flowKey, p.additionalHashNetwork)
    )

    log.info(s"Starting iteration for detecting scans for ${packets.size} packets.")
    val groupedByFlowKey: Map[(Long, Long, Long), Seq[Packet]] = packets.sortBy(_.timestamp).groupBy(p =>
      if (p.protocol != Protocols.ICMP)
      (p.flowKey, p.additionalHash, p.additionalHashNetwork)
      else
      (p.flowKey, Constants.ICMP_HASHCODE, p.additionalHashNetwork)
    )

    log.info(s"Got ${groupedByFlowKey.size} groups of flow keys.")

    val iterationResult: Future[Iterable[IterationResult]] = Future.sequence(groupedByFlowKey.map(entry => {
      val f = Future {
        val flowKey : Long = entry._1._1
        val protocol = entry._2.head.protocol
        val additionalHash : Long = entry._1._2
        val additionalHashNetwork : Long = entry._1._3
        val newPackets = entry._2 ++ (if (protocol == Protocols.ICMP) {
          groupByForNetworkLayer.getOrElse((flowKey, additionalHashNetwork), Seq())
            .filter(_.isUdp)
        } else if (protocol == Protocols.UDP) {
          groupedByFlowKey.getOrElse((flowKey, Constants.ICMP_HASHCODE, additionalHashNetwork), Seq())
            .filter(_.isIcmp)
        } else {
          Seq()
        })

        log.info(s"Starting analyzing packets for $flowKey flow key.")

        val f1 = fetchPacketsFromThisConnection(protocol, flowKey, additionalHash)
        while (!f1.isCompleted) Thread.sleep(500)
        val analyzed = f1.value.get.get //pakiety wcześniej przeanalizowane

        if (analyzed.nonEmpty) log.info(s"Fetched ${analyzed.size} packets for $flowKey flow key.")
        log.info(s"Got ${newPackets.size} new packets to analyze.")

        val packetsToAnalyze = (newPackets ++ analyzed).sortBy(_.timestamp)

        log.info(s"Analyzing total ${packetsToAnalyze.size} packets.")

        val sourceAddress = getSourceAddress(packetsToAnalyze)

        if (wasRegisteredByHoneypot(sourceAddress)) {
          log.info(s"$sourceAddress was registered by honeypot.")
          NetworkScanAlert(newPackets, analyzed)

        } else if (isSupportedInternetProtocol(protocol) &&
          incomingContainsInternetProtocol(packetsToAnalyze, protocol)) {

          log.info(s"Marking $sourceAddress as suspicious network scan.")
          SuspiciousNetworkScan(newPackets, analyzed)
        } else if (isSupportedTransportProtocol(protocol)) {

            if (isConnectionProtocol(protocol)) {
              if (tcpPacketWithoutAnyFlag(protocol, packetsToAnalyze)) {
                log.info(s"TCP NULL scan attack from $sourceAddress.")
                TcpNullScanAttack(newPackets, analyzed)

              } else if (isAckWinScan(protocol, packetsToAnalyze)) {
                log.info(s"ACK/WIN scan attack from $sourceAddress. Port scan alarm.")
                AckWinScanAttack(newPackets, analyzed, 100) //100% szansy

              } else if (isSuspiciousAckWinScan(protocol, packetsToAnalyze)) {
                log.info(s"Suspicious ACK/WIN scan attack from $sourceAddress.")
                SuspiciousAckWinScanAttack(newPackets, analyzed)

              } else if (isXmasAttack(protocol, packetsToAnalyze)) {
                log.info(s"Xmas scan attack from $sourceAddress.")
                XmasScanAttack(newPackets, analyzed)

              } else if (isInitializingConnection(protocol, packetsToAnalyze) &&
                !isConnectionClosed(protocol, packetsToAnalyze)) {

                log.info(s"Initialized connection")
                if (didSendAnyData(protocol, packetsToAnalyze)) {
                  InitializingConnectionAndDataTransfer(newPackets, analyzed)
                } else {
                  InitializingConnection(newPackets, analyzed)
                }

              } else if (isPortClosed(protocol, packetsToAnalyze)) {
                log.info(s"$sourceAddress tried to connect to an closed port.")
                PortClosed(newPackets, analyzed)

              } else if (!didSendAnyData(protocol, packetsToAnalyze) &&
                isConnectionClosed(protocol, packetsToAnalyze) &&
                wasNotProperlyFinished(protocol, packetsToAnalyze) &&
                (lastIsIncoming(protocol, packetsToAnalyze) || packetsToAnalyze.last.containsOnlyRstFlag ||
                  packetsToAnalyze.last.containsOnlyRstAckFLags)) {

                log.info(s"$sourceAddress connected to a port, did not send any data and connection was not properly " +
                  s"finished.")
                DidNotSendData(newPackets, analyzed)

              } else if (didSendAnyData(protocol, packetsToAnalyze) && isConnectionClosed(protocol, packetsToAnalyze)) {
                log.info(s"Removing ${packetsToAnalyze.size} fine packets")
                if (isInitializingConnection(protocol, packetsToAnalyze)) {
                  InitializingRemoveFinePackets(newPackets, analyzed)
                } else {
                  RemoveFinePackets(newPackets, analyzed)
                }

              } else if (isMaimonAttack(protocol, packetsToAnalyze)) {
                log.info(s"Maimon scan attack from $sourceAddress.")
                MaimonScanAttack(newPackets, analyzed, 100)

              } else if (isSuspiciousMaimonAttack(protocol, packetsToAnalyze)) {
                log.info(s"Suspicious Maimon scan attack from $sourceAddress.")
                SuspiciousMaimonScanAttack(newPackets, analyzed)

              } else if (isFinAttack(protocol, packetsToAnalyze)) {
                log.info(s"FIN scan attack from $sourceAddress.")
                FinScanAttack(newPackets, analyzed, 100)

              } else if (isSuspiciousFinAttack(protocol, packetsToAnalyze)) {
                log.info(s"Suspicious FIN scan attack from $sourceAddress.")
                SuspiciousFinScanAttack(newPackets, analyzed)

              } else {
                ContinueIteration(newPackets, analyzed)
              }
            } else {
              if (isPortClosed(protocol, packetsToAnalyze)) {
                PortClosed(newPackets, analyzed)
              } else {
                if (didSendAnyData(protocol, packetsToAnalyze)) {
                  SendData(newPackets, analyzed)
                } else {
                  DidNotSendData(newPackets, analyzed)
                }
              }
            }
        } else {
          ContinueIteration(newPackets, analyzed)
        }
      }
      f.onFailure{case e: Throwable => e.printStackTrace()}
      f
    }
    ))

    iterationResult.onSuccess {
      case rs =>
        log.info(s"Iteration finished. Got ${rs.size} iteration results.")
    }

    Seq(iterationResult.map(rs =>
      rs.map(iterationRs => filterIterationResult(iterationRs).map(x => worker.dispatch(x)))
    ))
  }

  def detectNetworkScans(worker: ScanDetectWorker,
                         iterationResultHistoryData: Seq[IterationResultHistory]): Seq[Future[Any]] = {

    val groupedBySourceAddress = iterationResultHistoryData.groupBy(_.sourceAddress)

    val iterationResult: Seq[NetworkScanAlert] = groupedBySourceAddress.flatMap(history => {
      val result = wasRegisteredByHoneypot(history._1)

      if (result) {
        val alerts = history._2.map(rs => {
          val f1 = fetchPacketsFromThisConnection(rs.flowKey, rs.additionalHash)
          while (!f1.isCompleted) Thread.sleep(500)
          val analyzed = f1.value.get.get
          NetworkScanAlert(Seq(), analyzed)
        })
        alerts
      } else {
        Seq()
    }
    }).toSeq

    iterationResult.map(rs => filterIterationResult(rs).map(x => worker.dispatch(x)))
  }

  def fetchPacketsFromThisConnection(protocol: String, flowKey: Long, additionalHash: Long): Future[Seq[Packet]] = {
    if (protocol == Protocols.UDP) {
      packetService.getAssociatedWithFlowKeyAndProtocol(flowKey, Protocols.ICMP)
        .flatMap(result => packetService.getAssociatedWithFlowKey(flowKey, additionalHash).map(rs => rs++result))
    } else if (protocol == Protocols.ICMP) {
      packetService.getAssociatedWithFlowKeyAndProtocol(flowKey, Protocols.UDP)
        .flatMap(result => packetService.getAssociatedWithFlowKey(flowKey, additionalHash).map(rs => rs++result))
    } else {
      fetchPacketsFromThisConnection(flowKey, additionalHash)
    }
  }

  def fetchPacketsFromThisConnection(flowKey: Long, additionalHash: Long): Future[Seq[Packet]] = {
    packetService.getAssociatedWithFlowKey(flowKey, additionalHash)
  }

  def filterIterationResult[A <: IterationResult](iterationResult: A): Future[IterationResult] = {
    iterationResult match {
      case result@InitializingConnection(captured: Seq[Packet], analyzed: Seq[Packet]) =>
        iterationResultHistoryService.create(
          createIterationResultHistory(iterationResult)
        ).map(rs => ContinueIteration(captured, analyzed))

      case result@InitializingRemoveFinePackets(captured: Seq[Packet], analyzed: Seq[Packet]) =>
        iterationResultHistoryService.create(
          createIterationResultHistory(iterationResult)
        ).map(rs => RemoveFinePackets(captured, analyzed))

      case result@SendData(captured: Seq[Packet], analyzed: Seq[Packet]) =>
        iterationResultHistoryService.create(
          createIterationResultHistory(iterationResult)
        ).map( rs => ContinueIteration(captured, analyzed))

      case result@DidNotSendData(captured: Seq[Packet], analyzed: Seq[Packet]) =>
        checkForAttack(iterationResult)

      case result@InitializingConnectionAndDataTransfer(captured: Seq[Packet], analyzed: Seq[Packet]) =>
        val f_1 = iterationResultHistoryService.create(
          createIterationResultHistory(iterationResult)
        )

        val f_2 = iterationResultHistoryService.create(
          createIterationResultHistory(iterationResult)
        )

        Future.sequence(Seq(f_1, f_2)).map(rs => ContinueIteration(captured, analyzed))

      case SuspiciousFinScanAttack(captured: Seq[Packet], analyzed: Seq[Packet]) =>
        awaitAndCheckForAttack(iterationResult)
      case SuspiciousAckWinScanAttack(captured: Seq[Packet], analyzed: Seq[Packet]) =>
        awaitAndCheckForAttack(iterationResult)
      case SuspiciousMaimonScanAttack(captured: Seq[Packet], analyzed: Seq[Packet]) =>
        awaitAndCheckForAttack(iterationResult)
      case iterationResult@PortClosed(captured: Seq[Packet], analyzed: Seq[Packet]) =>
        checkForAttack(iterationResult)
      case iterationResult@SuspiciousNetworkScan(captured: Seq[Packet], analyzed: Seq[Packet]) =>
        iterationResultHistoryService.create(
          createIterationResultHistory(iterationResult)
        ).map( rs => ContinueIteration(captured, analyzed))
      case iterationResult@RemoveFinePackets(captured: Seq[Packet], analyzed: Seq[Packet]) =>
        iterationResultHistoryService.create(
          createIterationResultHistory(iterationResult)
        ).map( rs => RemoveFinePackets(captured, analyzed))
      case _ => Future(iterationResult)
    }
  }

  def awaitAndCheckForAttack[A <: IterationResult](iterationResult: A) = {
    val f = Future (
      Thread.sleep(Constants.ONE_SECOND)
    )
    .flatMap(_ => iterationResult match {
      case _ => packetService.areThereMorePackets(iterationResult.captured.head.flowKey,
        iterationResult.captured.head.additionalHash).flatMap(result =>

        if (result) {
          Future{
            ContinueIteration(iterationResult.captured, iterationResult.analyzed)
          }
        } else {
          checkForAttack(iterationResult)
        }
      )
    })

    f
  }

  def checkForAttack[A <: IterationResult](iterationResult: A) = {
    iterationResultHistoryService.create(
      createIterationResultHistory(iterationResult)
    ).flatMap(createResultHistory =>
      checkForAttackWithNeuralNetwork(iterationResult).map(chance => {
        if (chance >= 50) {
          getAttackType(iterationResult, if (chance >= 100) 100 else chance)
        } else {
          ContinueIteration(iterationResult.captured, iterationResult.analyzed)
        }
      })
    )
  }

  def createCheckingContext[A <: IterationResult](sourceAddress: String,
                                                  iterationResult: A): Future[CheckingContext] = {
    val f = iterationResultHistoryService.findBySourceAddress(sourceAddress).map(result => {
      val resultTypes = result.groupBy(_.resultType)

      val openPortsTypes = resultTypes
        .filter(entry => ScanDetectionAlgorithm.OPEN_PORTS_COUNTER_LABELS.contains(entry._1))
        .values.flatten

      val numberOfTransportedPacketsToOpenPorts = openPortsTypes
        .map(_.info.getOrElse(IterationResultHistory.InfoKeys.INFO_KEYS, Constants.ZERO).toInt).sum

      val closedPortsTypes = resultTypes
        .filter(entry => ScanDetectionAlgorithm.CLOSED_PORTS_COUNTER_LABELS.contains(entry._1))
        .values.flatten

      val hostWasInitializingConnection = resultTypes.values.flatten
        .exists(ir => ir.resultType == ScanDetectionAlgorithm.IterationResultHistoryLabels.initializingConnection ||
          ir.resultType == ScanDetectionAlgorithm.IterationResultHistoryLabels.initializingRemoveFinePackets
        )

      val sendData = resultTypes.values.flatten
        .exists(ir => ir.resultType == ScanDetectionAlgorithm.IterationResultHistoryLabels.sendData ||
          ir.resultType == ScanDetectionAlgorithm.IterationResultHistoryLabels.removeFinePackets ||
          ir.resultType == ScanDetectionAlgorithm.IterationResultHistoryLabels.initializingRemoveFinePackets
        )

      val didNotSendData = resultTypes.get(ScanDetectionAlgorithm.IterationResultHistoryLabels.didNotSendData)
        .exists(_ => true)

      val closedPorts = closedPortsTypes.map(_.port).toSet

      val closedPortSize = closedPorts.size

      val openPorts = openPortsTypes.map(_.port).toSet
      val closedPortThreshold = this.worker.scanDetectContext
        .getSettingsValueOrUseDefault(SettingsKeys.CLOSED_PORT_THRESHOLD).toString.toInt

      val closedPortsThresholdResult = if (closedPortSize == closedPortThreshold) {
        AtThreshold(sourceAddress, closedPortThreshold)
      } else {
        if (closedPortSize > closedPortThreshold) {
          BeyondThreshold(sourceAddress, closedPortThreshold)
        } else {
          UnderThreshold(sourceAddress, closedPortThreshold)
        }
      }

      val groupedByPort: Map[Int, Seq[String]] = result
        .groupBy(_.port)
        .map(entry => (entry._1, entry._2.map(_.resultType)))

      val usedPorts = groupedByPort.keys

      val triedConnectToClosedPortAfterOpen = if (usedPorts.size >= 2) {
        usedPorts.sliding(2)
          .exists(
            part =>
              groupedByPort(part.head).contains(ScanDetectionAlgorithm.IterationResultHistoryLabels.didNotSendData) &&
              groupedByPort(part.last).contains(ScanDetectionAlgorithm.IterationResultHistoryLabels.portClosed)
          )
      } else {
        false
      }

      CheckingContext(hostWasInitializingConnection,
        sendData,
        didNotSendData,
        triedConnectToClosedPortAfterOpen,
        closedPortsThresholdResult,
        numberOfTransportedPacketsToOpenPorts,
        closedPorts,
        openPorts
      )
    })

    f.onFailure({
      case e: Throwable => e.printStackTrace()
    })

    f
  }

  def checkForAttackWithNeuralNetwork[A <: IterationResult](iterationResult: A): Future[Int] = {
    createCheckingContext(getSourceAddress(iterationResult.all), iterationResult).map(
      checkingContext => {
        val chance = try {
          val neuralNetworkResult = worker.scanDetectContext.scanDetectNeuralNetwork
            .getResultAsPercentage(checkingContext.createWeights.map(new Double(_)).toList.asJava)
          log.info("Neural network result => " + neuralNetworkResult)
          neuralNetworkResult
        } catch {
          case e: Throwable =>
            log.error("Error while getting result from neural network.", e)
            Constants.Numbers.ZERO
        }
        chance
      }
    )
  }

  def createIterationResultHistory[A <: IterationResult](iterationResult: A) = {
    IterationResultHistory(
      None,
      getSourceAddress(iterationResult.all),
      iterationResult.all.head.flowKey,
      iterationResult.all.head.additionalHash,
      getDestinationPort(iterationResult.all),
      Map(IterationResultHistory.InfoKeys.INFO_KEYS -> iterationResult.all.size.toString),
      iterationResult.getClass.getName
    )
  }

  def wasRegisteredByHoneypot(sourceAddress: String): Boolean = {
    try {
      if (worker.scanDetectContext.getSettingsValueOrUseDefault(SettingsKeys.USE_HONEYPOT).toString.toBoolean) {
        Await.result(honeypotService.wasRegisteredByHoneypot(sourceAddress), 10.seconds)
      } else {
        false
      }
    } catch {
      case ex: Throwable => false
    }
  }

}


