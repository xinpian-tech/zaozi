// SPDX-License-Identifier: Apache-2.0
package me.jiuyang.zaozitest

import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.Interface
import me.jiuyang.zaozi.valuetpe.*
import utest.*

class ParallelIO(parameter: WidthSpecParameter) extends HWBundle(parameter):
  val i = Flipped(UInt(8))
  val o = Aligned(UInt(8))

object ParallelElaborationSpec extends TestSuite:
  private def withDirectory[A](body: os.Path => A): A =
    val directory = os.temp.dir(deleteOnExit = false)
    try body(directory)
    finally os.remove.all(directory)

  private val multipleWorkers = Runtime.getRuntime.availableProcessors() > 1

  val tests = Tests:
    test("InstanceChoice candidates elaborate concurrently and share one leaf definition"):
      val bothBranches = new CountDownLatch(2)
      val bothEnsured  = new CountDownLatch(2)
      val leaves       = new AtomicInteger()

      @generator
      object Leaf extends Generator[WidthSpecParameter, WidthSpecLayers, ParallelIO, WidthSpecProbe]:
        def architecture(parameter: WidthSpecParameter) =
          leaves.incrementAndGet()
          val io = summon[Interface[ParallelIO]]
          io.o := io.i

      @generator
      object Branch extends Generator[WidthSpecParameter, WidthSpecLayers, ParallelIO, WidthSpecProbe]:
        def architecture(parameter: WidthSpecParameter) =
          val io   = summon[Interface[ParallelIO]]
          Contract(io.i) { value =>
            bothBranches.countDown()
            if multipleWorkers then require(bothBranches.await(10, TimeUnit.SECONDS), "contracts did not overlap")
            Ensure((value === io.i).I, s"branch_${parameter.width}")
            bothEnsured.countDown()
            if multipleWorkers then require(bothEnsured.await(10, TimeUnit.SECONDS))
          }
          val leaf = Leaf.instantiate(WidthSpecParameter(8))
          leaf.io.i := io.i
          io.o      := leaf.io.o

      @generator
      object Top extends Generator[WidthSpecParameter, WidthSpecLayers, ParallelIO, WidthSpecProbe]:
        def architecture(parameter: WidthSpecParameter) =
          val io     = summon[Interface[ParallelIO]]
          val branch = InstanceChoice[WidthSpecLayers, ParallelIO, WidthSpecProbe](
            Seq(
              "First"  -> Branch.architecture(WidthSpecParameter(7)),
              "Second" -> Branch.architecture(WidthSpecParameter(8))
            )
          )
          branch.io.i := io.i
          io.o := branch.io.o

      withDirectory { directory =>
        val parameter = WidthSpecParameter(8)
        val result    = Elaborate(Top, parameter, directory)
        val names     = Set(
          Top.moduleName(parameter),
          Branch.moduleName(WidthSpecParameter(7)),
          Branch.moduleName(parameter),
          Leaf.moduleName(parameter)
        )
        assert(result.top == Top.moduleName(parameter), result.modules.keySet == names, leaves.get() == 1)
        result.modules.foreach { (name, path) =>
          assert(path == directory / s"$name.mlirbc", os.size(path) > 0)
        }
        for width <- Seq(7, 8) do
          val path    = result.modules(Branch.moduleName(WidthSpecParameter(width)))
          val ir      = os.proc("circt-opt", path).call().out.lines()
          val ensures = ir.filter(_.contains("firrtl.int.verif.ensure"))
          assert(ensures.size == 1, ensures.head.contains(s"branch_$width"))
      }

    test("a failing alternative reaches the caller after the in-flight default finishes"):
      val siblingStarted = new CountDownLatch(1)
      val childFailed    = new CountDownLatch(1)
      val siblingDone    = new CountDownLatch(1)
      val marker         = new IllegalStateException("failed child generator")
      @generator
      object Child extends Generator[WidthSpecParameter, WidthSpecLayers, ParallelIO, WidthSpecProbe]:
        def architecture(parameter: WidthSpecParameter) =
          if multipleWorkers then require(siblingStarted.await(10, TimeUnit.SECONDS))
          childFailed.countDown()
          throw marker

      @generator
      object Sibling extends Generator[WidthSpecParameter, WidthSpecLayers, ParallelIO, WidthSpecProbe]:
        def architecture(parameter: WidthSpecParameter) =
          siblingStarted.countDown()
          if multipleWorkers then require(childFailed.await(10, TimeUnit.SECONDS))
          val io = summon[Interface[ParallelIO]]
          io.o := io.i
          siblingDone.countDown()

      @generator
      object Parent extends Generator[WidthSpecParameter, WidthSpecLayers, ParallelIO, WidthSpecProbe]:
        def architecture(parameter: WidthSpecParameter) =
          val io     = summon[Interface[ParallelIO]]
          val choice = InstanceChoice[WidthSpecLayers, ParallelIO, WidthSpecProbe](
            Seq("Default" -> Sibling.architecture(parameter), "Alternative" -> Child.architecture(parameter))
          )
          choice.io.i := io.i
          io.o := choice.io.o

      withDirectory { directory =>
        val parameter = WidthSpecParameter(8)
        val oldChild  = directory / s"${Child.moduleName(parameter)}.mlirbc"
        os.write(oldChild, "previous run")
        val caught    = intercept[IllegalStateException] { Elaborate(Parent, parameter, directory) }
        assert(caught eq marker, os.read(oldChild) == "previous run", os.list(directory).forall(os.isFile(_)))
        if multipleWorkers then
          assert(siblingDone.getCount() == 0, os.size(directory / s"${Sibling.moduleName(parameter)}.mlirbc") > 0)
      }
