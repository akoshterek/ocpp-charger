package com.thenewmotion.chargenetwork.ocpp.charger

import akka.actor._
import scala.concurrent.duration._

class ConnectorActor(service: ConnectorService)
  extends Actor
  with LoggingFSM[ConnectorActor.State, ConnectorActor.Data] {
  import ConnectorActor._

  startWith(Available, NoData)

  when(Available) {
    case Event(Plug, _) =>
      service.preparing()
      goto(Preparing)
  }

  when(Preparing) {
    case Event(SwipeCard(rfid), cs: ConnectorSettings)  =>
      if (service.authorize(rfid)) {
        val sessionId = service.startSession(rfid, initialMeterValue)
        service.charging()
        goto(Charging) using ChargingData(sessionId, initialMeterValue, cs)
      }
      else stay()

    case Event(Unplug, _) =>
      service.available()
      goto(Available)
  }

  when(Charging) {
    case Event(SwipeCard(rfid), ChargingData(transactionId, meterValue, _)) =>
      if (service.authorize(rfid) && service.stopSession(Some(rfid), transactionId, meterValue)) {
        service.finishing()
        goto(Finishing) using NoData
      }
      else stay()

    case Event(SendMeterValue, ChargingData(transactionId, meterValue, settings)) =>
      log.debug("Sending meter value")
      service.meterValue(transactionId, meterValue)
      stay() using ChargingData(transactionId, meterValue + 1, settings)

    case Event(StateRequest(sendNotification), _) =>
      sender ! getState(sendNotification)
      stay()

    case Event(_: Action, _) => stay()
  }

  when(Finishing) {
    case Event(Unplug, _) =>
      service.available()
      goto(Available)
  }

  onTransition {
    case _ -> Charging =>
      stateData match {
        case cd: ChargingData => startMeterValueTimer(cd.settings.meterValueInterval)
        case _ => startMeterValueTimer(ConnectorSettings().meterValueInterval)
      }

    case Charging -> _ => cancelTimer("meterValueTimer")
  }

  whenUnhandled {
    case Event(StateRequest(sendNotification), _) =>
      sender ! getState(sendNotification)
      stay()

    case Event(cs: ConnectorSettings, _) =>
      stay() using cs
  }

  onTermination {
    case StopEvent(_, Charging, ChargingData(transactionId, meterValue, _)) =>
      service.stopSession(None, transactionId, meterValue)
      println(getLog.mkString("\n\t"))
  }

  private def startMeterValueTimer(period: Int): Unit = {
    log.debug(s"Setting timer for meterValue (every $period seconds)")
    setTimer("meterValueTimer", SendMeterValue, period.seconds, repeat = true)
  }

  private def getState(sendNotification: Boolean): ConnectorActor.State = {
    if (sendNotification) {
      stateName match {
        case Available => service.available()
        case Preparing => service.preparing()
        case Charging => service.charging()
        case Finishing => service.finishing()
        case Faulted => service.faulted()
      }
    }
    stateName
  }
}

object ConnectorActor {
  val initialMeterValue = 100

  sealed trait State
  case object Available extends State
  case object Preparing extends State
  case object Charging extends State
  case object Finishing extends State
  //case object Reserved extends State
  //case object Unavailable extends State
  case object Faulted extends State

  sealed trait Action
  case object Plug extends Action
  case object Unplug extends Action
  case class SwipeCard(rfid: String) extends Action
  case class StateRequest(sendNotification: Boolean) extends Action
  case object Fault

  sealed abstract class Data
  case object NoData extends Data
  case class ConnectorSettings(power: Double = 3.7, meterValueInterval: Int = 20) extends Data
  case class ChargingData(transactionId: Int, meterValue: Int, settings: ConnectorSettings) extends Data

  case object SendMeterValue
}
