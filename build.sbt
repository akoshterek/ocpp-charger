val ocppCharger = project
  .in(file("."))
  .enablePlugins(OssLibPlugin)
  .settings(
    name := "ocpp-charger",
    organization := "com.thenewmotion.chargenetwork",
    description := "OCPP Charger Simulator",
    fork in run := true,
    connectInput in run:= true,

    libraryDependencies ++= {
      val log = {
        Seq("ch.qos.logback" % "logback-classic" % "1.2.3",
            "com.typesafe.scala-logging" %% "scala-logging-slf4j" % "2.1.2")
      }

      val ocpp = {
        def libs(xs: String*) = xs.map(x => "com.thenewmotion.ocpp" %% s"ocpp-$x" % "7.0.0")
        libs("spray", "json", "j-api")
      }

      val spray = {
        Seq("io.spray" %% "spray-can" % "1.3.4")
      }

      val akka = {
        def libs(xs: String*) = xs.map(x => "com.typesafe.akka" %% s"akka-$x" % "2.5.12")

        libs("actor", "slf4j", "stream") ++
        libs("testkit").map(_ % "test")
      }

      val akkahttp = {
        def libs(xs: String*) = xs.map(x => "com.typesafe.akka" %% s"akka-http-$x" % "10.1.1")

        libs("core", "jackson")
      }

      val commons = "commons-net" % "commons-net" % "3.6"
      val scallop = "org.rogach" %% "scallop" % "3.1.2"
      val jackson = "com.fasterxml.jackson.module" %% "jackson-module-scala" % "2.9.5"

      val tests =
        Seq("core", "mock", "junit").map(n => "org.specs2" %% s"specs2-$n" % "2.5" % "test")

      log ++ akka ++ spray ++ ocpp ++ akkahttp ++ tests :+ commons :+ scallop :+ jackson
    }
  )

