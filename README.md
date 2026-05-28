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

A BOM must be published as the **primary pom artifact of its own dedicated module**. Maven forbids combining a `<classifier>` with `<scope>import</scope>`, so a BOM cannot be attached as a classified artifact to a module that also ships a jar — give it its own module instead.

Create a module whose sole purpose is to publish the BOM (e.g. `acme-dependencies`) and apply `bomPublishSettings`. The BOM becomes the module's main published pom; no jar, sources, or docs are produced:

```scala
lazy val dependencies = (project in file("dependencies"))
  .enablePlugins(ArtifactBomPlugin)
  .settings(bomPublishSettings)
  .settings(
    name := "acme-dependencies",
    // depend on everything the BOM should pin
    libraryDependencies ++= Seq(/* ... */)
  )
```

This is published with the standard `sbt publish` / `publishLocal`, and consumed via the `import`-scope snippet shown above. The published pom uses the real project `version` (not the on-disk `makeBomProjectVersion` placeholder).

`bomPublishSettings` also enables `makeBomIncludeInternalDependencies`, so internal modules in the same build that the BOM module depends on (e.g. a sibling SPI module) are pinned in the BOM at the release version. They are excluded from the committed on-disk `makeBom` file by default, because their versions change every release and would churn it.

## Settings

The plugin provides the following settings:

| Setting | Description | Default Value |
|---------|-------------|---------------|
| `makeBomTargetDir` | The base directory where the BOM directory is stored. | `(ThisBuild / baseDirectory).value` |
| `makeBomTargetName` | The name of the directory where BOM files are stored. | `"artifact-bom"` |
| `makeBomProjectVersion`| The version string used in the generated `pom.xml`. | `"100.0.0"` |
| `makeBomScalaVersion` | If `Some(v)`, `makeBom` only runs when `scalaVersion` matches `v`. Avoids the BOM contents flipping between Scala versions in a cross-built project. Must be set at project scope (e.g. `myProject / makeBomScalaVersion := ...`); a `ThisBuild` override will be shadowed by the project-level default. | `crossScalaVersions.value.headOption` (i.e. the project's primary Scala version) |
| `makeBomOnCompile` | If `false`, suppresses the automatic `makeBom` trigger after `compile`. Useful for release flows (e.g. with `sbt-dynver`) where the BOM file changing in the working copy mid-release would be disruptive. `makeBom` can still be invoked explicitly. | `true` |
| `makeBomIncludeDependencies` | If `true`, the generated pom also populates a top-level `<dependencies>` section (in addition to `<dependencyManagement>`), for backwards compatibility with consumers that expected the old dependencies-only output. | `false` |
| `makeBomIncludeInternalDependencies` | If `true`, internal/sibling modules (other projects in the same sbt build) that this project depends on are included in the BOM. `bomPublishSettings` sets this to `true`. | `false` (`true` under `bomPublishSettings`) |

The plugin also provides:

- `bomPublishSettings` — a settings sequence that turns the module into a dedicated BOM publisher (see Publishing).

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

The plugin inspects the `update` report for the `compile` and `runtime` configurations. It collects all unique modules (de-duplicating by organization and name) and formats them into a `<dependencyManagement>` block within a `pom`-packaged `pom.xml`. By default, sibling modules in the same build are excluded so the committed on-disk BOM stays stable across releases; set `makeBomIncludeInternalDependencies := true` (as `bomPublishSettings` does) to pin them too.

## License

This project is licensed under the Apache License, Version 2.0. See the [LICENSE](LICENSE) file for details.

---
© 2026 Akka Team
