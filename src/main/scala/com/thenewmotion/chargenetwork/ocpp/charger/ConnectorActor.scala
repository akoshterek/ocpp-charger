package com.thenewmotion.chargenetwork.ocpp.charger

import akka.actor._
import akka.pattern.ask
import akka.util.Timeout
import com.thenewmotion.ocpp.messages.StopReason

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._

class ConnectorActor(service: ConnectorService)
  extends Actor
  with LoggingFSM[ConnectorActor.State, ConnectorActor.Data] {
  import ConnectorActor._

  var power = 3.7
  var sendMeterValuesPeriod = 20
  lazy val meterActor: ActorRef = context.actorOf(Props(new MeterActor()), "meter")

  startWith(Available, NoData)

  when(Available) {
    case Event(Plug, _) =>
      service.preparing()
      goto(Preparing)

    case Event(RemoteStartTransaction(rfid), _) =>
      remoteStartTransaction(rfid)
  }

  when(Preparing) {
    case Event(SwipeCard(rfid), _)  =>
      swipeCardStartTransaction(rfid)

    case Event(RemoteStartTransaction(rfid), _) =>
      remoteStartTransaction(rfid)

    case Event(Unplug, _) =>
      service.available()
      goto(Available)
  }

  when(Charging) {
    case Event(SwipeCard(rfid), ChargingData(transactionId, transactionRfid)) =>
      if (transactionRfid == rfid
        && service.authorize(rfid)
        && service.stopSession(Some(rfid), transactionId, readMeter)) {
        service.finishing()
        goto(Finishing) using NoData
      }
      else stay()

    case Event(SendMeterValue, ChargingData(transactionId, _)) =>
      log.debug("Sending meter value")
      service.meterValue(transactionId, readMeter)
      stay()

    case Event(MeterValueRequest(true), ChargingData(transactionId, _)) =>
      val meterValue = readMeter
      service.meterValue(transactionId, meterValue)
      sender ! meterValue
      stay()

    case Event(RemoteStopTransaction(transactionIdToStop), ChargingData(transactionId, _)) =>
      if (transactionIdToStop == transactionId) {
        service.stopSession(None, transactionId, readMeter, StopReason.Remote)
        service.available()
        goto(Available)
      } else {
        stay()
      }

    case Event(UnlockConnector, ChargingData(transactionId, _)) =>
      service.stopSession(None, transactionId, readMeter, StopReason.UnlockCommand)
      service.available()
      sender ! true
      goto(Available)

    case Event(_: Action, _) => stay()
  }

  when(Finishing) {
    case Event(SwipeCard(rfid), _)  =>
      swipeCardStartTransaction(rfid)

    case Event(RemoteStartTransaction(rfid), _) =>
      remoteStartTransaction(rfid)

    case Event(Unplug, _) =>
      service.available()
      goto(Available)
  }

  onTransition {
    case _ -> Charging =>
      startMeterValueTimer(sendMeterValuesPeriod)
      meterActor ! MeterActor.Start(power)

    case Charging -> _ =>
      meterActor ! MeterActor.Stop
      cancelTimer("meterValueTimer")
  }

  whenUnhandled {
    case Event(StateRequest(sendNotification), _) =>
      sender ! getState(sendNotification)
      stay()

    case Event(MeterValueRequest(_), _) =>
      import akka.pattern.pipe
      readMeterAsync.pipeTo(sender)
      stay()

    case Event(StateDataRequest, _) =>
      sender ! stateData
      stay()

    case Event(SwipeCard(_), _) =>
      stay()

    case Event(cs: ConnectorSettings, _) =>
      power = cs.power
      sendMeterValuesPeriod = cs.sendMeterValuesPeriod
      stay()

    case Event(UnlockConnector, _) =>
      if (stateName != Available) {
        service.available()
      }
      sender ! true
      goto(Available)
  }

  onTermination {
    case StopEvent(_, Charging, ChargingData(transactionId, _)) =>
      service.stopSession(None, transactionId, readMeter)
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

  private def swipeCardStartTransaction(rfid: String): State = {
    import com.thenewmotion.ocpp.messages.AuthorizationStatus.Accepted

    if (service.authorize(rfid)) {
      service.startSession(rfid, readMeter) match {
        case (sessionId, Accepted) =>
          service.charging()
          goto(Charging) using ChargingData(sessionId, rfid)
        case (sessionId, _) =>
          service.stopSession(Some(rfid), sessionId, readMeter)
          stay()
      }
    }
    else {
      stay()
    }
  }

  private def remoteStartTransaction(rfid: String): State = swipeCardStartTransaction(rfid)

  private def readMeter: Int = Await.result(readMeterAsync, 5.seconds)

  private def readMeterAsync: Future[Int] = {
    meterActor.ask(MeterActor.Read)(Timeout(5.seconds))
      .mapTo[Int]
      .fallbackTo(Future.successful(0))
  }
}

object ConnectorActor {
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
  case class RemoteStartTransaction(rfid: String) extends Action
  case class RemoteStopTransaction(transactionId: Int) extends Action
  case object UnlockConnector extends Action
  case object Fault
  case object SendMeterValue

  sealed abstract class Data
  case object NoData extends Data

  case class ChargingData(transactionId: Int, rfid: String) extends Data

  sealed trait Request
  case class StateRequest(sendNotification: Boolean) extends Request
  case class ConnectorSettings(power: Double = 3.7, sendMeterValuesPeriod: Int = 20) extends Request
  case class MeterValueRequest(sendNotification: Boolean) extends Request
  case object StateDataRequest extends Request
}
