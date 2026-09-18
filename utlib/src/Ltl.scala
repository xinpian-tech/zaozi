// SPDX-License-Identifier: Apache-2.0
package me.jiuyang.utlib

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}
import java.lang.foreign.Arena

/** A source-local model argument error, not a CIRCT/toolchain failure. */
final class LtlArgumentException(val code: String, val file: String, val line: Int, message: String)
    extends IllegalArgumentException(message)

/** Design-neutral predicates for model-authored LTL. No ports, protocol sequencing,
  * history, registers or assumptions are introduced by these helpers.
  */
object Ltl:
  private def check(condition: Boolean, code: String, message: => String)(using
    file: sourcecode.File, line: sourcecode.Line
  ): Unit =
    if !condition then throw new LtlArgumentException(code, file.value, line.value, message)

  /** Compare a numeric signal to a constant of its own type and width.
    * Bits/UInt accept unsigned values; SInt accepts signed values. Never truncate.
    */
  def is[D <: Bits | UInt | SInt](signal: Referable[D], value: BigInt)(using
    Arena, Context, Block, sourcecode.File, sourcecode.Line, sourcecode.Name.Machine, InstanceContext
  ): Referable[Bool] =
    val width = signal.width
    check(width > 0, "ltl_width", "Ltl.is requires a known positive signal width")
    val unsignedHint = " Use Ltl.isZero/isOnes for bit patterns, or BigInt(digits, radix) for numeric text; " +
      "BigInt(Int) cannot undo Scala Int overflow. Values are never wrapped or truncated."
    signal.getType match
      case _: Bits =>
        check(value >= 0 && value.bitLength <= width, "ltl_unsigned_range",
          s"Ltl.is value $value does not fit unsigned width $width." + unsignedHint)
        signal.asInstanceOf[Referable[Bits]] === value.B(width)
      case _: UInt =>
        check(value >= 0 && value.bitLength <= width, "ltl_unsigned_range",
          s"Ltl.is value $value does not fit unsigned width $width." + unsignedHint)
        signal.asInstanceOf[Referable[UInt]] === value.U(width)
      case _: SInt =>
        check(value >= -(BigInt(1) << (width - 1)) && value < (BigInt(1) << (width - 1)), "ltl_signed_range",
          s"Ltl.is value $value does not fit signed width $width. Values are never wrapped or truncated.")
        signal.asInstanceOf[Referable[SInt]] === value.S(width)

  /** Every bit is zero. Returns a predicate, not a constant or an assignment. */
  def isZero[D <: Bits | UInt | SInt](signal: Referable[D])(using
    Arena, Context, Block, sourcecode.File, sourcecode.Line, sourcecode.Name.Machine, InstanceContext
  ): Referable[Bool] = is(signal, BigInt(0))

  /** Every bit is one: -1 for SInt, the unsigned maximum for Bits/UInt. */
  def isOnes[D <: Bits | UInt | SInt](signal: Referable[D])(using
    Arena, Context, Block, sourcecode.File, sourcecode.Line, sourcecode.Name.Machine, InstanceContext
  ): Referable[Bool] =
    check(signal.width > 0, "ltl_width", "Ltl.isOnes requires a known positive signal width")
    val value = signal.getType match
      case _: SInt => BigInt(-1)
      case _       => (BigInt(1) << signal.width) - 1
    is(signal, value)
