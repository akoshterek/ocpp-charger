package com.thenewmotion.chargenetwork.ocpp

import akka.actor.ActorSystem

import scala.concurrent.{Await, Future, Promise}
import scala.concurrent.duration.Deadline


package object charger {
  implicit val system = ActorSystem("ocpp-simulator")
  implicit val executionContext = system.dispatcher

  def executeAfterDeadline(d: Deadline, f: => Unit): Unit =
    Future(Await.ready(Promise().future, d.timeLeft)) onComplete (_ => f)
}
