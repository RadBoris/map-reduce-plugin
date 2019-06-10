lazy val root = (project in file(".")).

settings(
    name := "MapReducePlugin",
    version := "0.2.0",
    scalaVersion := "2.12.8",
    scalacOptions in ThisBuild ++= Seq("-unchecked", "-deprecation", "-encoding", "UTF-8", "-Xlint"),
    organization := "com.github.radboris",
    sbtPlugin := true,
    unmanagedSourceDirectories in Compile += baseDirectory.value / "src" / "scala",
    libraryDependencies ++= Seq(
      "com.typesafe.akka" %% "akka-actor" % "2.5.22",
      "com.typesafe.akka" %% "akka-testkit" % "2.5.22" % "test",
      "org.scalactic" %% "scalactic" % "3.0.5" % "test",
      "org.scalatest" %% "scalatest" % "3.0.5" % "test"
    )
  )
