// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Huang Rui <vowstar@gmail.com>
package me.jiuyang.zaozi.default

import java.nio.file.{Files, StandardCopyOption}
import java.util.concurrent.Executors
import scala.collection.immutable.SortedMap
import scala.collection.mutable
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.Duration
import scala.util.control.NonFatal

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
    val staging = os.temp.dir(dir = directory, prefix = ".zaozi-", deleteOnExit = false)
    try
      val threads = Thread.ofPlatform().name("zaozi-elaborate-", 0L).inheritInheritableThreadLocals(false)
      val pool    = Executors.newFixedThreadPool(Runtime.getRuntime.availableProcessors(), threads.factory())
      try
        given ExecutionContext = ExecutionContext.fromExecutor(pool)
        val session            = new Session(directory, staging)
        session.submit(top, write)
        session.awaitCompletion()
        Result(top, SortedMap.from(session.files))
      finally pool.shutdown()
    finally os.remove.all(staging)

  private final class Session(
    directory: os.Path,
    staging:   os.Path
  )(
    using ExecutionContext):
    val files           = mutable.Map.empty[String, os.Path]
    private val tasks   = mutable.Queue.empty[Future[Unit]]
    private var failure = Option.empty[Throwable]

    private def fail(error: Throwable): Unit = synchronized {
      if failure.isEmpty then failure = Some(error)
    }

    private def publish(file: os.Path, write: os.Path => Unit): Unit =
      val temporary = staging / file.last
      write(temporary)
      Files.move(temporary.toNIO, file.toNIO, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)

    def submit(name: String, write: os.Path => Unit): Unit = synchronized {
      if failure.isEmpty && !files.contains(name) then
        val file = directory / s"$name.mlirbc"
        files.update(name, file)
        tasks.enqueue(Future {
          try
            if synchronized(failure.isEmpty) then
              current.set(this)
              publish(file, write)
          catch case error: Throwable => fail(error)
          finally current.remove()
        })
    }

    def awaitCompletion(): Unit =
      // Workers submit all children before completing their Future. Only the caller waits.
      def next() = synchronized { if tasks.isEmpty then None else Some(tasks.dequeue()) }
      var task   = next()
      while task.nonEmpty do
        try
          Await.result(task.get, Duration.Inf)
          task = next()
        catch
          case e: InterruptedException => fail(e) // Drain the same worker before releasing staging.
          case NonFatal(e) =>
            fail(e)
            task = next()
      failure.foreach(throw _)
end Elaborate
