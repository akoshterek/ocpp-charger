package com.thenewmotion.chargenetwork.ocpp.charger

import akka.actor.ActorRef
import akka.http.scaladsl.marshalling.ToEntityMarshaller
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.server.Directives._
import akka.util.Timeout

import scala.concurrent.duration._
import akka.pattern.{AskableActorRef, ask}
import com.thenewmotion.chargenetwork.ocpp.charger.ChargerActor._
import io.swagger.annotations.Api
//import javax.ws.rs.Path

import scala.concurrent.Await
import scala.reflect.ClassTag
import scala.util.{Failure, Success}

@Api(value = "/charger", produces = "application/json")
//@Path("/charger")
class Rest {
  implicit val timeout: Timeout = Timeout(60.seconds)
  implicit val marshaller: ToEntityMarshaller[AnyRef] = JacksonSupport.JacksonMarshaller

  val route: Route = {
    pathPrefix("charger" / Segment) { chargerId =>
      ChargerActor.Resolver.resolve(chargerId) match {
        case None =>
          complete(StatusCodes.NotFound, "Charger %s not found".format(chargerId))
        case Some(chargerActor) =>
          chargerActions(chargerId, chargerActor)
      }
    }
  }

  private def ask[Q, R: ClassTag](chargerActor: AskableActorRef, request: Q, fallback: R): R = {
    val future = chargerActor.ask(request).mapTo[R]
    Await.ready(future, timeout.duration)
    future.value match {
      case None => fallback
      case Some(Failure(e)) => throw e
      case Some(Success(result)) => result
    }
  }

  private def chargerActions(chargerId: String, chargerActor: ActorRef): Route = {
    pathPrefix(IntNumber) { connectorId =>
      if (ask(chargerActor, ChargerActor.ConnectorExists(connectorId - 1), false)) {
        connectorActions(chargerActor, connectorId - 1)
      } else {
        complete(StatusCodes.NotFound, s"Connector $chargerId:$connectorId not found")
      }
    } ~
    showConnectors(chargerActor) ~
    showConnectorsWithNotification(chargerActor)
  }

  private def connectorActions(chargerActor: ActorRef, connectorId: Int): Route = {
    post {
      plug(chargerActor, connectorId) ~
      unplug(chargerActor, connectorId) ~
      swipeCard(chargerActor, connectorId) ~
      readMeterAndNotify(chargerActor, connectorId)
    } ~
    get {
      path("meter") {
        val result = ask[MeterValue, Int](chargerActor, MeterValue(connectorId), 0)
        complete(Map(connectorId + 1 -> result))
      } ~
      pathEnd {
        completeWithConnectorState(chargerActor, connectorId)
      }
    }
  }

  private def plug(chargerActor: ActorRef, connectorId: Int): Route = {
    path("plug") {
      chargerActor ! Plug(connectorId)
      completeWithConnectorState(chargerActor, connectorId)
    }
  }

  private def unplug(chargerActor: ActorRef, connectorId: Int): Route = {
    path("unplug") {
      chargerActor ! Unplug(connectorId)
      completeWithConnectorState(chargerActor, connectorId)
    }
  }

  private def swipeCard(chargerActor: ActorRef, connectorId: Int): Route = {
    path("swipecard") {
      entity(as[String]) { rfid =>
        chargerActor ! SwipeCard(connectorId, rfid)
        completeWithConnectorState(chargerActor, connectorId)
        completeWithConnectorState(chargerActor, connectorId)
      }
    }
  }

  private def readMeterAndNotify(chargerActor: ActorRef, connectorId: Int): Route = {
    path("meter") {
      val result = ask[MeterValue, Int](chargerActor, MeterValue(connectorId, sendNotification = true), 0)
      complete(Map(connectorId + 1 -> result))
    }
  }

  private def completeWithConnectorState(chargerActor: AskableActorRef, connectorId: Int): Route = {
    val result = ask[StateRequest, ConnectorActor.State](chargerActor, StateRequest(connectorId), ConnectorActor.Faulted)
    complete(Map(connectorId + 1 -> result.toString))
  }

  private def showConnectors(chargerActor: ActorRef): Route = {
    path("showconnectors") {
      get {
        showConnectors(chargerActor, sendNotification = false)
      }
    }
  }

  private def showConnectorsWithNotification(chargerActor: ActorRef): Route = {
    path("showconnectors") {
      post {
        showConnectors(chargerActor, sendNotification = true)
      }
    }
  }

  private def showConnectors(chargerActor: ActorRef, sendNotification: Boolean): Route = {
    val result = ask(chargerActor, StateRequest(-1, sendNotification), Seq[ConnectorActor.State]())
      .map(_.toString)
      .zipWithIndex
      .map({case (v, k) => k + 1 -> v})
      .toMap
    complete(result)
  }
}

