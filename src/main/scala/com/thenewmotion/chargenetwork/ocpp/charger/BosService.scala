package com.thenewmotion.chargenetwork.ocpp.charger

import java.time.ZonedDateTime

import com.thenewmotion.ocpp.messages.ChargePointStatus.{Available, Faulted, Occupied}
import com.thenewmotion.ocpp.messages._
import com.thenewmotion.ocpp.messages.meter.{DefaultValue, Meter}

import scala.concurrent.duration.FiniteDuration
import scala.util.Random

/**
 * @author Yaroslav Klymko
 */
trait BosService {
  def chargerId: String
  def fault()
  def available()
  def boot(): FiniteDuration
  def heartbeat()
  def connector(idx: Int): ConnectorService
}

trait ConnectorService {
  def occupied()
  def available()
  def authorize(card: String): Boolean
  def startSession(card: String, meterValue: Int): Int
  def meterValue(transactionId: Int, meterValue: Int)
  def stopSession(card: Option[String], transactionId: Int, meterValue: Int): Boolean
}

trait Common {
  protected def service: SyncCentralSystem

  protected def notification(status: ChargePointStatus, connector: Option[Int] = None) {
    service(StatusNotificationReq(
      connector.map(ConnectorScope.apply) getOrElse ChargePointScope,
      status,
      Some(ChargerClock.now),
      None))
  }
}

object BosService {
  def apply(chargerId: String, service: SyncCentralSystem, config: ChargerConfig): BosService =
    new BosServiceImpl(chargerId, service, config)
}

class BosServiceImpl(val chargerId: String, protected val service: SyncCentralSystem, val config: ChargerConfig) extends BosService with Common {
  def boot(): FiniteDuration = service(BootNotificationReq(
    chargePointVendor = config.vendor(),
    chargePointModel = config.model(),
    chargePointSerialNumber = Some(config.serial()),
    chargeBoxSerialNumber = None, //deprecated
    firmwareVersion = config.firmwareVersion.get,
    iccid = config.iccid.get,
    imsi = config.imsi.get,
    meterType = config.meterType.get,
    meterSerialNumber = config.meterSerial.get)).interval

  private val errorCodes = ErrorCodes().iterator

  def fault() {
    notification(Faulted(Some(errorCodes.next()), Some("Random code"), Some("Random code")))
  }

  def available() {
    notification(Available())
  }

  def heartbeat() {
    ChargerClock.syncWithCentralSystem(service.heartbeat.currentTime)
  }

  def connector(idx: Int) = new ConnectorServiceImpl(service, idx)
}

class ConnectorServiceImpl(protected val service: SyncCentralSystem, connectorId: Int) extends ConnectorService with Common {

  private val random = new Random()

  def occupied() {
    notification(Occupied(Some(OccupancyKind.Charging), Some("Charging")), Some(connectorId))
  }

  def available() {
    notification(Available(), Some(connectorId))
  }

  def authorize(card: String): Boolean = service(AuthorizeReq(card)).idTag.status == AuthorizationStatus.Accepted

  def startSession(card: String, meterValue: Int): Int =
    service(StartTransactionReq(ConnectorScope(connectorId), card, ChargerClock.now, meterValue, None)).transactionId

  def meterValue(transactionId: Int, meterValue: Int) {
    val meter = Meter(ZonedDateTime.now, List(DefaultValue.apply(meterValue)))
    service(MeterValuesReq(ConnectorScope(connectorId), Some(transactionId), List(meter)))
  }

  def stopSession(card: Option[String], transactionId: Int, meterValue: Int): Boolean =
    service(StopTransactionReq(transactionId, card, ChargerClock.now, meterValue, StopReason.default, Nil))
      .idTag.exists(_.status == AuthorizationStatus.Accepted)
}