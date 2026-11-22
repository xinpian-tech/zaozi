// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Huang Rui <vowstar@gmail.com>
package me.jiuyang.zaozi.default

import java.nio.file.{Files, StandardCopyOption}
import scala.collection.immutable.SortedMap
import scala.collection.mutable

import me.jiuyang.zaozi.*

object Elaborate:
  /** Bytecode files in the caller's output directory, indexed by circuit name. */
  final case class Result(top: String, modules: SortedMap[String, os.Path])

  private val current         = new ThreadLocal[Session]
  private def outputDirectory = os.Path(sys.env.getOrElse("ZAOZI_OUTDIR", ""), os.pwd)

  def apply[PARAM <: Parameter, L <: LayerInterface[PARAM], I <: HWInterface[PARAM], P <: DVInterface[PARAM, L]](
    generator: Generator[PARAM, L, I, P],
    parameter: PARAM,
    directory: os.Path = outputDirectory
  ): Result =
    val top = generator.moduleName(parameter)
    run(top, directory) { file =>
      BaseGeneratorHelper.writeMlirbc(top, file) { generator.appendModule(parameter) }
    }

  private[default] def generate(name: String)(write: os.Path => Unit): Unit =
    Option(current.get()) match
      case Some(session) => session.submit(name, write)
      case None          =>
        run(name, outputDirectory)(write)
        ()

  private def run(top: String, directory: os.Path)(write: os.Path => Unit): Result =
    os.makeDir.all(directory)
    val staging  = os.temp.dir(dir = directory, prefix = ".zaozi-", deleteOnExit = false)
    val previous = current.get()
    try
      val session = new Session(directory, staging)
      current.set(session)
      session.submit(top, write)
      Result(top, SortedMap.from(session.files))
    finally
      if previous == null then current.remove() else current.set(previous)
      os.remove.all(staging)

  private final class Session(directory: os.Path, staging: os.Path):
    val files = mutable.Map.empty[String, os.Path]

    private def publish(file: os.Path, write: os.Path => Unit): Unit =
      val temporary = staging / file.last
      write(temporary)
      Files.move(temporary.toNIO, file.toNIO, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)

    def submit(name: String, write: os.Path => Unit): Unit =
      if !files.contains(name) then
        val file = directory / s"$name.mlirbc"
        files.update(name, file)
        try publish(file, write)
        catch
          case error: Throwable =>
            files.remove(name)
            throw error

end Elaborate
