package com.thenewmotion.chargenetwork
package ocpp.charger

import java.util.concurrent.TimeUnit

import org.specs2.mutable.SpecificationWithJUnit
import akka.testkit.{TestFSMRef, TestKit}
import org.specs2.mock.Mockito
import org.specs2.specification.Scope
import akka.actor.ActorSystem
import ConnectorActor._
import com.thenewmotion.ocpp.messages
import com.thenewmotion.ocpp.messages.AuthorizationStatus._
import com.thenewmotion.ocpp.messages.StopReason

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

    "not start charging when card is accepted but concurrent transaction is going on" in new ConnectorActorScope {
      actor.setState(stateName = Preparing)
      service.authorize(rfid) returns true
      service.startSession(rfid, MeterActor.initialTicks * 10) returns ((12345, ConcurrentTx))

      actor receive SwipeCard(rfid)

      actor.stateName mustEqual Preparing
      there was one(service).authorize(rfid)
    }

    "start charging when card accepted" in new ConnectorActorScope {
      actor.setState(stateName = Preparing)
      service.authorize(rfid) returns true
      service.startSession(rfid, MeterActor.initialTicks * 10) returns ((12345, Accepted))

      actor receive SwipeCard(rfid)

      actor.stateName mustEqual Charging
      actor.stateData mustEqual ChargingData(12345, rfid)

      there was one(service).authorize(rfid)
      there was one(service).startSession(rfid, MeterActor.initialTicks * 10)
    }

    "stop charging when card accepted" in new ConnectorActorScope {
      actor.setState(stateName = Charging, stateData = ChargingData(12345, rfid))
      service.authorize(rfid) returns true
      service.stopSession(card = ===(Some(rfid)), transactionId = ===(12345), any, any) returns true

      actor receive SwipeCard(rfid)
      actor.stateName mustEqual Finishing
      actor.stateData mustEqual NoData

      there was one(service).authorize(rfid)
      there was one(service).stopSession(===(Some(rfid)), ===(12345), any, any)
    }

    "not stop charging when card declined" in new ConnectorActorScope {
      actor.setState(stateName = Charging, stateData = ChargingData(12345, rfid))
      service.authorize(rfid) returns false

      actor receive SwipeCard(rfid)
      actor.stateName mustEqual Charging
      there was one(service).authorize(rfid)
      there was no(service).stopSession(any, any, any, any)
    }

    "not stop charging when other card was used" in new ConnectorActorScope {
      actor.setState(stateName = Charging, stateData = ChargingData(12345, rfid))
      actor receive SwipeCard(rfid + "other")

      actor.stateName mustEqual Charging
      there was no(service).authorize(any)
      there was no(service).stopSession(any, any, any, any)
    }

    "stop charging on termination" in new ConnectorActorScope {
      actor.setState(stateName = Charging, stateData = ChargingData(12345, rfid))
      Await.ready(system.terminate(), new FiniteDuration(5, TimeUnit.SECONDS))

      there was one(service).stopSession(===(None), ===(12345), any, any)
    }

    "start charging when RemoteStartTransaction accepted" in new ConnectorActorScope {
      actor.setState(stateName = Available)
      service.authorize(rfid) returns true
      service.startSession(rfid, MeterActor.initialTicks * 10) returns ((12345, Accepted))

      actor receive RemoteStartTransaction(rfid)

      actor.stateName mustEqual Charging
      actor.stateData mustEqual ChargingData(12345, rfid)

      there was one(service).authorize(rfid)
      there was one(service).startSession(rfid, MeterActor.initialTicks * 10)
    }

    "stop charging when RemoteStopTransaction accepted" in new ConnectorActorScope {
      actor.setState(stateName = Charging, stateData = ChargingData(12345, rfid))
      service.stopSession(any, any, any, === (StopReason.Remote)) returns true

      actor receive RemoteStopTransaction(12345)
      actor.stateName mustEqual Available

      there was no(service).authorize(any)
      there was one(service).stopSession(any, any, any, === (StopReason.Remote))
    }

    "stop charging when UnlockConnector received" in new ConnectorActorScope {
      actor.setState(stateName = Charging, stateData = ChargingData(12345, rfid))
      service.stopSession(any, any, any, === (StopReason.UnlockCommand)) returns true

      actor receive UnlockConnector
      actor.stateName mustEqual Available

      there was no(service).authorize(any)
      there was one(service).stopSession(any, any, any, === (StopReason.UnlockCommand))
    }

    "unplug charging when UnlockConnector received" in new ConnectorActorScope {
      actor.setState(stateName = Preparing)

      actor receive UnlockConnector
      actor.stateName mustEqual Available
    }
  }

  class ConnectorActorScope
    extends TestKit(ActorSystem("test"))
    with Scope {
    val service: ConnectorService = mock[ConnectorService]
    val actor = TestFSMRef(new ConnectorActor(service))
    val rfid = "rfid"
  }
}
