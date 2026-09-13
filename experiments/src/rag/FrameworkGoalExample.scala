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

  // Endpoints only: these forms do not constrain the intervening cycles.
  def bounded(before: Referable[Bool], after: Referable[Bool], lo: Int, hi: Int)(using ClockEvent)(using
    Arena, Context, Block, sourcecode.File, sourcecode.Line, sourcecode.Name.Machine, InstanceContext
  ): Sequence = before.S.##(lo, Some(hi))(after.S)

  // Native past preserves Bool/UInt/SInt/Bits types and emits clocked SVA $past; delay > 0.
  def previously(predicate: Referable[Bool], cycles: Int)(using ClockEvent)(using
    Arena, Context, Block, sourcecode.File, sourcecode.Line, sourcecode.Name.Machine, InstanceContext
  ): Referable[Bool] = past(predicate, cycles)

  def changed(signal: Referable[Bits], cycles: Int)(using ClockEvent)(using
    Arena, Context, Block, sourcecode.File, sourcecode.Line, sourcecode.Name.Machine, InstanceContext
  ): Referable[Bool] = !(past(signal, cycles) === signal)

  // The past sample is at the witnessed starting event, not before available history.
  def changedAfter(start: Referable[Bool], signal: Referable[Bits], cycles: Int)(using ClockEvent)(using
    Arena, Context, Block, sourcecode.File, sourcecode.Line, sourcecode.Name.Machine, InstanceContext
  ): Sequence = start.S.##(cycles)((!(past(signal, cycles) === signal)).S)

  // A bounded sequence describes an event to witness. Implication can be vacuously true
  // when its antecedent never occurs; it does not by itself request a transaction.
  // The model supplies Gen expressions; the framework extracts labels and builds the fixed UT.
  // Its complete source owns imports, architecture and clock/reset context.
  def emit(expression: Gen.Expr, label: String)(using ClockEvent)(using
    Arena, Context, Block, sourcecode.File, sourcecode.Line, sourcecode.Name.Machine, InstanceContext
  ): Unit = Gen(expression, label)
