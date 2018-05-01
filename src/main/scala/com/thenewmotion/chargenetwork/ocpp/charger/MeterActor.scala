package com.thenewmotion.chargenetwork.ocpp.charger

import akka.actor.LoggingFSM

import scala.concurrent.duration._

class MeterActor extends LoggingFSM[MeterActor.State, MeterActor.Data] {
  import MeterActor._

  startWith(Idle, Data(3.7, initialTicks))

  when(Idle) {
    case Event(Start(power), sd) =>
      if (power > 0) {
        val period = 3600 / (power * 1000) * 10 // seconds per tick
        log.debug(f"Setting timer for meter (every $period%4.2f seconds)")
        setTimer("meter", Tick, (period * 1000).millis, repeat = true)
        goto(Counting) using Data(power, sd.ticks)
      } else {
        log.debug(s"Invalid consumption power $power")
        stay()
      }
  }

  when(Counting) {
    case Event(Tick, sd) =>
      stay() using Data(sd.power, sd.ticks + 1)
    case Event(Stop, _) =>
      cancelTimer("meter")
      goto(Idle)
  }

  whenUnhandled {
    case Event(Read, sd) =>
      sender() ! toWatts(sd.ticks)
      stay()
  }

  def toWatts(ticks: Int): Int = ticks * 10 // 1 tick is 10 watts
}

object MeterActor {
  val initialTicks = 100

  sealed trait State
  case object Idle extends State
  case object Counting extends State

  sealed trait Action
  case class Start(power: Double) extends Action
  case object Stop extends Action
  case object Tick extends Action
  case object Read extends Action

  case class Data(power: Double, ticks: Int)
}