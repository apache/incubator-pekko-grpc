package akka.grpc.build

import java.io.File
import sbt._
import sbt.Keys._
import sbtprotoc.ProtocPlugin
import ProtocPlugin.autoImport.PB
import protocbridge.Target
import sbt.ProjectRef
import sbt.file
import sbt.internal.inc.classpath.ClasspathUtil

import scala.collection.mutable.ListBuffer
import protocbridge.{ Artifact => BridgeArtifact }

/** A plugin that allows to use a code generator compiled in one subproject to be used in a test project */
object ReflectiveCodeGen extends AutoPlugin {
  val generatedLanguages = SettingKey[Seq[String]]("reflectiveGrpcGeneratedLanguages")
  val generatedSources = SettingKey[Seq[String]]("reflectiveGrpcGeneratedSources")
  val extraGenerators = SettingKey[Seq[String]]("reflectiveGrpcExtraGenerators")
  val codeGeneratorSettings = settingKey[Seq[String]]("Code generator settings")
  val protocOptions = settingKey[Seq[String]]("Protoc Options.")

  // needed to be able to override the PB.generate task reliably
  override def requires = ProtocPlugin

  override def projectSettings: Seq[Def.Setting[_]] =
    inConfig(Compile)(
      Seq(
        PB.protocOptions := protocOptions.value,
        PB.generate :=
          // almost the same as `Def.sequential` but will return the "middle" value, ie. the result of the generation
          // Defines three steps:
          //   1) dynamically load the current code generator and plug it in the mutable generator
          //   2) run the generator
          //   3) delete the generation cache because it doesn't know that the generator may change
          Def.taskDyn {
            val _ = setCodeGenerator.value
            Def.taskDyn {
              val generationResult = generateTaskFromProtocPlugin.value

              Def.task {
                // path is defined in ProtocPlugin.sourceGeneratorTask
                val file = (PB.generate / streams).value.cacheDirectory / s"protobuf_${scalaBinaryVersion.value}"
                IO.delete(file)

                generationResult
              }
            }
          }.value,
        // HACK: make the targets mutable, so we can fill them while running the above PB.generate
        PB.targets := scala.collection.mutable.ListBuffer.empty,
        // Put an artifact resolver that returns the project's classpath for our generators
        PB.artifactResolver := Def.taskDyn {
          val cp = (ProjectRef(file("."), "akka-grpc-codegen") / Compile / fullClasspath).value.map(_.data)
          val oldResolver = PB.artifactResolver.value
          Def.task { (artifact: BridgeArtifact) =>
            artifact.groupId match {
              case "com.lightbend.akka.grpc" =>
                cp
              case _ =>
                oldResolver(artifact)
            }
          }
        }.value,
        setCodeGenerator := loadAndSetGenerator(
          // the magic sauce: use the output classpath from the the sbt-plugin project and instantiate generators from there
          (ProjectRef(file("."), "sbt-akka-grpc") / Compile / fullClasspath).value,
          generatedLanguages.value,
          generatedSources.value,
          extraGenerators.value,
          sourceManaged.value,
          codeGeneratorSettings.value,
          PB.targets.value.asInstanceOf[ListBuffer[Target]],
          scalaBinaryVersion.value),
        (Compile / PB.protoSources) := PB.protoSources.value ++ Seq(
          PB.externalIncludePath.value,
          sourceDirectory.value / "proto"))) ++ Seq(
      (Global / codeGeneratorSettings) := Nil,
      (Global / generatedLanguages) := Seq("Scala"),
      (Global / generatedSources) := Seq("Client", "Server"),
      (Global / extraGenerators) := Seq.empty,
      (Global / protocOptions) := Seq.empty,
      watchSources ++= (ProjectRef(file("."), "akka-grpc-codegen") / watchSources).value,
      watchSources ++= (ProjectRef(file("."), "sbt-akka-grpc") / watchSources).value)

  val setCodeGenerator = taskKey[Unit]("grpc-set-code-generator")

  def loadAndSetGenerator(
      classpath: Classpath,
      languages0: Seq[String],
      sources0: Seq[String],
      extraGenerators0: Seq[String],
      targetPath: File,
      generatorSettings: Seq[String],
      targets: ListBuffer[Target],
      scalaBinaryVersion: String): Unit = {
    val languages = languages0.mkString(", ")
    val sources = sources0.mkString(", ")
    val extraGenerators = extraGenerators0.mkString(", ")
    val generatorSettings1 = generatorSettings.mkString("\"", "\", \"", "\"")

    val cp = classpath.map(_.data)
    // ensure to set right parent classloader, so that protocbridge.ProtocCodeGenerator etc are
    // compatible with what is already accessible from this sbt build
    val loader = ClasspathUtil.toLoader(cp, classOf[protocbridge.ProtocCodeGenerator].getClassLoader)
    import scala.reflect.runtime.universe
    import scala.tools.reflect.ToolBox

    val tb = universe.runtimeMirror(loader).mkToolBox()
    val source =
      s"""import akka.grpc.sbt.AkkaGrpcPlugin
          |import akka.grpc.sbt.GeneratorBridge
          |import AkkaGrpcPlugin.autoImport._
          |import AkkaGrpc._
          |import akka.grpc.gen.scaladsl._
          |import akka.grpc.gen.javadsl._
          |import akka.grpc.gen.CodeGenerator.ScalaBinaryVersion
          |
          |val languages: Seq[AkkaGrpc.Language] = Seq($languages)
          |val sources: Seq[AkkaGrpc.GeneratedSource] = Seq($sources)
          |val scalaBinaryVersion = ScalaBinaryVersion("$scalaBinaryVersion")
          |
          |val logger = akka.grpc.gen.StdoutLogger
          |
          |(targetPath: java.io.File, settings: Seq[String]) => {
          |  val generators =
          |    AkkaGrpcPlugin.generatorsFor(sources, languages, scalaBinaryVersion, logger) ++
          |    Seq($extraGenerators).map(gen => GeneratorBridge.sandboxedGenerator(gen, scalaBinaryVersion, akka.grpc.gen.StdoutLogger))
          |  AkkaGrpcPlugin.targetsFor(targetPath, settings, generators)
          |}
        """.stripMargin
    val generatorsF = tb.eval(tb.parse(source)).asInstanceOf[(File, Seq[String]) => Seq[Target]]
    val generators = generatorsF(targetPath, generatorSettings)

    targets.clear()
    targets ++= generators.asInstanceOf[Seq[Target]]
  }

  def generateTaskFromProtocPlugin: Def.Initialize[Task[Seq[File]]] =
    // lookup and return `PB.generate := ...` setting from ProtocPlugin
    ProtocPlugin.projectSettings
      .find(_.key.key == PB.generate.key)
      .get
      .init
      .asInstanceOf[Def.Initialize[Task[Seq[File]]]]
}
