package com.thenewmotion.chargenetwork
package ocpp.charger

import java.util.concurrent.TimeUnit

import org.specs2.mutable.SpecificationWithJUnit
import akka.testkit.{TestFSMRef, TestKit}
import org.specs2.mock.Mockito
import org.specs2.specification.Scope
import akka.actor.ActorSystem
import ConnectorActor._

import scala.concurrent.Await
import scala.concurrent.duration.FiniteDuration

/**
 * @author Yaroslav Klymko
 */
class ConnectorActorSpec extends SpecificationWithJUnit with Mockito {
  "ConnectorActor" should {

    "become occupied when plug connected" in new ConnectorActorScope {
      actor.stateName mustEqual Available
      actor receive Plug
      actor.stateName mustEqual Preparing
      there was one(service).preparing()
    }

    "become available when plug disconnected" in new ConnectorActorScope {
      actor.setState(stateName = Preparing)
      actor receive Unplug
      actor.stateName mustEqual Available
      there was one(service).available()
    }

    "not start charging when card declined" in new ConnectorActorScope {
      actor.setState(stateName = Preparing)
      service.authorize(rfid) returns false

      actor receive SwipeCard(rfid)

      actor.stateName mustEqual Preparing
      there was one(service).authorize(rfid)
    }

    "start charging when card accepted" in new ConnectorActorScope {
      actor.setState(stateName = Preparing)
      service.authorize(rfid) returns true
      service.startSession(rfid, ConnectorActor.initialMeterValue) returns 12345

      actor receive SwipeCard(rfid)

      actor.stateName mustEqual Charging
      actor.stateData mustEqual ChargingData(12345, ConnectorActor.initialMeterValue, ConnectorSettings())

      there was one(service).authorize(rfid)
      there was one(service).startSession(rfid, ConnectorActor.initialMeterValue)
    }

    "continue charging when card declined" in new ConnectorActorScope {
      actor.setState(stateName = Charging, stateData = ChargingData(12345, ConnectorActor.initialMeterValue, null))
      service.authorize(rfid) returns false

      actor receive SwipeCard(rfid)
      actor.stateName mustEqual Charging

      there was one(service).authorize(rfid)
    }

    "stop charging when card accepted" in new ConnectorActorScope {
      actor.setState(stateName = Charging, stateData = ChargingData(12345, ConnectorActor.initialMeterValue, ConnectorSettings()))
      service.authorize(rfid) returns true
      service.stopSession((===(Some(rfid))), (===(12345)), any) returns true

      actor receive SwipeCard(rfid)
      actor.stateName mustEqual Preparing
      actor.stateData mustEqual NoData

      there was one(service).authorize(rfid)
      there was one(service).stopSession((===(Some(rfid))), (===(12345)), any)
    }

    "not stop charging when card declined" in new ConnectorActorScope {
      actor.setState(stateName = Charging, stateData = ChargingData(12345, ConnectorActor.initialMeterValue, ConnectorSettings()))
      service.authorize(rfid) returns false

      actor receive SwipeCard(rfid)
      actor.stateName mustEqual Charging
      there was one(service).authorize(rfid)
      there was no(service).stopSession(any, any, any)
    }

    "stop charging on termination" in new ConnectorActorScope {
      actor.setState(stateName = Charging, stateData = ChargingData(12345, ConnectorActor.initialMeterValue, ConnectorSettings()))
      Await.ready(system.terminate(), new FiniteDuration(5, TimeUnit.SECONDS))

      there was one(service).stopSession(None, 12345, ConnectorActor.initialMeterValue)
    }
  }

  class ConnectorActorScope
    extends TestKit(ActorSystem("test"))
    with Scope {
    val service = mock[ConnectorService]
    val actor = TestFSMRef(new ConnectorActor(service))
    val rfid = "rfid"
  }
}
