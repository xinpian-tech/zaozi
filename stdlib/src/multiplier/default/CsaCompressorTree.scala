// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 xinpian-tech
package me.jiuyang.stdlib.multiplier.default

import java.lang.foreign.Arena
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*

/** The two resolved low product bits and the two upper carry-save rows. */
private[default] case class CompressedResult(
  product0: Referable[Bool],
  product1: Referable[Bool],
  row0:     Vector[Referable[Bool]],
  row1:     Vector[Referable[Bool]])

/** Fixed CHN 3:2 compressor schedule for the coordinate-preserving Baugh-Wooley matrix.
  *
  * The first, middle, last, and final rows correspond to the reference R0/A0/A6/A10 regions. Every cell is an
  * ordinary-operation [[compressAdd32]]; no GTECH operation is instantiated.
  */
private[default] object CsaCompressorTree:
  private case class CompressionRows(
    first:  Vector[CompressorOutput],
    middle: Vector[Vector[CompressorOutput]],
    last: Vector[CompressorOutput]):
    def resolvedLowBitAt(column: Int): Referable[Bool] = middle(column - 2)(0).sum
    def compressorCount:               Int             = first.size + middle.map(_.size).sum + last.size

  private def generateRows(
    matrix:      PartialProductMatrix,
    corrections: BaughWooleyCorrections
  )(
    using Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line,
    sourcecode.Name.Machine,
    InstanceContext
  ): CompressionRows =
    val n    = matrix.aWidth
    val m    = matrix.bWidth
    val zero = false.B

    val first = Vector.tabulate(m - 1): j =>
      compressAdd32(matrix(0, j + 1), matrix(1, j), zero)

    val middle = (2 to n - 2).foldLeft(Vector.empty[Vector[CompressorOutput]]): (rows, i) =>
      val previousRow = rows.lastOption
      val row         = Vector.tabulate(m - 1): j =>
        val (previousCarry, third) = (previousRow, j) match
          case (None, right) if right == m - 2           =>
            (first(right).carry, matrix(1, m - 1))
          case (None, column)                            =>
            (first(column).carry, first(column + 1).sum)
          case (Some(previous), right) if right == m - 2 =>
            (previous(right).carry, matrix(i - 1, m - 1))
          case (Some(previous), column)                  =>
            (previous(column).carry, previous(column + 1).sum)

        compressAdd32(matrix(i, j), previousCarry, third)
      rows :+ row

    val last = Vector.tabulate(m): j =>
      if j < m - 2 then compressAdd32(matrix(n - 1, j), middle.last(j).carry, middle.last(j + 1).sum)
      else if j == m - 2 then compressAdd32(matrix(n - 1, j), middle.last(j).carry, matrix(n - 2, m - 1))
      else
        compressAdd32(
          corrections.aSignComplementCorrection,
          corrections.bSignComplementCorrection,
          matrix(n - 1, m - 1)
        )

    CompressionRows(first, middle, last)

  def compress(
    matrix:      PartialProductMatrix,
    corrections: BaughWooleyCorrections
  )(
    using Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line,
    sourcecode.Name.Machine,
    InstanceContext
  ): CompressedResult =
    val n    = matrix.aWidth
    val m    = matrix.bWidth
    val zero = false.B

    val rows     = generateRows(matrix, corrections)
    val finalCsa = (2 until n + m).toVector.map: column =>
      val bSignAtColumn = if column == m - 1 then corrections.bSignCorrection else zero

      if column < n - 1 then compressAdd32(bSignAtColumn, zero, rows.resolvedLowBitAt(column))
      else if column == n - 1 then compressAdd32(corrections.aSignCorrection, bSignAtColumn, rows.last(0).sum)
      else if column == n + m - 1 then compressAdd32(corrections.topCorrection, rows.last(m - 1).carry, zero)
      else compressAdd32(bSignAtColumn, rows.last(column - n).carry, rows.last(column - n + 1).sum)

    require(
      rows.compressorCount + finalCsa.size == n * m,
      "CHN must contain one 3:2 compressor per partial-product coordinate"
    )

    CompressedResult(
      product0 = matrix(0, 0),
      product1 = rows.first(0).sum,
      row0 = finalCsa.map(_.sum),
      row1 = zero +: finalCsa.dropRight(1).map(_.carry)
    )
