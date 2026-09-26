// SPDX-License-Identifier: Apache-2.0
package me.jiuyang.smtlib.tests

import me.jiuyang.smtlib.default.{*, given}
import me.jiuyang.smtlib.parser.Z3Status
import org.llvm.mlir.scalalib.capi.ir.given
import utest.*

object NamingSpec extends TestSuite:
  val tests = Tests:
    test("value declarations use Scala val names"):
      withSMTContext:
        solver {
          val a = smtValue(Bool)
          val b = smtValue(Bool)
          assert(a.operation.getAttributeByName("namePrefix").stringAttrGetValue == "a")
          assert(b.operation.getAttributeByName("namePrefix").stringAttrGetValue == "b")
          smtAssert(a)
          smtAssert(!b)
          smtCheck
        } ==> Seq(Z3Status.Sat)
    test("function declarations use Scala val names"):
      withSMTContext:
        solver {
          val count = smtValue(SInt)
          val f     = smtFunc(Seq(SInt, Bool), Bool)
          val g     = smtValue(SMTFunc(Seq(SInt, Bool), Bool))
          assert(f.operation.getAttributeByName("namePrefix").stringAttrGetValue == "f")
          assert(g.operation.getAttributeByName("namePrefix").stringAttrGetValue == "g")
          smtAssert(f(count, true.B))
          smtAssert(!g(count, true.B))
          smtCheck
        } ==> Seq(Z3Status.Sat)
    test("declarations in an anonymous function"):
      withSMTContext:
        solver {
          (0 until 2).foreach { index =>
            val value = { given sourcecode.Name.Machine = sourcecode.Name.Machine("$anonfun"); smtValue(Bool) }
            assert(value.operation.getAttributeByName("namePrefix").stringAttrGetValue == s"_GEN_$index")
            smtAssert(value)
          }
          smtCheck
        } ==> Seq(Z3Status.Sat)
    test("contextual names"):
      withSMTContext:
        solver {
          val core   = { given sourcecode.Name.Machine = sourcecode.Name.Machine("domain_core"); smtValue(Bool) }
          val enable = {
            given sourcecode.Name.Machine = sourcecode.Name.Machine("domain_enable"); smtFunc(Seq(SInt, Bool), Bool)
          }
          assert(core.operation.getAttributeByName("namePrefix").stringAttrGetValue == "domain_core")
          assert(enable.operation.getAttributeByName("namePrefix").stringAttrGetValue == "domain_enable")
          smtAssert(core)
          smtAssert(enable(1.S, core))
          smtCheck
        } ==> Seq(Z3Status.Sat)
    test("each solver starts a fresh shared anonymous counter"):
      withSMTContext:
        for _ <- 0 until 2 do
          solver {
            given sourcecode.Name.Machine = sourcecode.Name.Machine("$anonfun")
            val a                         = smtValue(Bool)
            val b                         = smtFunc(Seq(SInt, Bool), Bool)
            val c                         = smtValue(SInt)
            assert(a.operation.getAttributeByName("namePrefix").stringAttrGetValue == "_GEN_0")
            assert(b.operation.getAttributeByName("namePrefix").stringAttrGetValue == "_GEN_1")
            assert(c.operation.getAttributeByName("namePrefix").stringAttrGetValue == "_GEN_2")
            smtAssert(a)
            smtAssert(b(c, a))
            smtCheck
          } ==> Seq(Z3Status.Sat)
    test("colliding explicit and generated prefixes denote distinct values"):
      withSMTContext:
        solver {
          val named     = { given sourcecode.Name.Machine = sourcecode.Name.Machine("_GEN_0"); smtValue(Bool) }
          val anonymous = { given sourcecode.Name.Machine = sourcecode.Name.Machine("$anonfun"); smtValue(Bool) }
          smtAssert(named)
          smtAssert(!anonymous)
          smtCheck
        } ==> Seq(Z3Status.Sat)
