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
              case Some(connectorActor) => connectorActions(chargerActor, connectorActor, connectorId)
            }
          }

        }
        //complete(StatusCodes.NotFound)

      }
    }

  private def connectorActions(chargerActor: ActorRef, connectorActor: ActorRef, connectorId: Int): Route = {
    path("plug") {
      connectorActor ! ChargerActor.Plug(connectorId)
      complete(StatusCodes.Accepted, "Plug")
    } ~
    path("unplug") {
      connectorActor ! ChargerActor.Unplug(connectorId)
      complete(StatusCodes.Accepted, "Unplug")
    } ~
    path("swipecard") {
      complete("SwipeCard")
    }
  }
}
