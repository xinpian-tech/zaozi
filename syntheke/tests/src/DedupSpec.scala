// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.syntheke.tests

import me.jiuyang.syntheke.*
import utest.*

object DedupSpec extends TestSuite:

  def intEntry(name: String) =
    new GeneratorEntry[Int](GeneratorId(s"test.$name", "1"), Codec.fromReadWriter[Int](ujson.Str("int")))

  /** Two identical clusters: each wraps one consumer with the same generator and full parameter. */
  def buildTwinSoc(sameParam: Boolean): DesignSpec =
    val consEntry = intEntry("Cons")
    val prodEntry = intEntry("Prod")
    Design {
      def producerBody(
        using GeneratorScope[Int]
      ) =
        parametersConst(0)
        val out = outward(Wid).dFn(_ => Right(32))
        out
      def producer(
        name: String
      )(
        using WrapperScope
      ) =
        locally {
          given sourcecode.Name = sourcecode.Name(name)
          generator(prodEntry)(producerBody)
        }
      def clusterBody(
        fp: Int
      )(
        using WrapperScope
      ) =
        val cons = generator(consEntry) {
          parametersConst(fp)
          val in = inward(Wid).uFn(_ => Right(64))
          in
        }
        cons
      def cluster(
        name: String,
        fp:   Int
      )(
        using WrapperScope
      ) =
        locally {
          given sourcecode.Name = sourcecode.Name(name)
          wrapper(clusterBody(fp))
        }
      val prodOut  = producer("p0")
      val prodOut2 = producer("p1")
      val aIn      = cluster("clusterA", 7)
      val bIn      = cluster("clusterB", if sameParam then 7 else 8)
      aIn <-- prodOut
      bIn <-- prodOut2
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
