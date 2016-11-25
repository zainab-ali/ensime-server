import java.io._
import scala.util.{ Properties, Try }

import com.typesafe.sbt.SbtScalariform._
import de.heikoseeberger.sbtheader.{ HeaderKey, HeaderPlugin }
import sbt.Keys._
import sbt.{ IntegrationTest => It, _ }
import sbtassembly.AssemblyKeys._
import sbtassembly.{ AssemblyKeys, MergeStrategy, PathList }
import sbtbuildinfo.BuildInfoPlugin, BuildInfoPlugin.autoImport._
import SonatypeSupport._

import org.ensime.EnsimePlugin.JdkDir
import org.ensime.EnsimeKeys._


object ProjectPlugin extends AutoPlugin {
  override def requires = plugins.JvmPlugin
  override def trigger = allRequirements

  override def buildSettings = Seq(
    scalaVersion := "2.11.8",
    organization := "org.ensime",
    version := "2.0.0-graph-SNAPSHOT"
  )
}

object EnsimeBuild {
  lazy val commonSettings = Sensible.settings ++ Seq(
    libraryDependencies ++= Sensible.testLibs().value ++ Sensible.logback,

    dependencyOverrides ++= Set(
       "com.typesafe.akka" %% "akka-actor" % Sensible.akkaVersion.value,
       "com.typesafe.akka" %% "akka-testkit" % Sensible.akkaVersion.value,
       "io.spray" %% "spray-json" % "1.3.2"
    ),

      // disabling shared memory gives a small performance boost to tests
    javaOptions ++= Seq("-XX:+PerfDisableSharedMem"),

    javaOptions in Test ++= Seq(
      "-Dlogback.configurationFile=../logback-test.xml"
    ),

    dependencyOverrides ++= Set(
      "org.apache.lucene" % "lucene-core" % luceneVersion
    ),

    HeaderKey.headers := Copyright.GplMap,

    updateOptions := updateOptions.value.withCachedResolution(true)
  ) ++ sonatype("ensime", "ensime-server", GPL3)

  lazy val commonItSettings = inConfig(It)(
    Defaults.testSettings ++ Sensible.testSettings
  ) ++ scalariformSettingsWithIt ++ HeaderPlugin.settingsFor(It) ++ Seq(
      javaOptions in It ++= Seq(
        "-Dlogback.configurationFile=../logback-it.xml"
      )
    )

  lazy val JavaTools: File = JdkDir / "lib/tools.jar"

  ////////////////////////////////////////////////
  // modules
  lazy val monkeys = Project("monkeys", file("monkeys")) settings (commonSettings) settings (
    libraryDependencies ++= Seq(
      "org.scala-lang" % "scala-compiler" % scalaVersion.value,
      "org.apache.commons" % "commons-vfs2" % "2.1" exclude ("commons-logging", "commons-logging")
    )
  )

  lazy val api = Project("api", file("api")) settings (commonSettings) settings (
    licenses := Seq(Apache2),
    HeaderKey.headers := Copyright.ApacheMap
  )

  lazy val util = Project("util", file("util")) settings (commonSettings) dependsOn (
    api
  ) settings (
    libraryDependencies ++= List(
      "com.typesafe.akka" %% "akka-actor" % Sensible.akkaVersion.value,
      "org.scala-lang" % "scala-compiler" % scalaVersion.value,
      "org.apache.commons" % "commons-vfs2" % "2.1" exclude ("commons-logging", "commons-logging")
    ) ++ Sensible.guava
  )

  lazy val testutil = Project("testutil", file("testutil")) settings (commonSettings) dependsOn (
    util, api
  ) settings (
      libraryDependencies += "commons-io" % "commons-io" % "2.5",
      libraryDependencies ++= Sensible.testLibs("compile").value
    )

  lazy val s_express = Project("s-express", file("s-express")) settings (commonSettings) settings (
      HeaderKey.headers := Copyright.LgplMap,
      libraryDependencies ++= Seq(
        "org.parboiled" %% "parboiled" % "2.1.3"
      ) ++ Sensible.shapeless(scalaVersion.value)
    )

  // the JSON protocol
  lazy val jerky = Project("jerky", file("protocol-jerky")) settings (commonSettings) dependsOn (
    util,
    api,
    testutil % "test",
    api % "test->test" // for the test data
  ) settings (
      libraryDependencies ++= Seq(
        "com.github.fommil" %% "spray-json-shapeless" % "1.3.0",
        "com.typesafe.akka" %% "akka-slf4j" % Sensible.akkaVersion.value
      ) ++ Sensible.shapeless(scalaVersion.value)
    )

  // the S-Exp protocol
  lazy val swanky = Project("swanky", file("protocol-swanky")) settings (commonSettings) dependsOn (
    api, s_express, util,
    testutil % "test",
    api % "test->test" // for the test data
  ) settings (
      libraryDependencies ++= Seq(
        "com.typesafe.akka" %% "akka-slf4j" % Sensible.akkaVersion.value
      ) ++ Sensible.shapeless(scalaVersion.value)
    )

  lazy val core = Project("core", file("core")).dependsOn(
    api, s_express, monkeys, util,
    api % "test->test", // for the interpolator
    testutil % "test,it",
    // depend on "it" dependencies in "test" or sbt adds them to the release deps!
    // https://github.com/sbt/sbt/issues/1888
    testingEmpty % "test,it",
    testingSimple % "test,it",
    // test config needed to get the test jar
    testingSimpleJar % "test,it->test",
    testingTiming % "test,it",
    testingMacros % "test, it",
    testingShapeless % "test,it",
    testingDebug % "test,it",
    testingJava % "test,it"
  ).configs(It).settings(
      commonSettings, commonItSettings
    ).settings(
      unmanagedJars in Compile += JavaTools,
      ensimeUnmanagedSourceArchives += (baseDirectory in ThisBuild).value / "openjdk-langtools/openjdk8-langtools-src.zip",
      libraryDependencies ++= Seq(
        "com.orientechnologies" % "orientdb-graphdb" % Sensible.orientVersion
          exclude ("commons-collections", "commons-collections")
          exclude ("commons-beanutils", "commons-beanutils")
          exclude ("commons-logging", "commons-logging"),
        "org.apache.lucene" % "lucene-core" % luceneVersion,
        "org.apache.lucene" % "lucene-analyzers-common" % luceneVersion,
        "org.ow2.asm" % "asm-commons" % "5.1",
        "org.ow2.asm" % "asm-util" % "5.1",
        "org.scala-lang" % "scalap" % scalaVersion.value,
        "com.typesafe.akka" %% "akka-actor" % Sensible.akkaVersion.value,
        "com.typesafe.akka" %% "akka-slf4j" % Sensible.akkaVersion.value,
        scalaBinaryVersion.value match {
          // see notes in https://github.com/ensime/ensime-server/pull/1446
          case "2.10" => "org.scala-refactoring" % "org.scala-refactoring.library_2.10.6" % "0.10.0"
          case "2.11" => "org.scala-refactoring" % "org.scala-refactoring.library_2.11.8" % "0.10.0"
        },
        "commons-lang" % "commons-lang" % "2.6",
        "com.googlecode.java-diff-utils" % "diffutils" % "1.3.0",
        "org.scala-debugger" %% "scala-debugger-api" % "1.1.0-M2"
      ) ++ Sensible.testLibs("it,test").value ++ Sensible.shapeless(scalaVersion.value)
    ) enablePlugins BuildInfoPlugin settings (
        buildInfoPackage := organization.value,
        buildInfoKeys += BuildInfoKey.action("gitSha")(Try("git rev-parse --verify HEAD".!! dropRight 1) getOrElse "n/a"),
        buildInfoKeys += BuildInfoKey.action("builtAtString")(currentDateString())
      )

  private def currentDateString() = {
    val dtf = new java.text.SimpleDateFormat("yyyy-MM-dd")
    dtf.setTimeZone(java.util.TimeZone.getTimeZone("UTC"))
    dtf.format(new java.util.Date())
  }

  val luceneVersion = "6.2.1"
  val nettyVersion = "4.1.6.Final"
  lazy val server = Project("server", file("server")).dependsOn(
    core, swanky, jerky,
    s_express % "test->test",
    swanky % "test->test",
    // depend on "it" dependencies in "test" or sbt adds them to the release deps!
    // https://github.com/sbt/sbt/issues/1888
    core % "test->test",
    core % "it->it",
    testingDocs % "test,it"
  ).configs(It).settings(
      commonSettings ++ commonItSettings
    ).settings(
        libraryDependencies ++= Seq(
          "io.netty"    %  "netty-transport"  % nettyVersion,
          "io.netty"    %  "netty-handler"    % nettyVersion,
          "io.netty"    %  "netty-codec-http" % nettyVersion
        ) ++ Sensible.testLibs("it,test").value ++ Sensible.shapeless(scalaVersion.value)
      )

  // testing modules
  lazy val testingEmpty = Project("testingEmpty", file("testing/empty"))

  lazy val testingSimple = Project("testingSimple", file("testing/simple")) settings (
    scalacOptions in Compile := Seq(),
    libraryDependencies += "org.scalatest" %% "scalatest" % Sensible.scalatestVersion % Test intransitive ()
  )

  lazy val testingSimpleJar = Project("testingSimpleJar", file("testing/simpleJar")).settings(
    exportJars := true,
    ensimeUseTarget in Compile := Some((artifactPath in (Compile, packageBin)).value),
    ensimeUseTarget in Test := Some((artifactPath in (Test, packageBin)).value)
  )

  lazy val testingImplicits = Project("testingImplicits", file("testing/implicits")) settings (
    libraryDependencies += "org.scalatest" %% "scalatest" % Sensible.scalatestVersion % "test" intransitive ()
  )

  lazy val testingTiming = Project("testingTiming", file("testing/timing"))

  lazy val testingMacros = Project("testingMacros", file("testing/macros")) settings (
    libraryDependencies += "org.scala-lang" % "scala-reflect" % scalaVersion.value
  )

  // just to have access to shapeless
  lazy val testingShapeless = Project("testingShapeless", file("testing/shapeless")).settings (
    libraryDependencies ++= Sensible.shapeless(scalaVersion.value)
  )

  lazy val testingFqns = Project("testingFqns", file("testing/fqns")).settings (
    libraryDependencies ++= Sensible.shapeless(scalaVersion.value) ++ Seq(
      "org.typelevel" %% "cats-core" % "0.6.1" intransitive(),
      "org.spire-math" %% "spire" % "0.11.0" intransitive()
    )
  )

  lazy val testingDebug = Project("testingDebug", file("testing/debug")).settings(
    scalacOptions in Compile := Seq()
  )

  lazy val testingDocs = Project("testingDocs", file("testing/docs")).settings(
    dependencyOverrides ++= Set("com.google.guava" % "guava" % "18.0"),
    libraryDependencies ++= Seq(
      "com.github.dvdme" % "ForecastIOLib" % "1.5.1" intransitive (),
      "commons-io" % "commons-io" % "2.5" intransitive ()
    ) ++ Sensible.guava
  )

  // java project with no scala-library
  lazy val testingJava = Project("testingJava", file("testing/java")).settings(
    crossPaths := false,
    autoScalaLibrary := false
  )

  // manual root project so we can exclude the testing projects from publication
  lazy val root = Project(id = "ensime", base = file(".")) settings (commonSettings) aggregate (
    api, monkeys, util, s_express, jerky, swanky, core, server
  ) dependsOn (server) settings (
      // e.g. `sbt ++2.11.8 ensime/assembly`
      test in assembly := {},
      aggregate in assembly := false,
      assemblyMergeStrategy in assembly := {
        case PathList("org", "apache", "commons", "vfs2", xs @ _*) => MergeStrategy.first // assumes our classpath is setup correctly
        case PathList("META-INF", "io.netty.versions.properties") => MergeStrategy.concat // assumes our classpath is setup correctly
        case other => MergeStrategy.defaultMergeStrategy(other)
      },
      assemblyExcludedJars in assembly := {
        val everything = (fullClasspath in assembly).value
        everything.filter { attr =>
          val n = attr.data.getName
          n.startsWith("scala-library") | n.startsWith("scala-compiler") |
            n.startsWith("scala-reflect") | n.startsWith("scalap")
        } :+ Attributed.blank(JavaTools)
      },
      assemblyJarName in assembly := s"ensime_${scalaBinaryVersion.value}-${version.value}-assembly.jar"
    )
}
