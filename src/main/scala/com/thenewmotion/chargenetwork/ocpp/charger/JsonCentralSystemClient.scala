package com.thenewmotion.chargenetwork.ocpp.charger

import com.typesafe.scalalogging.slf4j.LazyLogging
import java.net.URI
import javax.net.ssl.SSLContext

import akka.actor.ActorRef
import com.thenewmotion.ocpp.Version
import com.thenewmotion.ocpp.messages._

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import com.thenewmotion.ocpp.json._
import com.thenewmotion.ocpp.json.api.{ChargePointRequestHandler, OcppError, OcppException}
import com.thenewmotion.ocpp.json.api.client.OcppJsonClient
import com.thenewmotion.ocpp.soap.CentralSystemClient

import scala.reflect.ClassTag

object JsonCentralSystemClient {
  def apply(chargeBoxIdentity: String,
            version: Version,
            centralSystemUri: URI,
            authPassword: Option[String],
            config: ChargerConfig
           )(implicit sslContext: SSLContext = SSLContext.getDefault): CentralSystemClient = version match {
    case Version.V16 => new JsonCentralSystemClientV16(chargeBoxIdentity, centralSystemUri, authPassword, config)(sslContext)
    case _ => throw new IllegalArgumentException("Wrong OCPP version")
  }
}

class JsonCentralSystemClientV16(val chargeBoxIdentity: String,
                                 centralSystemUri: URI,
                                 authPassword: Option[String],
                                 config: ChargerConfig)
                                (implicit sslContext: SSLContext = SSLContext.getDefault)
  extends CentralSystemClient with LazyLogging with AutoCloseable {

  def version: Version.V16.type = Version.V16

  lazy val chargerActor: ActorRef = ChargerActor.Resolver.resolve(chargeBoxIdentity) match {
    case Some(actorRef) => actorRef
    case None => throw new RuntimeException("Unable to find an actor");
  }

  val client : OcppJsonClient = new OcppJsonClient(chargeBoxIdentity, centralSystemUri, Seq(version), authPassword) {
    def onError(err: OcppError): Unit = logger.error(s"Received OCPP error $err")

    def onDisconnect: Unit = logger.error("WebSocket disconnected for charger {}" , chargeBoxIdentity)

    val requestHandler: ChargePointRequestHandler = new ChargePoint {

      def getConfiguration(req: GetConfigurationReq): Future[GetConfigurationRes] =
        askCharger(req)

      def remoteStartTransaction(q: RemoteStartTransactionReq): Future[RemoteStartTransactionRes] =
        notSupported("Remote Start Transaction")

      def remoteStopTransaction(q: RemoteStopTransactionReq): Future[RemoteStopTransactionRes] =
        notSupported("Remote Stop Transaction")

      def unlockConnector(q: UnlockConnectorReq): Future[UnlockConnectorRes] =
        notSupported("Unlock Connector")

      def getDiagnostics(req: GetDiagnosticsReq): Future[GetDiagnosticsRes] =
        notSupported("Get Diagnostics")

      def changeConfiguration(req: ChangeConfigurationReq): Future[ChangeConfigurationRes] =
        notSupported("Change Configuration")

      def changeAvailability(req: ChangeAvailabilityReq): Future[ChangeAvailabilityRes] =
        Future.successful(ChangeAvailabilityRes(AvailabilityStatus.Rejected))

      def clearCache: Future[ClearCacheRes] = askCharger(ClearCacheReq)

      def reset(req: ResetReq): Future[ResetRes] =
        Future.successful(ResetRes(false))

      def updateFirmware(req: UpdateFirmwareReq): Future[Unit] =
        notSupported("Update Firmware")

      def sendLocalList(req: SendLocalListReq): Future[SendLocalListRes] =
        askCharger(req)

      def getLocalListVersion: Future[GetLocalListVersionRes] = askCharger(GetLocalListVersionReq)

      def dataTransfer(q: ChargePointDataTransferReq): Future[ChargePointDataTransferRes] =
        notSupported("Data Transfer")

      def reserveNow(q: ReserveNowReq): Future[ReserveNowRes] =
        notSupported("Reserve Now")

      def cancelReservation(q: CancelReservationReq): Future[CancelReservationRes] =
        notSupported("Cancel Reservation")

      def clearChargingProfile(req: ClearChargingProfileReq): Future[ClearChargingProfileRes] =
        notSupported("Clear Charging Profile")

      def getCompositeSchedule(req: GetCompositeScheduleReq): Future[GetCompositeScheduleRes] =
        Future.successful(GetCompositeScheduleRes(CompositeScheduleStatus.Rejected))

      def setChargingProfile(req: SetChargingProfileReq): Future[SetChargingProfileRes] =
        notSupported("Set Charging Profile")

      def triggerMessage(req: TriggerMessageReq): Future[TriggerMessageRes] =
        Future.successful(TriggerMessageRes(TriggerMessageStatus.NotImplemented))

      def notSupported(opName: String): Future[Nothing] =
        Future.failed(OcppException(
          PayloadErrorCode.NotSupported,
          s"OCPP Charger Simulator doesn't support $opName"
        ))
    }

  }

  def syncSend[REQ <: CentralSystemReq, RES <: CentralSystemRes](req: REQ)
                                                                (implicit reqRes: CentralSystemReqRes[REQ, RES]): RES = {
    val res = Await.result(client.send(req), 60.seconds)
    logger.info("{}\n\t>> {}\n\t<< {}", chargeBoxIdentity, req, res)
    res
  }

  def askCharger[REQ <: ChargePointReq, RES <: ChargePointRes](req: REQ)
                                                              (implicit tag: ClassTag[RES]): Future[RES] = {
    import akka.pattern.ask
    chargerActor.ask(req)(30.seconds).asInstanceOf[Future[RES]]
  }

  def authorize(req: AuthorizeReq): AuthorizeRes = syncSend[AuthorizeReq, AuthorizeRes](req)

  def bootNotification(req: BootNotificationReq): BootNotificationRes = syncSend[BootNotificationReq, BootNotificationRes](req)

  def dataTransfer(req: CentralSystemDataTransferReq): CentralSystemDataTransferRes = syncSend[CentralSystemDataTransferReq, CentralSystemDataTransferRes](req)

  def diagnosticsStatusNotification(req: DiagnosticsStatusNotificationReq): Unit = syncSend(req)

  def firmwareStatusNotification(req: FirmwareStatusNotificationReq): Unit = syncSend(req)

  def heartbeat: HeartbeatRes = syncSend(HeartbeatReq)

  def meterValues(req: MeterValuesReq): Unit = syncSend(req)

  def startTransaction(req: StartTransactionReq): StartTransactionRes = syncSend(req)

  def statusNotification(req: StatusNotificationReq): Unit = syncSend(req)

  def stopTransaction(req: StopTransactionReq): StopTransactionRes = syncSend(req)

  override def close(): Unit = {
    client.connection.close()
  }
}
