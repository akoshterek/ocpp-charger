package com.thenewmotion.chargenetwork.ocpp.charger

import akka.actor.Props
import com.thenewmotion.ocpp.Version
import java.net.URI
import java.util.Locale

import akka.http.scaladsl.Http
import akka.stream.ActorMaterializer
import javax.net.ssl.SSLContext

import scala.io.StdIn
import scala.util.{Failure, Success, Try}

object ChargerApp {

  def main(args: Array[String]) {
    val config = ChargerConfig(args)

    val version = try {
      Version.withName(config.protocolVersion())
    } catch {
      case _: NoSuchElementException => sys.error(s"Unknown protocol version ${config.protocolVersion()}")
    }

    val connectionType: ConnectionType = config.connectionType().toLowerCase(Locale.ENGLISH) match {
      case "json" => Json
      case "soap" => Soap
      case s => sys.error(s"Unknown connection type $s")
    }

    Try {
      val url = new URI(config.chargeServerUrl())
      if (connectionType == Json) {
        new OcppJsonCharger(
          config.chargerId(),
          config.numberOfConnectors(),
          url,
          config.authPassword.toOption,
          config
        )(config.keystoreFile.toOption.fold(SSLContext.getDefault) { keystoreFile =>
          SslContext(keystoreFile, config.keystorePassword())
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
    } match {
      case Success(charger) =>
        onChargerStarted(charger)
      case Failure(e) =>
        println(e)
        system.terminate()
    }

    def onChargerStarted(charger: OcppCharger): Unit = {
      if (config.simulateUser()) {
        (0 until config.numberOfConnectors()) map {
          c => system.actorOf(Props(new UserActor(charger.chargerActor, c, ActionIterator(config.passId()))))
        }
      }

      implicit val materializer: ActorMaterializer = ActorMaterializer()
      val apiPort = config.listenApiPort()
      val bindingFuture = Http().bindAndHandle(Rest.route, "localhost", apiPort)

      println("Server online at http://localhost:%d/\nPress RETURN to stop...".format(apiPort))
      StdIn.readLine // let it run until user presses return

      bindingFuture
        .flatMap(_.unbind()) // trigger unbinding from the port
        .onComplete(_ => {
        charger.close()
        system.terminate()
      }) // and shutdown when done
    }
  }

  sealed trait ConnectionType
  case object Json extends ConnectionType
  case object Soap extends ConnectionType
}
