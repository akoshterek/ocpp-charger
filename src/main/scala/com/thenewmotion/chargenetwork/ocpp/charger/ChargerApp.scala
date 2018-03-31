package com.thenewmotion.chargenetwork.ocpp.charger

import akka.actor.Props
import com.thenewmotion.ocpp.Version
import java.net.URI

import java.util.Locale
import javax.net.ssl.SSLContext

object ChargerApp {

  def main(args: Array[String]) {
    val config = ChargerConfig(args)

    val version = try {
      Version.withName(config.protocolVersion())
    } catch {
      case e: NoSuchElementException => sys.error(s"Unknown protocol version ${config.protocolVersion()}")
    }

    val connectionType: ConnectionType = config.connectionType().toLowerCase(Locale.ENGLISH) match {
      case "json" => Json
      case "soap" => Soap
      case _ => sys.error(s"Unknown connection type ${config.connectionType()}")
    }

    val url = new URI(config.chargeServerUrl())
    val charger = if (connectionType == Json) {
      new OcppJsonCharger(
        config.chargerId(),
        config.numberOfConnectors(),
        url,
        config.authPassword.get,
        config
      )(config.keystoreFile.get.fold(SSLContext.getDefault) { keystoreFile =>
        SslContext(
          keystoreFile,
          config.keystorePassword()
        )
      })
    } else {
      val server = new ChargerServer(config.listenPort())
      new OcppSoapCharger(
        config.chargerId(),
        config.numberOfConnectors(),
        version.get,
        url,
        server,
        config
      )
    }

    if (config.simulateUser()) {
      (0 until config.numberOfConnectors()) map {
        c => system.actorOf(Props(new UserActor(charger.chargerActor, c, ActionIterator(config.passId()))))
      }
    }
  }

  sealed trait ConnectionType
  case object Json extends ConnectionType
  case object Soap extends ConnectionType
}
