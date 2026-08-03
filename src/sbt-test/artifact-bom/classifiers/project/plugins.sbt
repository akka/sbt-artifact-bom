addSbtPlugin("io.akka.sbt" % "sbt-artifact-bom" % sys.props.getOrElse("plugin.version", throw new RuntimeException("plugin.version not set")))
