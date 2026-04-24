import sbt.Keys.releaseNotesURL

ThisBuild / version := "0.0.1"
ThisBuild / organization := "io.akka.sbt"
ThisBuild / homepage := Some(url("https://github.com/akka/sbt-artifact-bom"))
ThisBuild / description := "sbt plugin to create Maven pom.xml with direct and indirect dependencies"
ThisBuild / scmInfo := Some(ScmInfo(url("https://github.com/akka/sbt-artifact-bom"), "scm:git@github.com:akka/sbt-artifact-bom.git"))
ThisBuild / releaseNotesURL := Some(url(s"https://github.com/akka/sbt-artifact-bom/releases/tag/v${version.value}"))
ThisBuild / startYear := Some(2026)
ThisBuild / licenses := Seq(("Apache-2.0", url(s"https://www.apache.org/licenses/LICENSE-2.0.txt")))
ThisBuild / developers := List(
  Developer(id = "akka",
    name = "Akka team",
    email = "info@akka.io",
    url = url("https://akka.io")))

ThisBuild / publishTo := Some("Cloudsmith" at "https://maven.cloudsmith.io/lightbend/akka/")
ThisBuild / credentials ++= (for {
  user     <- sys.env.get("PUBLISH_USER")
  password <- sys.env.get("PUBLISH_PASSWORD")
} yield Credentials("Cloudsmith", "maven.cloudsmith.io", user, password)).toSeq

lazy val root = project
  .in(file("."))
  .enablePlugins(SbtPlugin)
  .settings(
    name := "sbt-artifact-bom",
    pomIncludeRepository := (_ => false),
    pluginCrossBuild / sbtVersion := {
      scalaBinaryVersion.value match {
        case "2.12" => "1.5.8"
      }
    },
    scriptedLaunchOpts := scriptedLaunchOpts.value ++
      Seq("-Xmx1024M", "-Dplugin.version=" + version.value),
    scriptedBufferLog := false
  )
