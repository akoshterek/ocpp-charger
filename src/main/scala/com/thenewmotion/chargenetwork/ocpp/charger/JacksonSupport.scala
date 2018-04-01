package com.thenewmotion.chargenetwork.ocpp.charger

import akka.http.scaladsl.marshalling.{Marshaller, ToEntityMarshaller}
import akka.http.scaladsl.model.{HttpEntity, HttpRequest}
import akka.http.scaladsl.unmarshalling.FromRequestUnmarshaller
import akka.http.scaladsl.model.MediaTypes._
import akka.stream.Materializer

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.scala.DefaultScalaModule

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import scala.reflect.ClassTag

object JacksonSupport {
  private val mapper = new ObjectMapper()
  mapper.registerModule(DefaultScalaModule)

  implicit def JacksonMarshaller: ToEntityMarshaller[AnyRef] = {
    Marshaller.withFixedContentType(`application/json`) { obj =>
      HttpEntity(`application/json`, mapper.writeValueAsString(obj).getBytes("UTF-8"))
    }
  }

  implicit def JacksonUnmarshaller[T <: AnyRef](implicit c: ClassTag[T]): FromRequestUnmarshaller[T] = {
    new FromRequestUnmarshaller[T]{
      override def apply(request: HttpRequest)(implicit ec: ExecutionContext, materializer: Materializer): Future[T] = {
        request.entity.toStrict(5.seconds).map(_.data.decodeString("UTF-8")).map { str =>
          mapper.readValue(str, c.runtimeClass).asInstanceOf[T]
        }
      }
    }
  }
}
