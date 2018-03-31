package com.thenewmotion.chargenetwork.ocpp.charger

import java.time.temporal.ChronoUnit
import java.time.{ZoneOffset, ZonedDateTime}

object ChargerClock {
  private var offsetMillis: Long = 0

  def syncWithCentralSystem(heartbeatTime: ZonedDateTime): Unit = {
    //Millis is enough, don't bother about network latency
    this.offsetMillis = ZonedDateTime.now(ZoneOffset.UTC).until(heartbeatTime, ChronoUnit.MILLIS)
  }

  def now: ZonedDateTime = ZonedDateTime.now(ZoneOffset.UTC).plus(offsetMillis, ChronoUnit.MILLIS)
}
