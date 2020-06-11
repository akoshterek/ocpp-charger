package com.thenewmotion.chargenetwork.ocpp.charger

import com.thenewmotion.ocpp.messages.ChargePointStatus.{Available, Faulted, Occupied}
import com.thenewmotion.ocpp.messages._
import com.thenewmotion.ocpp.messages.meter._

import scala.concurrent.duration.FiniteDuration

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
  def preparing()

  def charging()

  def available()

  def finishing()

  def faulted()

  def authorize(card: String): Boolean

  def startSession(card: String, meterValue: Int): (Int, AuthorizationStatus)

  def meterValue(transactionId: Int, meterValue: Int)

  def stopSession(card: Option[String], transactionId: Int, meterValue: Int, stopReason: StopReason = StopReason.default): Boolean
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
    firmwareVersion = config.firmwareVersion.toOption,
    iccid = config.iccid.toOption,
    imsi = config.imsi.toOption,
    meterType = config.meterType.toOption,
    meterSerialNumber = config.meterSerial.toOption)).interval

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

  def connector(idx: Int) = new ConnectorServiceImpl(service, idx, config)
}

class ConnectorServiceImpl(protected val service: SyncCentralSystem, connectorId: Int,
                           val config: ChargerConfig) extends ConnectorService with Common {

  def preparing() {
    notification(Occupied(Some(OccupancyKind.Preparing), Some("Preparing")), Some(connectorId))
  }

  def charging() {
    notification(Occupied(Some(OccupancyKind.Charging), Some("Charging")), Some(connectorId))
  }

  def finishing() {
    notification(Occupied(Some(OccupancyKind.Finishing), Some("Finishing")), Some(connectorId))
  }

  def faulted(): Unit = {
    notification(Faulted(Some(ChargePointErrorCode.InternalError), Some("InternalError"), None))
  }

  def available() {
    notification(Available(), Some(connectorId))
  }

  def authorize(card: String): Boolean = service(AuthorizeReq(card)).idTag.status == AuthorizationStatus.Accepted

  def startSession(card: String, meterValue: Int): (Int, AuthorizationStatus) = {
    val res = service(StartTransactionReq(ConnectorScope(connectorId), card, ChargerClock.now, meterValue, None))

    if (config.isAblVendor && config.isEichrechtCharger) {
      signedAblMeterValue(res.transactionId, meterValue)
    }

    (res.transactionId, res.idTag.status)
  }

  def meterValue(transactionId: Int, meterValue: Int) {
    val meter = Meter(ChargerClock.now, List(DefaultValue.apply(meterValue)))
    service(MeterValuesReq(ConnectorScope(connectorId), Some(transactionId), List(meter)))
  }

  def signedAblMeterValue(transactionId: Int, meterValue: Int): Unit = {
    service(MeterValuesReq(ConnectorScope(connectorId), Some(transactionId), List(signedMeterValuesBeginTransaction(meterValue))))
  }

  def signedMeterValuesBeginTransaction(meter: Int): Meter = {
    val meterValue: Value = Value(value = meter.toString,
      context = ReadingContext.TransactionBegin,
      format = ValueFormat.Raw,
      measurand = Measurand.EnergyActiveImportRegister,
      phase = None,
      location = Location.Outlet,
      unit = UnitOfMeasure.Kwh)

    val signedMeterReading: Value = meterValue.copy(context = ReadingContext.TransactionBegin,
      format = ValueFormat.SignedData, value = signedMeter)

    Meter(ChargerClock.now, List(meterValue, signedMeterReading))
  }

  def signedMeter: String = <values>
    <value>
      <signedData
      format="ALFEN">AP;0;3;ALCV3ABBBISHMA2RYEGAZE3HV5YQBQRQAEHAR2MN;BIHEIWSHAAA2W2V7OYYDCNQAAAFACRC2I4ADGAETI4AAAABAOOJYUAGMXEGV4AIAAEEAB7Y6AAO3EAIAAAAAAABQGQ2UINJZGZATGMJTGQ4DAAAAAAAAAACXAAAABKYAAAAA====;R7KGQ3CEYTZI6AWKPOA42MXJTGBW27EUE2E6X7J77J5WMQXPSOM3E27NMVM2D77DPTMO3YACIPTRI===;
      </signedData>
    </value>
  </values>.toString()

  def stopSession(card: Option[String], transactionId: Int, meterValue: Int, stopReason: StopReason = StopReason.default): Boolean =
    service(StopTransactionReq(transactionId, card, ChargerClock.now, meterValue, stopReason, Nil))
      .idTag.exists(_.status == AuthorizationStatus.Accepted)
}
