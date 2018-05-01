package com.thenewmotion.chargenetwork.ocpp.charger

import akka.actor.ActorSystem
import akka.testkit.{TestFSMRef, TestKit}
import org.specs2.mock.Mockito
import org.specs2.mutable.SpecificationWithJUnit
import org.specs2.specification.Scope

class MeterActorSpec extends SpecificationWithJUnit with Mockito {
  "MeterActor" should {

  }

  class MeterActorScope
    extends TestKit(ActorSystem("test"))
      with Scope {
    val actor = TestFSMRef(new MeterActor)
  }
}
