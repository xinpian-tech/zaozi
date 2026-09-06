// SPDX-License-Identifier: Apache-2.0
package me.jiuyang.utlib

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.ltltpe.*
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import org.llvm.circt.scalalib.capi.dialect.firrtl.given_TypeApi
import org.llvm.mlir.scalalib.capi.ir.{*, given}
import java.lang.foreign.Arena

/** One generation entry point: a hardware predicate, sequence or property describes the target trace.
  * No value/state/relation categories are required. Generation seeks a witness, not a universal proof.
  * Use finite-witness goals; accepting Property does not promise support for arbitrary infinite-time LTL.
  */
object Gen:
  type Expr = Referable[Bool] | Sequence | Property

  /** Per-call elaboration context. History cannot be constructed outside a Gen body, and guards cannot leak
    * between goals or modules. The guards constrain the starting cycle of the entire goal.
    */
  final class Scope private[Gen] ():
    private[Gen] val guards = collection.mutable.ArrayBuffer.empty[Referable[Bool]]

  /** The sampled value `cycles` clock cycles ago, not handshake-qualified transactions.
    * Reset-initialized history and its valid-depth guard are owned by the enclosing Gen call.
    * Even inside a future sequence, all requested history must already be valid at the goal's start.
    */
  def past(signal: Referable[Bits], width: Int, cycles: Int)(using
    scope: Scope, clock: ClockScope, reset: ResetScope
  )(using Arena, Context, Block, sourcecode.File, sourcecode.Line, sourcecode.Name.Machine, InstanceContext
  ): Referable[Bits] =
    require(width > 0, "history width must be positive")
    require(signal.refer.getType.getBitWidth(true) == width.toLong, "history width must match the signal width")
    require(cycles > 0, "history depth must be positive")
    val slots = Seq.fill(cycles)(RegInit(0.B(width)))
    slots.head := signal
    slots.sliding(2).foreach {
      case Seq(newer, older) => older := newer
      case _ => ()
    }
    val counterWidth = 32 - Integer.numberOfLeadingZeros(cycles)
    val beats = RegInit(0.U(counterWidth))
    val next = (beats + 1.U(counterWidth)).asBits.bits(counterWidth - 1, 0).asUInt
    beats := ((beats === cycles.U(counterWidth)) ? (beats, next))
    scope.guards += (beats === cycles.U(counterWidth))
    slots.last

  /** The framework supplies clock/reset context and calls Gen(expression, label) once per target.
    * Bool goals use the existing immediate lowering; sequences/properties retain their temporal operators.
    * Emit assert-not-goal for the BMC adapter; the JasperGold adapter converts the selected assertion to cover.
    */
  def apply(goal: Scope ?=> Expr, label: String)(using ClockEvent)(using
    Arena, Context, Block, sourcecode.File, sourcecode.Line, sourcecode.Name.Machine, InstanceContext
  ): Unit =
    val scope = new Scope()
    val expression = goal(using scope)
    val guard = scope.guards.reduceOption(_ & _)
    expression match
      case sequence: Sequence =>
        val guarded = guard.map(g => sequence & g.S).getOrElse(sequence)
        Assert(!guarded, label)
      case property: Property =>
        val guarded = guard.map(g => property & g.S).getOrElse(property)
        Assert(!guarded, label)
      case predicate: Referable[?] =>
        // Expr permits only Referable[Bool]; the type argument is erased at this match.
        val condition = predicate.asInstanceOf[Referable[Bool]]
        val guarded = guard.map(_ & condition).getOrElse(condition)
        Assert((!guarded).I, label)
