// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.syntheke.demo

import me.jiuyang.syntheke.*
import me.jiuyang.syntheke.demo.zaoziimpl.{*, given}

val Pll = new GeneratorEntry[PllP]

/** The PLL ([[PllGen]]): one reference clock in from the board, `outHz` out to every consumer on the die. The loop
  * ratio comes out of the settled reference frequency, and a ratio the loop cannot lock fails here.
  */
final class PllNodes(
  name:  String,
  outHz: Int,
  taps:  Vector[String]
)(
  using GeneratorScope[PllP])
    extends Nodes:
  val ref          = inward(ClockDomain).uFn(_ => Right(()))
  private val outs = taps.map { n =>
    given sourcecode.Name = sourcecode.Name(n)
    outward(ClockDomain).dFn(_ => Right(outHz))
  }

  /** Clock taps are declared by name, so they are looked up by name. */
  def tap(n: String): ClockDomain.Outward =
    require(taps.contains(n), s"pll '$name' has no clock tap '$n' (taps: ${taps.mkString(", ")})")
    outs(taps.indexOf(n))

  parameters { view =>
    val refHz = view.edgeOf(ref)
    val ratio = BigInt(outHz).gcd(BigInt(refHz)).toInt
    val mult  = outHz / ratio
    val div   = refHz / ratio
    if mult > PllNodes.maxMult || div > PllNodes.maxDiv then
      Left(
        Violation(
          s"$refHz Hz to $outHz Hz needs a $mult/$div loop, beyond the PLL's ${PllNodes.maxMult}/${PllNodes.maxDiv}"
        )
      )
    else Right(PllP(refHz, outHz, mult, div, taps))
  }

object PllNodes:
  /** What the loop's dividers can actually reach. */
  val maxMult: Int = 64
  val maxDiv:  Int = 8

def pll(
  outHz: Int,
  taps:  Vector[String]
)(
  using
  ws:    WrapperScope,
  name:  sourcecode.Name,
  file:  sourcecode.File,
  line:  sourcecode.Line
): PllNodes =
  generator(Pll)(new PllNodes(name.value, outHz, taps))
