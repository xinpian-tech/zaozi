// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.syntheke.circt.tests

import me.jiuyang.syntheke.*
import me.jiuyang.syntheke.circt.*
import me.jiuyang.syntheke.tests.Wid
import utest.*

/** Regression: two wrappers sharing one structural key must emit instances whose port order matches the shared
  * definition. The key sorts ports by name, so instances may have been planned in different bind orders.
  */
object DedupVerilogSpec extends TestSuite:

  val outDir = os.Path(sys.env.getOrElse("ZAOZI_OUTDIR", os.pwd.toString), os.pwd)

  val leafEntry = DvVerilogSpec.entry("DupLeaf")
  val srcEntry  = DvVerilogSpec.entry("DupSrc")
  val backends: Seq[GeneratorBackend] = Seq(StubBackend(leafEntry, outDir), StubBackend(srcEntry, outDir))

  def buildTwins(): DesignSpec =
    Design {
      def clusterBody(
        using WrapperScope
      ) =
        val leaf = generator(leafEntry) {
          parameters(DvVerilogSpec.stubParams("DupLeaf"))(identity)
          val p = inward(Wid).uFn(_ => Right(64))
          val q = inward(Wid).uFn(_ => Right(64))
          (p, q)
        }
        leaf
      def cluster(
        name: String
      )(
        using WrapperScope
      ) =
        locally {
          given sourcecode.Name = sourcecode.Name(name)
          wrapper(clusterBody)
        }
      def srcBody(
        using GeneratorScope[StubFull]
      ) =
        parameters(DvVerilogSpec.stubParams("DupSrc"))(identity)
        val out = outward(Wid).dFn(_ => Right(32))
        out
      def src(
        name: String
      )(
        using WrapperScope
      ) =
        locally {
          given sourcecode.Name = sourcecode.Name(name)
          generator(srcEntry)(srcBody)
        }
      val (ap, aq) = cluster("clusterA")
      val (bp, bq) = cluster("clusterB")
      // Interleaved: clusterA's boundary sees its p edge first while clusterB sees its q edge first.
      ap <-- src("s0")
      bq <-- src("s1")
      aq <-- src("s2")
      bp <-- src("s3")
    }

  val tests = Tests {

    test("same-key wrappers planned in different bind orders share one definition and elaborate") {
      val resolved = Negotiator.negotiate(buildTwins())
      val dd       = Dedup.dedup(resolved)
      assert(dd.keyOf(ModuleId.root / "clusterA") == dd.keyOf(ModuleId.root / "clusterB"))
      assert(dd.nameOf(ModuleId.root / "clusterB") == "clusterA")
      val design   = Elaborator.elaborate(resolved, backends)
      assert(design.verilog.contains("module Top"))
      assert(design.verilog.contains("module clusterA"))
    }
  }
