package com.thenewmotion.chargenetwork.ocpp.charger

import akka.actor._
import akka.pattern.ask
import akka.util
import akka.util.Timeout
import com.thenewmotion.ocpp.messages.{GetConfigurationReq, GetConfigurationRes, KeyValue}

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{Failure, Success}

class ConnectorActor(service: ConnectorService, chargerActor: ActorRef)
  extends Actor
  with LoggingFSM[ConnectorActor.State, ConnectorActor.Data] {
  import ConnectorActor._

  startWith(Available, NoData)

  when(Available) {
    case Event(Plug, _) =>
      service.occupied()
      goto(Preparing)
  }

  when(Preparing) {
    case Event(SwipeCard(rfid), _)  =>
      if (service.authorize(rfid)) {
        val sessionId = service.startSession(rfid, initialMeterValue)
        goto(Charging) using ChargingData(sessionId, initialMeterValue)
      }
      else stay()

    case Event(Unplug, _) =>
      service.available()
      goto(Available)
  }

  when(Charging) {
    case Event(SwipeCard(rfid), ChargingData(transactionId, meterValue)) =>
      if (service.authorize(rfid) && service.stopSession(Some(rfid), transactionId, meterValue))
        goto(Preparing) using NoData
      else stay()

    case Event(SendMeterValue, ChargingData(transactionId, meterValue)) =>
      log.debug("Sending meter value")
      service.meterValue(transactionId, meterValue)
      stay() using ChargingData(transactionId, meterValue + 1)

    case Event(StateRequest, _) =>
      sender ! stateName
      stay()

    case Event(_: Action, _) => stay()
  }

  onTransition {
    case _ -> Charging =>
      implicit val timeout: util.Timeout = Timeout(5.seconds)
      (chargerActor ? GetConfigurationReq(List("MeterValueSampleInterval")))
        .mapTo[GetConfigurationRes]
        .fallbackTo(Future.successful(GetConfigurationRes(List(KeyValue("MeterValueSampleInterval", readonly = false, Some("20"))), List())))
        .onComplete({
          case Success(res) => startMeterValueTimer(res.values.head.value.fold(20)(_.toInt))
          case Failure(_) => startMeterValueTimer(20)
        })

    case Charging -> _ => cancelTimer("meterValueTimer")
  }

  whenUnhandled {
    case Event(StateRequest, _) =>
      sender ! stateName
      stay()
  }

  onTermination {
    case StopEvent(_, Charging, ChargingData(transactionId, meterValue)) =>
      service.stopSession(None, transactionId, meterValue)
      println(getLog.mkString("\n\t"))
  }

  private def startMeterValueTimer(period: Int): Unit = {
    log.debug(s"Setting timer for meterValue (every $period seconds)")
    setTimer("meterValueTimer", SendMeterValue, period.seconds, repeat = true)
  }
}

object ConnectorActor {
  val initialMeterValue = 100

  sealed trait State
  case object Available extends State
  case object Preparing extends State
  case object Charging extends State
  case object Finishing extends State
  case object Reserved extends State
  case object Unavailable extends State
  case object Faulted extends State

  sealed trait Action
  case object Plug extends Action
  case object Unplug extends Action
  case class SwipeCard(rfid: String) extends Action
  case object StateRequest extends Action
  case object Fault

  sealed abstract class Data
  case object NoData extends Data
  case class ChargingData(transactionId: Int, meterValue: Int) extends Data

  case object SendMeterValue
}
