name := "process-streaming"

organization := "com.github.sam"

scalaVersion := "2.11.8"

version := "1.0.0-SNAPSHOT"

val akkaVersion = "2.4.4"

libraryDependencies ++= Seq(
  "ch.qos.logback" % "logback-classic" % "1.1.3",
  "com.typesafe.akka" %% "akka-actor" % akkaVersion,
  "com.typesafe.akka" %% "akka-slf4j" % akkaVersion)
