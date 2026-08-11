// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.utlib.tests

import me.jiuyang.smtlib.default.{*, given}
import me.jiuyang.utlib.*
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.ltltpe.ClockEvent
import me.jiuyang.zaozi.magic.macros.generator
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*

import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

import java.lang.foreign.Arena
import utest.*

final case class CoveredUnitParameter(width: Int) extends Parameter

object CoveredUnitParameter:
  given upickle.default.ReadWriter[CoveredUnitParameter] = upickle.default.macroRW

class CoveredUnitLayers(parameter: CoveredUnitParameter) extends LayerInterface(parameter):
  def layers = Seq.empty

class CoveredUnitIO(parameter: CoveredUnitParameter) extends HWBundle(parameter):
  val clock = Flipped(Clock())
  val a     = Flipped(UInt(parameter.width))

class CoveredUnitProbe(parameter: CoveredUnitParameter)
    extends DVBundle[CoveredUnitParameter, CoveredUnitLayers](parameter)

/** A DUT whose only observable behavior is a cover point: `a === 13` at a clock edge. The
  * default constraints leave `a` free, and the solver's arbitrary pick misses 13 — which is
  * exactly what the closure loop's hint has to fix.
  */
@generator
object CoveredUnit
    extends Generator[CoveredUnitParameter, CoveredUnitLayers, CoveredUnitIO, CoveredUnitProbe]
    with HasUT[CoveredUnitParameter, CoveredUnitIO]:
  def constraints(
    parameter: CoveredUnitParameter
  )(
    using Arena,
    Context,
    Block,
    ConstraintInterface[CoveredUnitIO]
  ): Unit = ()

  def architecture(parameter: CoveredUnitParameter) =
    val io = summon[Interface[CoveredUnitIO]]
    given ClockEvent = posedge(io.clock)
    Cover((io.a === 13.U(parameter.width)).S, "cover_magic")

object CoverageLoopTest extends TestSuite:
  private val outputRoot = os.Path(sys.props("zaozi.utlib.outDir"), os.pwd)

  private def generator(name: String) =
    UTGenerator(
      CoveredUnit,
      CoveredUnitParameter(4),
      cycles = 1,
      outputDirectory = outputRoot / name
    )

  val tests: Tests = Tests:
    test("hinted re-solving reaches a goal the baseline stimulus misses"):
      val closure = generator("coverage-closed").closeCoverage(
        goals = Seq(
          CoverageGoal[CoveredUnitIO]("cover_magic"):
            val io = summon[ConstraintInterface[CoveredUnitIO]]
            smtAssert(io.a.at(0) === 13.S)
        ),
        maxRounds = 3
      )
      assert(closure.closed)
      assert(closure.hits.getOrElse("cover_magic", 0) > 0)
      // Round 0 is the unhinted baseline and misses; the hinted round supplies a = 13.
      assert(closure.rounds.head.goal.isEmpty)
      val hinted = closure.rounds.last
      assert(hinted.goal.contains("cover_magic"))
      assert(hinted.stimulus.exists(_.io.a.values == Vector(BigInt(13))))

    test("an unsatisfiable hint is reported as an unreached goal, not an error"):
      val closure = generator("coverage-unsat").closeCoverage(
        goals = Seq(
          CoverageGoal[CoveredUnitIO]("cover_magic"):
            // 99 violates the 4-bit width bound the solver always asserts — UNSAT.
            val io = summon[ConstraintInterface[CoveredUnitIO]]
            smtAssert(io.a.at(0) === 99.S)
        ),
        maxRounds = 3
      )
      assert(!closure.closed)
      assert(closure.missed == Set("cover_magic"))
      assert(closure.rounds.exists(round => round.goal.contains("cover_magic") && round.stimulus.isEmpty))
