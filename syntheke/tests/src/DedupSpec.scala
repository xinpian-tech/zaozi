// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.syntheke.tests

import me.jiuyang.syntheke.*
import utest.*

object DedupSpec extends TestSuite:

  def intEntry(name: String) =
    new GeneratorEntry[Int](s"test.$name")

  /** Two identical clusters: each wraps one consumer with the same generator and full parameter. */
  def buildTwinSoc(sameParam: Boolean): DesignSpec =
    val consEntry = intEntry("Cons")
    val prodEntry = intEntry("Prod")

    final class ProdPort(
      using GeneratorScope[Int])
        extends Endpoints:
      parametersConst(0)
      val out = outward(Wid).dFn(_ => Right(32))
    def producer(
    )(
      using
      ws:   WrapperScope,
      name: sourcecode.Name,
      loc:  SourceLocation
    ) =
      generator(prodEntry)(new ProdPort)

    final class ClusterPorts(
      fp: Int
    )(
      using WrapperScope)
        extends Endpoints:
      val cons = generator(consEntry) {
        parametersConst(fp)
        val in = inward(Wid).uFn(_ => Right(64))
        in
      }
    def cluster(
      fp:   Int
    )(
      using
      ws:   WrapperScope,
      name: sourcecode.Name,
      loc:  SourceLocation
    ) =
      wrapper(new ClusterPorts(fp))

    Design {
      val p0       = producer()
      val p1       = producer()
      val clusterA = cluster(7)
      val clusterB = cluster(if sameParam then 7 else 8)
      clusterA.cons <-- p0.out
      clusterB.cons <-- p1.out
    }

  val tests = Tests {

    test("identical generator parameters and wrapper contents share one definition") {
      val resolved   = Negotiator.negotiate(buildTwinSoc(sameParam = true))
      val result     = Dedup.dedup(resolved)
      val a          = ModuleId.root / "clusterA"
      val b          = ModuleId.root / "clusterB"
      // Same consumer key and same wrapper key; both wrappers reference one definition named by the first instance.
      assert(result.keyOf(a / "cons") == result.keyOf(b / "cons"))
      assert(result.keyOf(a) == result.keyOf(b))
      val wrapperDef = result.definitions.find(_.instances.contains(a)).get
      assert(wrapperDef.instances == Vector(a, b))
      assert(wrapperDef.name == "clusterA")
      // The two producers also dedup; the root does not collide with anything.
      assert(result.keyOf(ModuleId.root / "p0") == result.keyOf(ModuleId.root / "p1"))
      assert(result.nameOf(ModuleId.root / "p0") == "p0")
    }

    test("different full parameters split definitions and same-name keys get preorder suffixes") {
      val resolved = Negotiator.negotiate(buildTwinSoc(sameParam = false))
      val result   = Dedup.dedup(resolved)
      val a        = ModuleId.root / "clusterA"
      val b        = ModuleId.root / "clusterB"
      assert(result.keyOf(a / "cons") != result.keyOf(b / "cons"))
      assert(result.keyOf(a) != result.keyOf(b))
      // Both consumer definitions have candidate name "cons": first keeps it, second gets the suffix.
      assert(result.nameOf(a / "cons") == "cons")
      assert(result.nameOf(b / "cons") == "cons_1")
      assert(result.nameOf(b) == "clusterB")
    }
  }
