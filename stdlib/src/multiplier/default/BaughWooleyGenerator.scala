// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 xinpian-tech
package me.jiuyang.stdlib.multiplier.default

import java.lang.foreign.Arena
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*

/** Coordinate-preserving Baugh-Wooley partial products.
  *
  * `bits(i)(j)` is always the cell generated from `A(i)` and `B(j)`. Keeping that coordinate is required by the fixed
  * CHN compressor schedule; a column bucket cannot distinguish the reference R0/A0/A6 connections.
  */
private[default] case class PartialProductMatrix(bits: Vector[Vector[Referable[Bool]]]):
  require(bits.nonEmpty && bits.head.nonEmpty, "partial-product matrix must be non-empty")
  require(bits.forall(_.size == bits.head.size), "partial-product matrix must be rectangular")

  def aWidth:                      Int             = bits.size
  def bWidth:                      Int             = bits.head.size
  def apply(aBit: Int, bBit: Int): Referable[Bool] = bits(aBit)(bBit)

/** Signed Baugh-Wooley correction inputs, generated independently from the partial-product matrix. */
private[default] case class BaughWooleyCorrections(
  aSignCorrection:           Referable[Bool],
  bSignCorrection:           Referable[Bool],
  aSignComplementCorrection: Referable[Bool],
  bSignComplementCorrection: Referable[Bool],
  topCorrection:             Referable[Bool])

/** Generate the coordinate-preserving modified Baugh-Wooley matrix used by the general CHN datapath. */
private[default] object BaughWooleyGenerator:
  private enum PartialProductKind:
    case Positive
    case NegABySignB
    case NegBBySignA

  private def signMatrix(n: Int, m: Int): Vector[Vector[PartialProductKind]] =
    Vector.tabulate(n, m): (i, j) =>
      if i < n - 1 && j == m - 1 then PartialProductKind.NegABySignB
      else if i == n - 1 && j < m - 1 then PartialProductKind.NegBBySignA
      else PartialProductKind.Positive

  def generatePartialProduct(
    aNot:   Vector[Referable[Bool]],
    bNot:   Vector[Referable[Bool]],
    signed: Referable[Bool]
  )(
    using Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line,
    sourcecode.Name.Machine,
    InstanceContext
  ): PartialProductMatrix =
    val n = aNot.size
    val m = bNot.size

    val aNotX = aNot.dropRight(1).map(_ ^ signed)
    val bNotX = bNot.dropRight(1).map(_ ^ signed)

    PartialProductMatrix(signMatrix(n, m).zipWithIndex.map: (row, i) =>
      row.zipWithIndex.map: (kind, j) =>
        kind match
          case PartialProductKind.Positive    => !(aNot(i) | bNot(j))
          case PartialProductKind.NegABySignB => !(aNotX(i) | bNot(j))
          case PartialProductKind.NegBBySignA => !(aNot(i) | bNotX(j)))

  def generateCorrections(
    aBits:  Vector[Referable[Bool]],
    bBits:  Vector[Referable[Bool]],
    aNot:   Vector[Referable[Bool]],
    bNot:   Vector[Referable[Bool]],
    signed: Referable[Bool]
  )(
    using Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line,
    sourcecode.Name.Machine,
    InstanceContext
  ): BaughWooleyCorrections =
    require(aBits.nonEmpty && bBits.nonEmpty, "Baugh-Wooley corrections require non-empty operands")
    require(aBits.size == aNot.size && bBits.size == bNot.size, "operand and inverted-operand widths must match")

    BaughWooleyCorrections(
      aSignCorrection = aBits.last & signed,
      bSignCorrection = bBits.last & signed,
      aSignComplementCorrection = aNot.last & signed,
      bSignComplementCorrection = bNot.last & signed,
      topCorrection = signed
    )
