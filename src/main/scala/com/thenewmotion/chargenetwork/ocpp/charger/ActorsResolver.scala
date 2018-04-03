package com.thenewmotion.chargenetwork.ocpp.charger

import akka.actor.ActorRef

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.{Failure, Success}

object ActorsResolver {
  def name(chargerId: String): String = "charger$" + chargerId

  def name(chargerId: String, connectorId: Int): String = name(chargerId) + "$" + connectorId

  def resolve(chargerId: String): Option[ActorRef] =
    findActor("charger$" + chargerId)

  def resolve(chargerId: String, connectorId: Int): Option[ActorRef] =
    findActor("charger$" + chargerId + "$" + connectorId)

  private def findActor(name: String): Option[ActorRef] = {
    Await.ready(system.actorSelection("user/" + name).resolveOne(5.seconds), Duration.Inf).value.get match {
      case Success(t) => Some(t)
      case Failure(_) => None
    }
  }
}
