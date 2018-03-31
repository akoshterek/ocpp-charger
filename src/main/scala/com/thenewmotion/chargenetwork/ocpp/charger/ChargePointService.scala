package com.thenewmotion.chargenetwork.ocpp.charger

import com.typesafe.scalalogging.slf4j.LazyLogging
import com.thenewmotion.ocpp.messages._
import akka.actor.{Actor, ActorRef, Props}
import akka.util.Timeout
import akka.pattern.ask

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import java.util.concurrent.TimeoutException

import org.apache.commons.net.ftp.FTPSClient
import java.io.ByteArrayInputStream
import java.nio.charset.Charset
import java.text.SimpleDateFormat
import java.net.URI
import java.time.ZonedDateTime

import scala.language.postfixOps

/**
 * Implementation of ChargePointService that just logs each method call on it and does nothing else
 */
class ChargePointService(chargerId: String, actor: ActorRef) extends ChargePoint with LazyLogging {
  val uploadActor = system.actorOf(Props[Uploader])

  def clearCache = Future.successful(ClearCacheRes(accepted = false))

  def remoteStartTransaction(req: RemoteStartTransactionReq) = Future.successful(RemoteStartTransactionRes(accepted = false))

  def remoteStopTransaction(req: RemoteStopTransactionReq) = Future.successful(RemoteStopTransactionRes(accepted = false))

  def unlockConnector(req: UnlockConnectorReq) = Future.successful(UnlockConnectorRes(UnlockStatus.NotSupported))

  def getDiagnostics(req: GetDiagnosticsReq) = {
    val fileName = "test-getdiagnostics-upload"
    uploadActor ! UploadJob(req.location, fileName)
    Future.successful(GetDiagnosticsRes(Some(fileName)))
  }

  def changeConfiguration(req: ChangeConfigurationReq): Future[ChangeConfigurationRes] = Future.successful(ChangeConfigurationRes(ConfigurationStatus.NotSupported))

  def getConfiguration(req: GetConfigurationReq) = Future.successful(GetConfigurationRes(Nil, req.keys))

  def changeAvailability(req: ChangeAvailabilityReq) = Future.successful(ChangeAvailabilityRes(AvailabilityStatus.Rejected))

  def reset(req: ResetReq) = Future.successful(ResetRes(accepted = false))

  def updateFirmware(req: UpdateFirmwareReq) = Future.successful(())

  def sendLocalList(req: SendLocalListReq) = Future.successful(SendLocalListRes(UpdateStatusWithoutHash.NotSupported))

  def getLocalListVersion = Future.successful(GetLocalListVersionRes(AuthListNotSupported))

  def dataTransfer(req: ChargePointDataTransferReq) = Future.successful(ChargePointDataTransferRes(DataTransferStatus.UnknownVendorId))

  def reserveNow(req: ReserveNowReq) = Future.successful(ReserveNowRes(Reservation.Rejected))

  def cancelReservation(req: CancelReservationReq) = Future.successful(CancelReservationRes(accepted = false))

  def clearChargingProfile(req: ClearChargingProfileReq) = Future.successful(ClearChargingProfileRes(ClearChargingProfileStatus.Unknown))

  def getCompositeSchedule(req: GetCompositeScheduleReq) = Future.successful(GetCompositeScheduleRes(CompositeScheduleStatus.Rejected))

  def setChargingProfile(req: SetChargingProfileReq) = Future.successful(SetChargingProfileRes(ChargingProfileStatus.NotSupported))

  def triggerMessage(req: TriggerMessageReq) = Future.successful(TriggerMessageRes(TriggerMessageStatus.NotImplemented))

  override def apply[REQ <: ChargePointReq, RES <:ChargePointRes](req: REQ)
                                                                 (implicit reqRes: ChargePointReqRes[REQ, RES],
                                                                  ec: ExecutionContext) = {
    implicit val timeout = Timeout(500 millis)
    val future = actor ? req
    val res = try Await.result(future, timeout.duration).asInstanceOf[RES] catch {
      case _: TimeoutException => super.apply(req)(reqRes, ec)
    }
    logger.info(s"$chargerId\n\t>> $req\n\t<< $res")
    Future.successful(res.asInstanceOf[RES])
  }
}

class Uploader extends Actor with LazyLogging {
  def receive = {
    case UploadJob(location, filename) =>
      logger.debug("Uploader being run")
      val client = new FTPSClient()
      try {
        client.connect(location.getHost)
        val authPart = location.getAuthority
        val userAndPasswd = authPart.split("@")(0).split(":")
        val loggedIn = client.login(userAndPasswd(0), userAndPasswd(1))
        logger.debug(if (loggedIn) "Uploader logged in" else "FTP login failed")
        val dateTimeString = new SimpleDateFormat("yyyyMMddHHmmssz").format(ChargerClock.now)
        val remoteName = s"${location.getPath}/$filename.$dateTimeString"
        client.enterLocalPassiveMode()
        logger.debug(s"Storing file at $remoteName")
        val success = client.storeFile(remoteName, new ByteArrayInputStream("zlorg".getBytes(Charset.defaultCharset())))
        logger.info(s"Uploader completed ${if (success) "successfully: " else "with error: "} ${client.getReplyString}")
      } catch {
        case e: Exception => logger.error("Uploading diagnostics failed", e)
      }
  }
}

case class UploadJob(location: URI, filename: String)
