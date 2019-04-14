package com.typelead

import sbt.Keys._
import sbt._

final case class Cabal(projectName: String,
                       projectVersion: String,
                       projectLibrary: Option[Cabal.Library],
                       executables: Seq[Cabal.Executable],
                       testSuites: Seq[Cabal.TestSuite]) {

  val cabalName: String = projectName + ".cabal"
  val packageId: String = projectName + "-" + projectVersion

  def artifacts: Seq[Cabal.Artifact[_]] = projectLibrary.toList ++ executables ++ testSuites
  def getArtifacts(filter: Cabal.Artifact.Filter): Seq[Cabal.Artifact[_]] = artifacts.filter(filter).sortBy {
    case _: Cabal.Library    => 0
    case _: Cabal.Executable => 1
    case _: Cabal.TestSuite  => 2
  }
  def getArtifactsJars(dist: File, etaVersion: String, filter: Cabal.Artifact.Filter): Classpath = {
    val buildPath = dist / "build" / ("eta-" + etaVersion) / packageId
    getArtifacts(filter).map {
      case _: Cabal.Library =>
        buildPath / "build" / (packageId + "-inplace.jar")
      case a: Cabal.Executable =>
        buildPath / "x" / a.name / "build" / a.name / (a.name + ".jar")
      case a: Cabal.TestSuite =>
        buildPath / "t" / a.name / "build" / a.name / (a.name + ".jar")
    }.flatMap(jar => PathFinder(jar).classpath)
  }

  def getMainClass: Option[String] = {
    if (hasExecutable) Some("eta.main")
    else None
  }

  def hasLibrary   : Boolean = projectLibrary.nonEmpty
  def hasExecutable: Boolean = executables.nonEmpty
  def hasTestSuite : Boolean = testSuites.nonEmpty

  def resolveNames: Cabal = this.copy(
    projectLibrary = projectLibrary.map {
      case a if a.name == Cabal.NONAME => a.copy(name = projectName)
      case other                       => other
    },
    executables = executables.map {
      case a if a.name == Cabal.NONAME => a.copy(name = projectName)
      case other                       => other
    },
    testSuites = testSuites.map {
      case a if a.name == Cabal.NONAME => a.copy(name = projectName)
      case other                       => other
    }
  )

  def isEmpty: Boolean = projectName == Cabal.NONAME || projectVersion == Cabal.NOVERSION || artifacts.isEmpty

}

object Cabal {

  val NONAME = "<--noname-->"
  val NOVERSION = "<--noversion-->"

  val empty: Cabal = Cabal(
    projectName = NONAME,
    projectVersion = NOVERSION,
    projectLibrary = None,
    executables = Nil,
    testSuites = Nil
  )

  val Haskell98   = "Haskell98"
  val Haskell2010 = "Haskell2010"

  sealed trait Artifact[A <: Artifact[A]] {
    def name: String
    def depsPackage: String
    def sourceDirectories: Seq[String]
    def exposedModules: Seq[String]
    def buildDependencies: Seq[String]
    def mavenDependencies: Seq[String]
    def hsMain: Option[String]
    def ghcOptions: Seq[String]
    def defaultLanguage: String

    def addSourceDirectories(dirs: Seq[String]): A
    def addLibrary(artifact: Option[Library]): A
  }

  final case class Library(override val name: String,
                           override val sourceDirectories: Seq[String],
                           override val exposedModules: Seq[String],
                           override val buildDependencies: Seq[String],
                           override val mavenDependencies: Seq[String],
                           override val ghcOptions: Seq[String],
                           override val defaultLanguage: String) extends Artifact[Library] {

    override def depsPackage: String = "lib:" + name
    override def hsMain: Option[String] = None


    override def addSourceDirectories(dirs: Seq[String]): Library = this.copy(sourceDirectories = dirs)
    override def addLibrary(artifact: Option[Library]): Library = this

  }

  final case class Executable(override val name: String,
                              override val sourceDirectories: Seq[String],
                              override val exposedModules: Seq[String],
                              override val buildDependencies: Seq[String],
                              override val mavenDependencies: Seq[String],
                              override val hsMain: Option[String],
                              override val ghcOptions: Seq[String],
                              override val defaultLanguage: String) extends Artifact[Executable] {

    override def depsPackage: String = "exe:" + name

    override def addSourceDirectories(dirs: Seq[String]): Executable = this.copy(sourceDirectories = dirs)
    override def addLibrary(artifact: Option[Library]): Executable = this.copy(buildDependencies = artifact.map(_.name).toList ++ buildDependencies)

  }

  final case class TestSuite(override val name: String,
                             override val sourceDirectories: Seq[String],
                             override val exposedModules: Seq[String],
                             override val buildDependencies: Seq[String],
                             override val mavenDependencies: Seq[String],
                             override val hsMain: Option[String],
                             override val ghcOptions: Seq[String],
                             override val defaultLanguage: String) extends Artifact[TestSuite] {

    override def depsPackage: String = "test:" + name

    override def addSourceDirectories(dirs: Seq[String]): TestSuite = this.copy(sourceDirectories = dirs)
    override def addLibrary(artifact: Option[Library]): TestSuite = this.copy(buildDependencies = artifact.map(_.name).toList ++ buildDependencies)

  }

  object Artifact {

    type Filter = Artifact[_] => Boolean

    def lib(name: String): Library = Library(name, Nil, Nil, Nil, Nil, Nil, Haskell2010)
    def exe(name: String): Executable = Executable(name, Nil, Nil, Nil, Nil, Some("Main.hs"), Nil, Haskell2010)
    def test(name: String): TestSuite = TestSuite (name, Nil, Nil, Nil, Nil, Some("Spec.hs"), Nil, Haskell2010)

    val all: Filter = _ => true
    val library: Filter = {
      case _: Library => true
      case _ => false
    }
    val executable: Filter = {
      case _: Executable => true
      case _ => false
    }
    val testSuite: Filter = {
      case _: TestSuite => true
      case _ => false
    }

    def not(filter: Filter): Filter = a => !filter(a)
    def and(f1: Filter, f2: Filter): Filter = a => f1(a) && f2(a)
    def or (f1: Filter, f2: Filter): Filter = a => f1(a) || f2(a)

  }

  def parseCabal(cwd: File, log: Logger): Cabal = {
    getCabalFile(cwd) match {
      case Some(file) =>

        log.info(s"Found '$file' in '${cwd.getCanonicalFile}'.")

        val NamePattern = """\s*name:\s*(\S+)\s*$""".r
        val VersionPattern = """\s*version:\s*(\S+)\s*$""".r
        val LibraryPattern = """\s*library(\s*)$""".r
        val ExecutableWithName = """\s*executable\s*(\S+)\s*$""".r
        val ExecutableWithoutName = """\s*executable(\s*)$""".r
        val TestSuiteWithName = """\s*test-suite\s*(\S+)\s*$""".r
        val TestSuiteWithoutName = """\s*test-suite(\s*)$""".r

        val cabal = IO.readLines(cwd / file).foldLeft(empty) {
          case (info, NamePattern(projName))    => info.copy(projectName = projName)
          case (info, VersionPattern(projVer))  => info.copy(projectVersion = projVer)
          case (info, LibraryPattern(_))        => info.copy(projectLibrary = Some(Artifact.lib(NONAME)))
          case (info, ExecutableWithName(exe))  => info.copy(executables = info.executables :+ Artifact.exe(exe))
          case (info, ExecutableWithoutName(_)) => info.copy(executables = info.executables :+ Artifact.exe(NONAME))
          case (info, TestSuiteWithName(suite)) => info.copy(testSuites = info.testSuites :+ Artifact.test(suite))
          case (info, TestSuiteWithoutName(_))  => info.copy(testSuites = info.testSuites :+ Artifact.test(NONAME))
          case (info, _)                        => info
        }.resolveNames

        if (cabal.projectName == NONAME) {
          log.error("No project name specified.")
          empty
        } else if (cabal.projectVersion == NOVERSION) {
          log.error("No project version specified.")
          empty
        } else {
          log.info(cabal.toString)
          cabal
        }

      case None =>
        log.error(s"No cabal file found in '${cwd.getCanonicalFile}'.")
        empty
    }
  }

  def writeArtifact(artifact: Artifact[_]): Seq[String] = {
    artifact.sourceDirectories  .mkString("  hs-source-dirs:   ", "\n                  , ", "").split("\n") ++
      artifact.exposedModules   .mkString("  exposed-modules:  ", "\n                  , ", "").split("\n") ++
      artifact.buildDependencies.mkString("  build-depends:    ", "\n                  , ", "").split("\n") ++
      artifact.mavenDependencies.mkString("  maven-depends:    ", "\n                  , ", "").split("\n") ++
      artifact.hsMain           .map(m => "  main-is:          " + m).toList ++
      Seq(
                                          "  ghc-options:      " + artifact.ghcOptions.mkString(" "),
                                          "  default-language: " + artifact.defaultLanguage
      )
  }

  def writeCabal(cwd: File, cabal: Cabal): Unit = {
    if (cabal.isEmpty) {
      sys.error("The Eta project is not properly configured.")
    } else {
      val headers = Seq(
        "name:          " + cabal.projectName,
        "version:       " + cabal.projectVersion,
        "cabal-version: >= 1.10",
        "build-type:    Simple",
      )
      val libraryDefs = cabal.projectLibrary.map { artifact =>
        Seq(
          "",
          "library"
        ) ++ writeArtifact(artifact)
      }.getOrElse(Nil)
      val executableDefs = cabal.executables.flatMap { artifact =>
        Seq(
          "",
          "executable " + artifact.name
        ) ++ writeArtifact(artifact.addLibrary(cabal.projectLibrary))
      }
      val testSuiteDefs = cabal.testSuites.flatMap { artifact =>
        Seq(
          "",
          "test-suite " + artifact.name
        ) ++ writeArtifact(artifact.addLibrary(cabal.projectLibrary))
      }
      IO.writeLines(cwd / cabal.cabalName, headers ++ libraryDefs ++ executableDefs ++ testSuiteDefs)
    }
  }

  def getCabalFile(cwd: File): Option[String] = {
    cwd.listFiles.map(_.getName).find(_.matches(""".*\.cabal$"""))
  }

}