package com.thenewmotion.chargenetwork.ocpp.charger

import akka.actor.ActorRef
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.server.Directives._
import akka.util.Timeout

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.{Failure, Success}

class JsonWebServer(config: ChargerConfig) {
  implicit val timeout: Timeout = Timeout(5.seconds)

  val route: Route =
    post {
      pathPrefix("charger" / Segment) { chargerId: String =>
        val a: Option[ActorRef] = ActorsResolver.resolve(chargerId)
        val dd: Int = 5
        dd match {
          4 => 2
          _ => 0
        }
        /*
        a match {
        None => complete (StatusCodes.Accepted, "Charger %s not found".format (chargerId) )
        Some (ActorRef) => null

        }*/
        complete(StatusCodes.NotFound)

      }
    }
}
     /*
          complete(StatusCodes.Accepted, "Charger %s not found".format(connectorId))})

        if (config.chargerId() == chargerId) {
          path(IntNumber) { connectorId =>
            complete(StatusCodes.Accepted, "Connector %d not found".format(connectorId))
          }
        }
        complete(StatusCodes.NotFound)
      } */


