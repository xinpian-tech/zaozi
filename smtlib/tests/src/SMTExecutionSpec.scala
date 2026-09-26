// SPDX-License-Identifier: Apache-2.0
package me.jiuyang.smtlib.tests

import me.jiuyang.smtlib.SolverContext
import me.jiuyang.smtlib.default.{*, given}
import me.jiuyang.smtlib.parser.Z3Status
import org.llvm.mlir.scalalib.capi.ir.{*, given}
import utest.*

object SMTExecutionSpec extends TestSuite:
  val tests = Tests:
    test("solver executes the naming DSL directly"):
      withSMTContext:
        val result = solver {
          val a = smtValue(Bool)
          val b = smtValue(SInt)
          assert(a.operation.getAttributeByName("namePrefix").stringAttrGetValue == "a")
          assert(b.operation.getAttributeByName("namePrefix").stringAttrGetValue == "b")
          smtAssert(a)
          smtAssert(b === 42.S)
          smtCheck
        }
        assert(result == Seq(Z3Status.Sat))
    test("push and pop preserve one solver across multiple checks"):
      withSMTContext:
        val result = solver {
          val a = smtValue(Bool)
          smtAssert(a)
          smtCheck
          smtPush(1)
          smtAssert(!a)
          smtCheck
          smtPop(1)
          smtCheck
        }
        assert(result == Seq(Z3Status.Sat, Z3Status.Unsat, Z3Status.Sat))
    test("each execution starts with fresh solver and naming state"):
      withSMTContext:
        var exports         = 0
        given SolverContext = new SolverContext(debug = Some(_ => exports += 1))
        for expected <- Seq(Z3Status.Unsat, Z3Status.Sat) do
          val result = solver {
            given sourcecode.Name.Machine = sourcecode.Name.Machine("$anonfun")
            val a                         = smtValue(Bool)
            assert(a.operation.getAttributeByName("namePrefix").stringAttrGetValue == "_GEN_0")
            smtAssert(a)
            if expected == Z3Status.Unsat then smtAssert(!a)
            smtCheck
          }
          assert(result == Seq(expected))
        assert(exports == 2)
    test("no implicit final check"):
      withSMTContext:
        val result = solver { smtAssert(false.B) }
        assert(result.isEmpty)
    test("reset affects subsequent checks"):
      withSMTContext:
        val result = solver {
          smtAssert(false.B)
          smtCheck
          smtReset
          smtCheck
        }
        assert(result == Seq(Z3Status.Unsat, Z3Status.Sat))
    test("builder and debug failures leave the context reusable"):
      withSMTContext:
        intercept[IllegalStateException]:
          solver {
            val a = smtValue(Bool)
            smtAssert(a)
            throw new IllegalStateException("builder failed")
          }
        intercept[IllegalStateException]:
          given SolverContext = new SolverContext(debug = Some(_ => throw new IllegalStateException("debug failed")))
          solver { smtCheck }
        assert(solver { smtCheck } == Seq(Z3Status.Sat))
