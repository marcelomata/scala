/* NSC -- new Scala compiler
 * Copyright 2006-2013 LAMP/EPFL
 * @author  Lex Spoon
 */

package scala.tools.nsc

import interpreter.shell.{ILoop, ShellConfig}

object JarRunner extends CommonRunner {
  def runJar(settings: GenericRunnerSettings, jarPath: String, arguments: Seq[String]): Either[Throwable, Boolean] = {
    val jar       = new io.Jar(jarPath)
    val mainClass = jar.mainClass getOrElse (throw new IllegalArgumentException(s"Cannot find main class for jar: $jarPath"))
    val jarURLs   = util.ClassPath expandManifestPath jarPath
    val urls      = if (jarURLs.isEmpty) io.File(jarPath).toURL +: settings.classpathURLs else jarURLs

    if (settings.Ylogcp) {
      Console.err.println("Running jar with these URLs as the classpath:")
      urls foreach println
    }

    runAndCatch(urls, mainClass, arguments)
  }
}

/** An object that runs Scala code.  It has three possible
 *  sources for the code to run: pre-compiled code, a script file,
 *  or interactive entry.
 */
class MainGenericRunner {
  def errorFn(str: String, e: Option[Throwable] = None, isFailure: Boolean = true): Boolean = {
    if (str.nonEmpty) Console.err println str
    e foreach (_.printStackTrace())
    !isFailure
  }

  def process(args: Array[String]): Boolean = {
    val command = new GenericRunnerCommand(args.toList, (x: String) => errorFn(x))
    import command.{settings, howToRun, thingToRun, shortUsageMsg}

    // only created for info message
    def sampleCompiler = new Global(settings)

    def run(): Boolean = {
      def isE   = settings.execute.isSetByUser
      def dashe = settings.execute.value

      // when -e expr -howtorun script, read any -i or -I files and append expr
      // the result is saved to a tmp script file and run
      def combinedCode  = {
        val files   =
          for {
            dashi <- List(settings.loadfiles, settings.pastefiles) if dashi.isSetByUser
            path  <- dashi.value
          } yield io.File(path).slurp()

        (files :+ dashe).mkString("\n\n")
      }

      import GenericRunnerCommand.{AsObject, AsScript, AsJar, Error}
      def runTarget(): Either[Throwable, Boolean] = howToRun match {
        case AsObject =>
          ObjectRunner.runAndCatch(settings.classpathURLs, thingToRun, command.arguments)
        case AsScript if isE =>
          Right(ScriptRunner.runCommand(settings, combinedCode, thingToRun +: command.arguments))
        case AsScript =>
          ScriptRunner.runScriptAndCatch(settings, thingToRun, command.arguments)
        case AsJar    =>
          JarRunner.runJar(settings, thingToRun, command.arguments)
        case Error =>
          Right(false)
        case _  =>
          // We start the repl when no arguments are given.
          // If user is agnostic about both -feature and -deprecation, turn them on.
          if (settings.deprecation.isDefault && settings.feature.isDefault) {
            settings.deprecation.value = true
            settings.feature.value = true
          }
          val config = ShellConfig(settings)
          Right(new ILoop(config).run(settings))
      }

      runTarget() match {
        case Left(ex) => errorFn("", Some(ex))  // there must be a useful message of hope to offer here
        case Right(b) => b
      }
    }

    if (!command.ok)
      errorFn(f"%n$shortUsageMsg")
    else if (command.shouldStopWithInfo)
      errorFn(command.getInfoMessage(sampleCompiler), isFailure = false)
    else
      run()
  }
}

object MainGenericRunner extends MainGenericRunner {
  def main(args: Array[String]): Unit = if (!process(args)) System.exit(1)
}
