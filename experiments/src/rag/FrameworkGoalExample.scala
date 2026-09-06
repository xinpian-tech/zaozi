// SPDX-License-Identifier: Apache-2.0

// Task: express a generation goal using caller-supplied predicates and temporal operators.
// Given: symbolic signals, magnitudes, widths and delays, never a design-specific answer.
import me.jiuyang.utlib.Gen
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.ltltpe.*
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}
import java.lang.foreign.Arena

object FrameworkGoalExample:
  // Bool is already a predicate. Bool has NO .asUInt and NO &&.
  // Use & / | / !, with parentheses around comparisons. No category wrappers.
  def asserted(enabled: Referable[Bool]): Referable[Bool] = enabled

  def conjunction(enabled: Referable[Bool], predicate: Referable[Bool])(using
    Arena, Context, Block, sourcecode.File, sourcecode.Line, sourcecode.Name.Machine, InstanceContext
  ): Referable[Bool] = enabled & predicate

  def deasserted(enabled: Referable[Bool])(using
    Arena, Context, Block, sourcecode.File, sourcecode.Line, sourcecode.Name.Machine, InstanceContext
  ): Referable[Bool] = !enabled

  // Bits === UInt is illegal: convert Bits with .asUInt, or stay in Bits on both sides.
  // A String has no .U method. For hexadecimal text use BigInt(digits, 16), not "h...".U.
  // BigInt avoids Scala Int overflow for large literals; specify the signal's width.
  def unsignedEquality(signal: Referable[Bits], magnitude: BigInt, width: Int)(using
    Arena, Context, Block, sourcecode.File, sourcecode.Line, sourcecode.Name.Machine, InstanceContext
  ): Referable[Bool] = signal.asUInt === magnitude.U(width)

  def bitsEquality(signal: Referable[Bits], magnitude: BigInt, width: Int)(using
    Arena, Context, Block, sourcecode.File, sourcecode.Line, sourcecode.Name.Machine, InstanceContext
  ): Referable[Bool] = signal === magnitude.B(width)

  // .S lifts a Bool onto the provided clock. .##(gap)(...) is a fixed cycle delay;
  // before.S ### after.S is the one-cycle form. Use & / | for sequence combination.
  def ordered(before: Referable[Bool], after: Referable[Bool], gap: Int)(using ClockEvent)(using
    Arena, Context, Block, sourcecode.File, sourcecode.Line, sourcecode.Name.Machine, InstanceContext
  ): Sequence = before.S.##(gap)(after.S)

  // Gen.past is available only inside the Gen expression context. It captures clock cycles,
  // not handshake-qualified beats. Gen automatically requires real history at the goal's start.
  def changed(signal: Referable[Bits], width: Int, cycles: Int)(using Gen.Scope, ClockScope, ResetScope)(using
    Arena, Context, Block, sourcecode.File, sourcecode.Line, sourcecode.Name.Machine, InstanceContext
  ): Referable[Bool] = !(Gen.past(signal, width, cycles) === signal)

  // A bounded sequence describes an event to witness. Implication can be vacuously true
  // when its antecedent never occurs; it does not by itself request a transaction.
  // The framework calls Gen once. The JSON response supplies ONLY the expression,
  // not this helper object, a DUT implementation, or another Gen call.
  def emit(expression: Gen.Scope ?=> Gen.Expr, label: String)(using ClockEvent)(using
    Arena, Context, Block, sourcecode.File, sourcecode.Line, sourcecode.Name.Machine, InstanceContext
  ): Unit = Gen(expression, label)
