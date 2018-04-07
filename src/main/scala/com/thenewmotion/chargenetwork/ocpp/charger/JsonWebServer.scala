package com.thenewmotion.chargenetwork.ocpp.charger

import akka.actor.ActorRef
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.server.Directives._
import akka.util.Timeout

import scala.concurrent.duration._
import akka.pattern.ask

import scala.concurrent.{Await, Future}

object JsonWebServer {
  implicit val timeout: Timeout = Timeout(5.seconds)

  val route: Route =
    post {
      pathPrefix("charger" / Segment) { chargerId =>
        ChargerActor.Resolver.resolve(chargerId) match {
          case None =>
            complete (StatusCodes.NotFound, "Charger %s not found".format(chargerId))
          case Some (chargerActor) => pathPrefix(IntNumber) { connectorId =>
            val exists = Await.result(chargerActor.ask(ChargerActor.ConnectorExists(connectorId - 1))
              .mapTo[Boolean]
              .fallbackTo(Future.successful(false)),
              timeout.duration
            )

            if (exists) {
              connectorActions(chargerActor, connectorId - 1)
            } else {
              complete (StatusCodes.NotFound, s"Connector $chargerId:$connectorId not found")
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
