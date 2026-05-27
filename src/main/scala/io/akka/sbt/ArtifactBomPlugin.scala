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
    val makeBomScalaVersion = settingKey[Option[String]]("If set, makeBom only runs when scalaVersion matches this value. Useful for cross-built projects to avoid the BOM contents flipping between cross-build passes (defaults to the head of crossScalaVersions, i.e. the project's primary Scala version)")
  }

  import autoImport._

  override lazy val projectSettings: Seq[Setting[_]] = Seq(
    makeBomTargetDir := (ThisBuild / baseDirectory).value,
    makeBomTargetName := "artifact-bom",
    makeBomProjectVersion := "100.0.0",
    makeBomScalaVersion := crossScalaVersions.value.headOption,

    makeBom := Def.task {
      val s = streams.value
      val log = s.log
      val artName = name.value
      val pinnedScala = makeBomScalaVersion.value
      val currentScala = scalaVersion.value
      val report = update.value
      val org = organization.value
      val bomVersion = makeBomProjectVersion.value
      val sbv = scalaBinaryVersion.value
      val allProjectIDs = projectID.all(ScopeFilter(inAnyProject)).value
      val targetDir = makeBomTargetDir.value
      val targetName = makeBomTargetName.value
      if (pinnedScala.exists(_ != currentScala)) {
        log.debug(s"[$artName] Skipping artifact BOM generation, scalaVersion $currentScala does not match pinned ${pinnedScala.get}")
      } else {
        // Sibling projects in the same build get a new version on every release; excluding them
        // keeps the BOM stable across releases. projectID is un-cross-versioned, so apply the
        // crossVersion mapping to match the resolved module name (e.g. lib -> lib_2.13).
        val siblingKeys = allProjectIDs.map { id =>
          val crossed = CrossVersion(id.crossVersion, currentScala, sbv).fold(id.name)(_(id.name))
          (id.organization, crossed)
        }.toSet

        val outputFolder = targetDir / targetName / artName
        val outFile = outputFolder / "pom.xml"

        // Capture every resolved module across the 'compile' and 'runtime' configs
        val allModules = report.configuration(ConfigRef("compile")).toSeq.flatMap(_.modules) ++
          report.configuration(ConfigRef("runtime")).toSeq.flatMap(_.modules)

        val uniqueDeps = allModules
          .groupBy(m => (m.module.organization, m.module.name))
          .map(_._2.head) // De-duplicate
          .toSeq
          .filterNot(m => siblingKeys.contains((m.module.organization, m.module.name)))
          .sortBy(m => (m.module.organization, m.module.name))

        // Cache key covers everything that influences the generated file
        val cacheKey =
          (org +: artName +: bomVersion +: outFile.getAbsolutePath +:
            uniqueDeps.map(m => s"${m.module.organization}:${m.module.name}:${m.module.revision}")
          ).mkString("\n")
        val cacheFile = s.cacheDirectory / "makeBom.cachekey"
        val previousKey = if (cacheFile.exists()) Some(IO.read(cacheFile)) else None

        if (previousKey.contains(cacheKey) && outFile.exists()) {
          log.debug(s"[$artName] Artifact BOM up to date at ${outFile.getAbsolutePath}")
        } else {
          val pomXml =
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>{org}</groupId>
              <artifactId>{artName}</artifactId>
              <version>{bomVersion}</version>
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

          val printer = new PrettyPrinter(120, 4)
          val newContent = printer.format(pomXml)
          IO.createDirectory(outputFolder)

          // Skip the write (preserving mtime) when an existing file already has identical content,
          // e.g. after the cache was cleared but the BOM itself is still up to date
          val existingContent = if (outFile.exists()) Some(IO.read(outFile)) else None
          if (existingContent.contains(newContent)) {
            log.debug(s"[$artName] Artifact BOM content unchanged at ${outFile.getAbsolutePath}")
          } else {
            IO.write(outFile, newContent)
            log.info(s"[$artName] Created artifact BOM at ${outFile.getAbsolutePath}")
          }
          IO.write(cacheFile, cacheKey)
        }
      }
    }.triggeredBy(Compile/compile).value
  )
}
