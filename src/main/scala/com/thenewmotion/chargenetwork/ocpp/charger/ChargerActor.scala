package com.thenewmotion.chargenetwork.ocpp.charger

import akka.actor._
import com.thenewmotion.ocpp.messages.UpdateStatusWithoutHash.VersionMismatch

import scala.concurrent.duration._
import com.thenewmotion.ocpp.messages._

import scala.concurrent.{Await, Future}
import scala.language.postfixOps

class ChargerActor(service: BosService, numberOfConnectors: Int = 1, config: ChargerConfig)
  extends Actor
  with LoggingFSM[ChargerActor.State, ChargerActor.Data] {

  import ChargerActor._
  import context.dispatcher

  var localAuthList = LocalAuthList()
  var chargerParameters: Map[String, (Boolean, Option[String])] = Map[String, (Boolean, Option[String])](
    ("chargerId", (true, Some(service.chargerId))),
    ("numberOfConnectors", (true, Some(numberOfConnectors.toString))),
    ("OCPP-Simulator", (true, Some(""))),
    ("AuthorizeRemoteTxRequests", (false, Some(true.toString)))
  )

  override def preStart() {
    val interval = service.boot()
    Future {
      service.available()
    }
    context.system.scheduler.schedule(1 second, interval, self, Heartbeat)
    scheduleFault()

    (0 until numberOfConnectors).foreach(startConnector)
  }

  def scheduleFault() {
    if (config.simulateFailure())
      context.system.scheduler.scheduleOnce(30 seconds, self, Fault)
  }

  startWith(Available, NoData)

  when(Available) {
    case Event(Plug(c), PluggedConnectors(cs)) =>
      if (!cs.contains(c)) dispatch(ConnectorActor.Plug, c)
      stay() using PluggedConnectors(cs + c)
    case Event(Unplug(c), PluggedConnectors(cs)) =>
      if (cs.contains(c)) dispatch(ConnectorActor.Unplug, c)
      stay() using PluggedConnectors(cs - c)
    case Event(SwipeCard(c, card), PluggedConnectors(cs)) =>
      if (cs.contains(c)) dispatch(ConnectorActor.SwipeCard(card), c)
      stay()
    case Event(Fault, _) =>
      service.fault()
      goto(Faulted) forMax 5.seconds
  }

  when(Faulted) {
    case Event(StateTimeout, _) =>
      service.available()
      scheduleFault()
      goto(Available)
    case Event(_: UserAction, _) => stay()
  }

  whenUnhandled {
    case Event(GetLocalListVersionReq, _) =>
      sender ! GetLocalListVersionRes(localAuthList.version)
      stay()

    case Event(ClearCacheReq, _) =>
      sender ! ClearCacheRes(true)
      stay()

    case Event(SendLocalListReq(updateType: UpdateType, version, localAuthorisationList, _), _) =>

      val status = if (version.version <= localAuthList.version.version) VersionMismatch
      else {
        localAuthList = LocalAuthList(
          version = version,
          data = updateType match {
            case UpdateType.Full => localAuthorisationList.collect {
              case AuthorisationAdd(idTag, idTagInfo) => idTag -> idTagInfo
            }.toMap

            case UpdateType.Differential => localAuthorisationList.foldLeft(localAuthList.data) {
              case (data, AuthorisationAdd(idTag, idTagInfo)) => data + (idTag -> idTagInfo)
              case (data, AuthorisationRemove(idTag)) => data - idTag
            }
          })
        UpdateStatusWithHash.Accepted(None)
      }

      sender ! SendLocalListRes(status)
      stay()

    case Event(GetConfigurationReq(keys), _) =>
      val (values: List[KeyValue], unknownKeys: List[String]) =
        if (keys.isEmpty) chargerParameters.map {
          case (key, (readonly, value)) => KeyValue(key, readonly, value)
        }.toList -> Nil
        else {
          val data = keys.map {
            key => key -> chargerParameters.get(key).map {
              case (readonly, value) => KeyValue(key, readonly, value)
            }
          }
          val values = data.collect {
            case (_, Some(keyValue)) => keyValue
          }
          val unknownKeys = data.collect {
            case (key, None) => key
          }
          (values, unknownKeys)
        }

      sender ! GetConfigurationRes(values, unknownKeys)
      stay()

    case Event(ChangeConfigurationReq(key, value), _) =>
      val status = chargerParameters.get(key) match {
        case Some((true, _)) => ConfigurationStatus.Rejected
        case _ =>
          chargerParameters = chargerParameters + (key -> (false -> Some(value)))
          ConfigurationStatus.Accepted
      }
      sender ! ChangeConfigurationRes(status)
      stay()

    case Event(Heartbeat, _) =>
      service.heartbeat()
      stay()
  }

  initialize()

  def startConnector(c: Int) {
    context.actorOf(Props(new ConnectorActor(service.connector(c))), connectorActorName(c))
  }

  def connector(c: Int): ActorRef = {
    val timeout = 5.seconds
    Await.result(context.actorSelection("user/" + connectorActorName(c)).resolveOne(timeout), timeout)
  }

  def dispatch(msg: ConnectorActor.Action, c: Int) {
    connector(c) ! msg
  }

  private def connectorActorName(c: Int): String = config.chargerId() + "/" + c.toString
}

object ChargerActor {
  sealed trait State
  case object Available extends State
  case object Faulted extends State

  sealed trait Data
  val NoData = PluggedConnectors(Set())
  case class PluggedConnectors(ids: Set[Int]) extends Data

  sealed trait Action
  case object Heartbeat extends Action
  case object Fault extends Action

  sealed trait UserAction extends Action
  case class Plug(connector: Int) extends UserAction
  case class Unplug(connector: Int) extends UserAction
  case class SwipeCard(connector: Int, card: String) extends UserAction
}

case class LocalAuthList(version: AuthListSupported = AuthListSupported(0),
                         data: Map[String, IdTagInfo] = Map())
