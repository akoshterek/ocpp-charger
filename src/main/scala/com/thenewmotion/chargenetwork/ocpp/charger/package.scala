package com.thenewmotion.chargenetwork.ocpp

import akka.actor.ActorSystem


package object charger {
  implicit val system = ActorSystem("ocpp-simulator")
  implicit val executionContext = system.dispatcher
}
