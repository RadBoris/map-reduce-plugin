lazy val root = (project in file(".")).
  
settings(
    name := "map-reduce-plugin",
    version := "0.2.0",
    scalaVersion := "2.12.8",
    scalacOptions in ThisBuild ++= Seq("-unchecked", "-deprecation", "-encoding", "UTF-8",  "-Xlint"),
    organization := "com.github.radboris",
    sbtPlugin := true,
  )
