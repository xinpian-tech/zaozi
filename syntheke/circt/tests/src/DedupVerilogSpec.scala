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

  final class LeafPorts(
    using GeneratorScope[StubFull])
      extends Endpoints:
    parameters(DvVerilogSpec.stubParams("DupLeaf"))(identity)
    val p = inward(Wid).uFn(_ => Right(64))
    val q = inward(Wid).uFn(_ => Right(64))

  final class ClusterPorts(
    using WrapperScope)
      extends Endpoints:
    val leaf = generator(leafEntry)(new LeafPorts)

  def cluster(
  )(
    using
    ws:   WrapperScope,
    name: sourcecode.Name,
    loc:  SourceLocation
  ): ClusterPorts =
    wrapper(new ClusterPorts)

  final class SrcPort(
    using GeneratorScope[StubFull])
      extends Endpoints:
    parameters(DvVerilogSpec.stubParams("DupSrc"))(identity)
    val out = outward(Wid).dFn(_ => Right(32))

  def src(
  )(
    using
    ws:   WrapperScope,
    name: sourcecode.Name,
    loc:  SourceLocation
  ): SrcPort =
    generator(srcEntry)(new SrcPort)

  def buildTwins(): DesignSpec =
    Design {
      val clusterA = cluster()
      val clusterB = cluster()
      val s0       = src()
      val s1       = src()
      val s2       = src()
      val s3       = src()
      // Interleaved: clusterA's boundary sees its p edge first while clusterB sees its q edge first.
      clusterA.leaf.p <-- s0.out
      clusterB.leaf.q <-- s1.out
      clusterA.leaf.q <-- s2.out
      clusterB.leaf.p <-- s3.out
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
