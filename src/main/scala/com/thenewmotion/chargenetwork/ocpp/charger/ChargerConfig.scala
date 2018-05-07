package com.thenewmotion.chargenetwork.ocpp.charger

import org.rogach.scallop.ScallopConf

object ChargerConfig {
  def apply(args: Seq[String]): ChargerConfig = new ChargerConfig(args)
}

class ChargerConfig(args: Seq[String]) extends ScallopConf(args) {
  val chargerId = opt[String]("id", descr = "Charge point ID of emulated charge point", default = Some("00055103978E"))
  val numberOfConnectors = opt[Int]("connectors", descr = "Number of connectors of emulated charge point", default = Some(2))
  val vendor = opt[String]("vendor", descr = "Charge point vendor", default = Some("The New Motion"))
  val model = opt[String]("model", descr = "Charge point model", default = Some("OCPP-Simulator"))
  val serial = opt[String]("serial", descr = "Charge point serial number", default = Some("00055103978E"))
  val firmwareVersion = opt[String]("firmware-version", descr = "Charge point firmware version", default = Some("OCPPSIM-0.1"))
  val iccid = opt[String]("iccid", descr = "Charge point SIM card ICCID", default = None)
  val imsi = opt[String]("imsi", descr = "Charge point SIM card IMSI", default = None)
  val meterType = opt[String]("meter-type", descr = "Charge point main meter type", default = Some("Dummy Meter"))
  val meterSerial = opt[String]("meter-serial", descr = "Charge point main meter serial number", default = Some("DUMMY-000001"))
  val connectorPower = opt[Double]("connector-power", descr = "kWh per connector [3.7 - 22]", default = Some(11))

  val passId = opt[String]("pass-id", descr = "RFID of pass to try to start sessions with", default = Some("3E60A5E2"))
  val protocolVersion = opt[String]("protocol-version", descr = "OCPP version (\"1.2\" or \"1.5\" or \"1.6\"", default = Some("1.6"))
  val connectionType = opt[String]("connection-type", descr = "whether to use WebSocket/JSON or HTTP/SOAP (either  \"json\" or \"soap\")", default = Some("json"))
  val listenPort = opt[Int]("listen", descr = "TCP port to listen on for remote commands (SOAP)", default = Some(8084))
  val listenApiPort = opt[Int]("listen-api", descr = "TCP port to listen on for REST API", default = Some(8184))
  val reconnectAfter = opt[Int]("reconnect", descr = "Reconnect after <arg> seconds in case of disconnect (JSON). 0 means no reconnect", default = Some(60))


  val simulateUser = toggle("user-simulation", descrYes = "Simulate user activity", descrNo = "Don't simulate user activity",  default = Some(false))
  val simulateFailure = toggle("failure-simulation", descrYes = "Simulate charger failure", descrNo = "Don't simulate charger failure",  default = Some(false))

  val authPassword = opt[String]("auth-password", descr = "set basic auth password", default = None)
  val keystoreFile = opt[String]("keystore-file", descr = "keystore file for ssl", default = None)
  val keystorePassword = opt[String]("keystore-password", descr = "keystore password", default = Some(""))
  val chargeServerUrl = trailArg[String](descr = "Charge server URL base (without trailing slash)", default = Some("http://127.0.0.1:8080/ocppws"))

  validate (listenPort) {
    p => validatePort(p)
  }

  validate (listenApiPort) {
    p => validatePort(p)
  }

  validate (connectorPower) {
    p =>
      if (p >= 3.7 && p <= 22) Right(Unit)
      else Left("Invalid connector power: " + p)
  }

  validate (reconnectAfter) {
    p =>
      if (p >= 0) Right(Unit)
      else Left("Invalid reconnect timeout: " + p)
  }

  private def validatePort(p: Int): Either[String, Unit] =
    if (p > 0 && p <= 65535) Right(Unit)
    else Left("Wrong port number: " + p)

  verify()
}
