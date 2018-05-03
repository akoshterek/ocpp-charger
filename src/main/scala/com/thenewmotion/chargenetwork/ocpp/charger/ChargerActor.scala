package com.thenewmotion.chargenetwork.ocpp.charger

import akka.actor._
import akka.pattern.ask
import akka.util.Timeout
import com.thenewmotion.ocpp.messages.UpdateStatusWithoutHash.VersionMismatch

import scala.concurrent.duration._
import com.thenewmotion.ocpp.messages._

import scala.collection.immutable.TreeMap
import scala.concurrent.{Await, Future}
import scala.language.postfixOps
import scala.util.{Failure, Success}

class ChargerActor(service: BosService, numberOfConnectors: Int = 1, config: ChargerConfig)
  extends Actor
  with LoggingFSM[ChargerActor.State, ChargerActor.Data] {

  import ChargerActor._

  var localAuthList = LocalAuthList()
  var chargerParameters: Map[String, (Boolean, Option[String])] = TreeMap[String, (Boolean, Option[String])](
    ("chargerId", (true, Some(service.chargerId))),
    ("numberOfConnectors", (true, Some(numberOfConnectors.toString))),
    ("OCPP-Simulator", (true, None)),
    ("AuthorizeRemoteTxRequests", (false, Some(true.toString))),
    ("MeterValueSampleInterval", (false, Some(60.toString)))
  )(Ordering.by(_.toLowerCase))

  lazy val connectorActors: Vector[ActorRef] = (0 until numberOfConnectors).map(startConnector).toVector

  override def preStart() {
    val interval = service.boot()
    Future {
      service.available()
    }
    context.system.scheduler.schedule(1 second, interval, self, Heartbeat)
    scheduleFault()
  }

  def scheduleFault() {
    if (config.simulateFailure())
      context.system.scheduler.scheduleOnce(30 seconds, self, Fault)
  }

  startWith(Available, NoData)

  when(Available) {
    case Event(Plug(c), PluggedConnectors(cs)) =>
      if (c < numberOfConnectors) {
        if (!cs.contains(c)) dispatch(ConnectorActor.Plug, c)
        stay() using PluggedConnectors(cs + c)
      } else stay()

    case Event(Unplug(c), PluggedConnectors(cs)) =>
      if (c < numberOfConnectors) {
        if (cs.contains(c)) dispatch(ConnectorActor.Unplug, c)
        stay() using PluggedConnectors(cs - c)
      } else stay()

    case Event(SwipeCard(c, card), PluggedConnectors(cs)) =>
      if (cs.contains(c)) {
        sendConnectorSettings(connector(c))
        dispatch(ConnectorActor.SwipeCard(card), c)
      }
      stay()

    case Event(RemoteStartTransactionReq(idTag, maybeConnectorScope, _), PluggedConnectors(cs)) =>
      if (maybeConnectorScope.nonEmpty && maybeConnectorScope.get.id < numberOfConnectors) {
        val c = maybeConnectorScope.get.id

        Await.result(askConnectorState(c, sendNotification = false), 30.seconds) match {
          case ConnectorActor.Charging | ConnectorActor.Faulted =>
            sender ! RemoteStartTransactionRes(false)
            stay()
          case _ =>
            sendConnectorSettings(connector(c))
            sender ! RemoteStartTransactionRes(true)
            dispatch(ConnectorActor.RemoteStartTransaction(idTag), c)
            stay() using PluggedConnectors(cs + c)
        }
      } else {
        sender ! RemoteStartTransactionRes(false)
        stay()
      }

    case Event(RemoteStopTransactionReq(transactionIdToStop), PluggedConnectors(cs)) =>
      val maybeTransaction = cs.map(c => (c, askConnectorStateData(c)))
        .map(pair => {
          val typedFuture = pair._2.mapTo[ConnectorActor.Data].fallbackTo(Future.successful(ChargerActor.NoData))
          (pair._1, Await.result(typedFuture, 30.seconds))
        })
        .find(pair => pair._2 match {
          case ConnectorActor.ChargingData(transactionId, _) => transactionId == transactionIdToStop
          case _ => false
        })

      if (maybeTransaction.nonEmpty) {
        dispatch(ConnectorActor.RemoteStopTransaction(transactionIdToStop), maybeTransaction.get._1)
        sender ! RemoteStopTransactionRes(true)
        stay() using PluggedConnectors(cs - maybeTransaction.get._1)
      } else {
        sender ! RemoteStopTransactionRes(false)
        stay()
      }

    case Event(UnlockConnectorReq(c), PluggedConnectors(cs)) =>
      if (cs.contains(c.id)) {
        Await.ready(connector(c.id).ask(ConnectorActor.UnlockConnector)(30.seconds), 30.seconds)
        sender ! UnlockConnectorRes(UnlockStatus.Unlocked)
        stay() using PluggedConnectors(cs - c.id)
      } else {
        sender ! UnlockConnectorRes(if (c.id < numberOfConnectors) UnlockStatus.Unlocked else UnlockStatus.UnlockFailed)
        stay()
      }

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

    case Event(ConnectorExists(connector), _) =>
      sender ! (connector >= 0 && connector < connectorActors.size)
      stay()

    case Event(StateRequest(c, sendNotification), _) =>
      if (c >= 0) {
        forward(ConnectorActor.StateRequest(sendNotification), c)
      } else {
        import akka.pattern.pipe

        val future = Future.sequence(
          connectorActors.zipWithIndex.map(c => askConnectorState(c._2, sendNotification))
        )

        future.pipeTo(sender)
      }
      stay()

    case Event(MeterValue(c, sendNotification), _) =>
      forward(ConnectorActor.MeterValueRequest(sendNotification), c)
      stay()
  }

  initialize()

  def startConnector(c: Int): ActorRef = {
    context.actorOf(Props(new ConnectorActor(service.connector(c))), c.toString)
  }

  def connector(c: Int): ActorRef = {
    connectorActors(c)
  }

  def dispatch(msg: ConnectorActor.Action, c: Int) {
    connector(c) ! msg
  }

  def forward(msg: ConnectorActor.Request, c: Int) {
    connector(c).forward(msg)
  }

  def askConnectorState(c: Int, sendNotification: Boolean): Future[ConnectorActor.State] = {
      connector(c).ask(ConnectorActor.StateRequest(sendNotification))(Timeout(30.seconds))
        .mapTo[ConnectorActor.State]
        .fallbackTo(Future.successful(ConnectorActor.Faulted))
  }

  def askConnectorStateData(c: Int): Future[ConnectorActor.Data] = {
    connector(c).ask(ConnectorActor.StateDataRequest)(Timeout(30.seconds))
      .mapTo[ConnectorActor.Data]
      .fallbackTo(Future.successful(ConnectorActor.NoData))
  }

  def sendConnectorSettings(c: ActorRef): Unit = {
    c ! ConnectorActor.ConnectorSettings(config.connectorPower(),
      chargerParameters.getOrElse("MeterValueSampleInterval", (false, Some("60")))._2.map(s => s.toInt).getOrElse(60))
  }
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
  case class ConnectorExists(connector: Int) extends Action

  sealed trait UserAction extends Action
  case class Plug(connector: Int) extends UserAction
  case class Unplug(connector: Int) extends UserAction
  case class SwipeCard(connector: Int, card: String) extends UserAction
  case class StateRequest(connector: Int, sendNotification: Boolean = false) extends UserAction
  case class MeterValue(connector: Int, sendNotification: Boolean = false) extends UserAction

  object Resolver {
    def name(chargerId: String): String = "charger$" + chargerId

    def resolve(chargerId: String): Option[ActorRef] =
      findActor("charger$" + chargerId)

    private def findActor(name: String): Option[ActorRef] = {
      Await.ready(system.actorSelection("user/" + name).resolveOne(5.seconds), Duration.Inf).value.get match {
        case Success(t) => Some(t)
        case Failure(_) => None
      }
    }
  }
}

case class LocalAuthList(version: AuthListSupported = AuthListSupported(0),
                         data: Map[String, IdTagInfo] = Map())
