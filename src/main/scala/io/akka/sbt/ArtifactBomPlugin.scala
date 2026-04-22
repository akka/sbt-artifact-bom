package io.akka.sbt

import sbt._
import Keys._
import scala.xml.PrettyPrinter

object ArtifactBomPlugin extends AutoPlugin {

  override def trigger = noTrigger

  object autoImport {
    val makeBom = taskKey[Unit]("Generates a Bill of Material in the form of a Maven POM file including all direct and indirect dependencies (according to sbt)")
    val makeBomTargetDir = settingKey[File]("The directory where BOM directory are stored (defaults to baseDirectory)")
    val makeBomTargetName = settingKey[String]("The name of the directory where BOM files are stored (defaults to artifact-bom)")
    val makeBomProjectVersion = settingKey[String]("Project version of the BOM (defaults to fixed string to avoid versioning trouble)")
  }

  import autoImport._

  override lazy val projectSettings: Seq[Setting[_]] = Seq(
    makeBomTargetDir := (ThisBuild / baseDirectory).value,
    makeBomTargetName := "artifact-bom",
    makeBomProjectVersion := "100.0.0",

    makeBom := Def.task {
      val report = update.value
      val log = streams.value.log
      val artName = name.value

      val outputFolder = makeBomTargetDir.value / makeBomTargetName.value / artName
      val outFile = outputFolder / "pom.xml"

      // 1. Capture every resolved module across the 'compile' and 'runtime' configs
      val allModules = report.configuration(ConfigRef("compile")).toSeq.flatMap(_.modules) ++
        report.configuration(ConfigRef("runtime")).toSeq.flatMap(_.modules)

      val uniqueDeps = allModules
        .groupBy(m => (m.module.organization, m.module.name))
        .map(_._2.head) // De-duplicate
        .toSeq
        .sortBy(_.module.name)

      // 2. Construct the XML
      val pomXml =
        <project xmlns="http://maven.apache.org/POM/4.0.0">
          <modelVersion>4.0.0</modelVersion>
          <groupId>{organization.value}</groupId>
          <artifactId>{artName}</artifactId>
          <version>{makeBomProjectVersion.value}</version>
          <dependencies>
            {uniqueDeps.map { m =>
            <dependency>
              <groupId>{m.module.organization}</groupId>
              <artifactId>{m.module.name}</artifactId>
              <version>{m.module.revision}</version>
            </dependency>
          }}
          </dependencies>
        </project>

      // 3. create folders and file
      val printer = new PrettyPrinter(120, 4)
      IO.createDirectory(outputFolder)
      IO.write(outFile, printer.format(pomXml))
      log.info(s"[$artName] Created artifact BOM at ${outFile.getAbsolutePath}")
    }.triggeredBy(Compile/compile).value
  )
}
