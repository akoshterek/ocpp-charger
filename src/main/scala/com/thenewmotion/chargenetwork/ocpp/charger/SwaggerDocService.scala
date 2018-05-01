package com.thenewmotion.chargenetwork.ocpp.charger

import com.github.swagger.akka.SwaggerHttpService

object SwaggerDocService extends SwaggerHttpService {
  override val apiClasses: Set[Class[_]] = Set(classOf[Rest])
  override val host = "localhost:8194"
}
