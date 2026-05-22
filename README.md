# sbt-artifact-bom

`sbt-artifact-bom` is an sbt plugin designed to generate a Maven Bill of Materials (BOM) in the form of a `pom.xml` file. It captures all direct and indirect dependencies of your project (as resolved by sbt) and flattens them into a single list of dependencies.

## Features

- **Flattened Dependency Tree**: Automatically includes both direct and transitive dependencies.
- **True Maven BOM**: Generates a `pom`-packaged artifact with a `<dependencyManagement>` section that downstream projects can import.
- **Publishable**: Optionally publishes the BOM, either attached to a jar-producing module or as a dedicated BOM-only module.
- **Configurable**: Allows customization of the output directory, folder name, and BOM project version.
- **Automatic Execution**: Triggered automatically by the `compile` task.

## Installation

Add the following to your `project/plugins.sbt`:

```scala
addSbtPlugin("io.akka.sbt" % "sbt-artifact-bom" % "1.0.0-SNAPSHOT")
```

## Usage

Enable the plugin in your `build.sbt`:

```scala
lazy val myProject = (project in file("."))
  .enablePlugins(ArtifactBomPlugin)
```

The `makeBom` task is automatically triggered when you run `compile`. You can also run it manually:

```bash
sbt makeBom
```

The resulting BOM will be generated at:
`[baseDirectory]/artifact-bom/[projectName]/pom.xml`

This on-disk file uses the fixed `makeBomProjectVersion` (`100.0.0` by default) to keep it stable across releases. It is not published; publication (see below) always uses the real project `version`.

## Publishing the BOM

The generated BOM is a true Maven BOM — a `pom`-packaged artifact whose `<dependencyManagement>` section pins every resolved dependency. Downstream projects import it with `import` scope:

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>com.example</groupId>
      <artifactId>example-dependencies</artifactId>
      <version>1.2.3</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>
```

There are two ways to publish, depending on whether the module also ships a jar.

### 1. Attach the BOM to a jar-producing module

For a normal module that publishes a jar, set `makeBomPublish := true`. The BOM is published as an **additional** classified `pom` artifact (`<artifactId>-<version>-bom.pom`); the module's jar, sources, docs, and main pom are published unchanged.

```scala
lazy val myLib = (project in file("."))
  .enablePlugins(ArtifactBomPlugin)
  .settings(
    makeBomPublish := true // off by default, so enabling the plugin never changes what you publish
  )
```

Downstream consumers reference it with the `bom` classifier:

```xml
<dependency>
  <groupId>com.example</groupId>
  <artifactId>my-lib</artifactId>
  <version>1.2.3</version>
  <type>pom</type>
  <classifier>bom</classifier>
  <scope>import</scope>
</dependency>
```

### 2. Dedicated BOM-only module

For a module whose sole purpose is to publish a BOM (e.g. `acme-dependencies`), apply `bomOnlySettings`. The BOM becomes the module's **main** published pom and no jar/sources/docs are produced:

```scala
lazy val dependencies = (project in file("dependencies"))
  .enablePlugins(ArtifactBomPlugin)
  .settings(bomOnlySettings)
  .settings(
    name := "acme-dependencies",
    // depend on everything the BOM should pin
    libraryDependencies ++= Seq(/* ... */)
  )
```

This is published with the standard `sbt publish` / `publishLocal`, and consumed via the plain `import`-scope snippet shown above (no classifier).

## Settings

The plugin provides the following settings:

| Setting | Description | Default Value |
|---------|-------------|---------------|
| `makeBomTargetDir` | The base directory where the BOM directory is stored. | `(ThisBuild / baseDirectory).value` |
| `makeBomTargetName` | The name of the directory where BOM files are stored. | `"artifact-bom"` |
| `makeBomProjectVersion`| The version string used in the generated `pom.xml`. | `"100.0.0"` |
| `makeBomScalaVersion` | If `Some(v)`, `makeBom` only runs when `scalaVersion` matches `v`. Avoids the BOM contents flipping between Scala versions in a cross-built project. Must be set at project scope (e.g. `myProject / makeBomScalaVersion := ...`); a `ThisBuild` override will be shadowed by the project-level default. | `crossScalaVersions.value.headOption` (i.e. the project's primary Scala version) |
| `makeBomOnCompile` | If `false`, suppresses the automatic `makeBom` trigger after `compile`. Useful for release flows (e.g. with `sbt-dynver`) where the BOM file changing in the working copy mid-release would be disruptive. `makeBom` can still be invoked explicitly. | `true` |
| `makeBomPublish` | If `true`, attach the BOM as an additional classified `pom` artifact alongside a jar-producing module's normal artifacts (see Publishing). | `false` |
| `makeBomClassifier` | Classifier used for the attached BOM artifact when `makeBomPublish := true`. | `"bom"` |
| `makeBomIncludeDependencies` | If `true`, the generated pom also populates a top-level `<dependencies>` section (in addition to `<dependencyManagement>`), for backwards compatibility with consumers that expected the old dependencies-only output. | `false` |

The plugin also provides:

- `makeBomArtifact` — a task that generates the publishable BOM `pom` file (using the real project `version`).
- `bomOnlySettings` — a settings sequence for a dedicated BOM-only module (see Publishing).

## Disabling the compile trigger for releases

When releasing with `sbt-dynver`, having the BOM rewritten on `compile` can make the working tree look dirty and cause dynver to append a `+<sha>-<timestamp>` suffix to the version. Wire `makeBomOnCompile` to a system property so a release build can opt out:

```scala
ThisBuild / makeBomOnCompile := !sys.props.get("release").contains("true")
```

Then pass `-Drelease=true` from your release workflow, e.g.:

```yaml
- name: Release
  run: sbt -Drelease=true "+publishSigned" sonatypeBundleRelease
```

Regular CI builds keep the default (BOM regenerated on compile); the release job leaves the working copy untouched.

## How it works

The plugin inspects the `update` report for the `compile` and `runtime` configurations. It collects all unique modules (de-duplicating by organization and name) and formats them into a `<dependencyManagement>` block within a `pom`-packaged `pom.xml`. Sibling modules in the same build are excluded so the BOM stays stable across releases.

## License

This project is licensed under the Apache License, Version 2.0. See the [LICENSE](LICENSE) file for details.

---
© 2026 Akka Team
