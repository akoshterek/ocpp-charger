package com.thenewmotion.chargenetwork.ocpp.charger

import akka.actor.ActorSystem
import akka.testkit.{TestFSMRef, TestKit}
import com.thenewmotion.chargenetwork.ocpp.charger.MeterActor._
import org.specs2.mock.Mockito
import org.specs2.mutable.SpecificationWithJUnit
import org.specs2.specification.Scope

class MeterActorSpec extends SpecificationWithJUnit with Mockito {
  "MeterActor" should {
    "become counting on start" in new MeterActorScope {
      actor.stateName mustEqual Idle
      actor receive Start(3.7)
      actor.stateName mustEqual Counting
    }

    "ignore invalid connector power" in new MeterActorScope {
      actor.stateName mustEqual Idle
      actor receive Start(-10)
      actor.stateName mustEqual Idle
    }

    "stop counting on stop event" in new MeterActorScope {
      actor.setState(stateName = Counting, stateData = Data(11, MeterActor.initialTicks))
      actor receive Stop
      actor.stateName mustEqual Idle
    }

    "ignore double stop" in new MeterActorScope {
      actor.stateName mustEqual Idle
      actor receive Stop
      actor.stateName mustEqual Idle
    }
  }

  class MeterActorScope
    extends TestKit(ActorSystem("test"))
      with Scope {
    val actor = TestFSMRef(new MeterActor)
  }
}
