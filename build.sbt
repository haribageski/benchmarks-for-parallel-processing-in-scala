scalaVersion := "2.12.7"

resolvers += "Sonatype OSS Snapshots" at
  "https://oss.sonatype.org/content/repositories/snapshots"

val root = project in file(".")

dependencyOverrides += "org.scala-lang" % "scala-compiler" % scalaVersion.value

libraryDependencies += "com.storm-enroute" %% "scalameter-core" % "0.19"
libraryDependencies += "com.storm-enroute" %% "scalameter" % "0.19"
libraryDependencies += "io.monix" %% "monix" % "3.0.0"
libraryDependencies += "io.monix" %% "monix-reactive" % "3.0.0"

testFrameworks += new TestFramework(
  "org.scalameter.ScalaMeterFramework")

logBuffered := false
parallelExecution in Test := false

fork := true

outputStrategy := Some(StdoutOutput)

connectInput := true