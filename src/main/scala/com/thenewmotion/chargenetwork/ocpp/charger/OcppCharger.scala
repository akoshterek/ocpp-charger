package com.thenewmotion.chargenetwork.ocpp.charger

import java.net.URI
import javax.net.ssl.SSLContext

import dispatch.Http
import akka.actor.{ActorRef, Props}
import com.thenewmotion.ocpp.soap.CentralSystemClient
import com.thenewmotion.ocpp.Version

object OcppCharger {
  val CHARGER_ACTOR_NAME: String = "chargerActor"
}

trait OcppCharger {
  def chargerActor: ActorRef
}

class OcppSoapCharger(chargerId: String,
                      numConnectors: Int,
                      ocppVersion: Version,
                      centralSystemUri: URI,
                      server: ChargerServer,
                      config: ChargerConfig,
                      http: Http = new Http) extends OcppCharger {

  val client = CentralSystemClient(chargerId, ocppVersion, centralSystemUri, http, Some(server.url))
  val chargerActor = system.actorOf(Props(new ChargerActor(BosService(chargerId, client, config), numConnectors, config)), OcppCharger.CHARGER_ACTOR_NAME)
  server.actor ! ChargerServer.Register(chargerId, new ChargePointService(chargerId, chargerActor))
}

class OcppJsonCharger(chargerId: String,
                      numConnectors: Int,
                      centralSystemUri: URI,
                      authPassword: Option[String],
                      config: ChargerConfig)
                     (implicit sslContext: SSLContext = SSLContext.getDefault) extends OcppCharger {
  val client = JsonCentralSystemClient(chargerId, Version.V16, centralSystemUri, authPassword)
  val chargerActor = system.actorOf(Props(new ChargerActor(BosService(chargerId, client, config), numConnectors, config)), OcppCharger.CHARGER_ACTOR_NAME)
}
