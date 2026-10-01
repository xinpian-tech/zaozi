// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Huang Rui <vowstar@gmail.com>
package me.jiuyang.stdlib.prcm

import scala.collection.immutable.SortedMap

import me.jiuyang.decoder.{BitSet, TruthTable}
import me.jiuyang.stdlib.default.DecoderParameter

private[prcm] object PRCMSequenceDecoder:
  private def table(
    name:        String,
    inputWidth:  Int,
    outputWidth: Int,
    inputs:      Seq[Int]
  )(output:      Int => Int
  ): DecoderParameter =
    val rows     = inputs.map: input =>
      BitSet.bitpat(BigInt(input), (BigInt(1) << inputWidth) - 1, inputWidth) -> output(input).toString
    val encoding = SortedMap.from((rows.map(_._2) :+ "0").distinct.map: value =>
      value -> BitSet.bitpat(BigInt(value), (BigInt(1) << outputWidth) - 1, outputWidth))
    DecoderParameter(name, Seq(TruthTable("value", rows, "0", encoding)))

  lazy val domainNext: DecoderParameter =
    table("PRCMDomainNext", 11, 4, (0 until 2048).filter(i => ((i >> 5) & 3) < 3)): input =>
      PRCMSequence
        .step(
          PRCMPhase.fromOrdinal(input >> 7),
          PRCMTarget.fromOrdinal((input >> 5) & 3),
          PRCMFeedback.fromBits(input & 15),
          (input & 16) != 0
        )
        .ordinal

  lazy val domainControl: DecoderParameter = table("PRCMDomainControl", 5, 7, 0 until 32): input =>
    val phase   = PRCMPhase.fromOrdinal(input >> 1)
    val control = PRCMSequence.control(phase)
    val flags   = Seq(
      control.power,
      control.clock,
      control.reset,
      control.isolation,
      control.quiesce,
      PRCMSequence.fault(phase),
      PRCMSequence.powerLost(phase, (input & 1) != 0)
    )
    flags.zipWithIndex.foldLeft(0):
      case (value, (flag, bit)) => if flag then value | (1 << bit) else value

  lazy val serviceNext: DecoderParameter = table("PRCMServiceNext", 6, 2, 0 until 64): input =>
    PRCMServiceSequence
      .step(
        PRCMServicePhase.fromOrdinal(input >> 4),
        (input & 1) != 0,
        (input & 2) != 0,
        (input & 4) != 0,
        (input & 8) != 0
      )
      .ordinal

  lazy val serviceControl: DecoderParameter = table("PRCMServiceControl", 2, 2, 0 until 4): input =>
    val phase = PRCMServicePhase.fromOrdinal(input)
    (if PRCMServiceSequence.request(phase) then 1 else 0) | (if phase == PRCMServicePhase.Hold then 2 else 0)
