package com.thenewmotion.chargenetwork.ocpp.charger

import akka.actor.ActorRef
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.server.Directives._
import akka.util.Timeout

import scala.concurrent.duration._

object JsonWebServer {
  implicit val timeout: Timeout = Timeout(5.seconds)

  val route: Route =
    post {
      pathPrefix("charger" / Segment) { chargerId =>
        ActorsResolver.resolve(chargerId) match {
          case None => complete (StatusCodes.NotFound, "Charger %s not found".format(chargerId))
          case Some (chargerActor) => path(IntNumber) { connectorId =>
            ActorsResolver.resolve(chargerId, connectorId) match {
              case None => complete(StatusCodes.NotFound, "Connector %d not found on charger %s".format(connectorId, chargerId))
              case Some(_) => connectorActions(chargerActor, connectorId)
            }
          }
        }
      }
    }

  private def connectorActions(chargerActor: ActorRef, connectorId: Int): Route = {
    import ChargerActor.{Plug, Unplug, SwipeCard}

    path("plug") {
      chargerActor ! Plug(connectorId)
      complete(StatusCodes.Accepted, "Plug")
    } ~
    path("unplug") {
      chargerActor ! Unplug(connectorId)
      complete(StatusCodes.Accepted, "Unplug")
    } ~
    path("swipecard") {
      entity(as[String]) { rfid =>
        chargerActor ! SwipeCard(connectorId, rfid)
        complete(StatusCodes.Accepted, "SwipeCard")
      }
    }
  }
}
