// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Huang Rui <vowstar@gmail.com>
package me.jiuyang.stdlib.prcm

import me.jiuyang.stdlib.mmio.*

enum PRCMPolicy derives upickle.default.ReadWriter:
  case Local
  case Fixed(mode: String)

case class PRCMChipMode(name: String, code: BigInt, domains: Map[String, PRCMPolicy])

given upickle.default.ReadWriter[PRCMChipMode] = upickle.default.macroRW

case class PRCMChip(modes: Seq[PRCMChipMode], resetMode: String):
  require(modes.nonEmpty, "chip needs at least one mode")
  require(modes.forall(_.name.nonEmpty), "chip mode names must not be empty")
  require(modes.map(_.name).distinct.size == modes.size, "chip mode names must be unique")
  require(modes.forall(_.code >= 0), "chip mode codes must be nonnegative")
  require(modes.map(_.code).distinct.size == modes.size, "chip mode codes must be unique")
  val reset     = modes
    .find(_.name == resetMode)
    .getOrElse(
      throw new IllegalArgumentException(s"chip reset mode $resetMode is not declared")
    )
  val modeWidth = modes.map(_.code.bitLength).max.max(1)

  private[prcm] def targets(domains: Seq[PRCMManagedDomain]): Seq[(PRCMChipMode, Seq[Option[PRCMTarget]])] =
    modes.map: mode =>
      require(mode.domains.keySet == domains.map(_.name).toSet, s"chip mode ${mode.name} must name every domain")
      mode -> domains.map: domain =>
        mode.domains(domain.name) match
          case PRCMPolicy.Local       => None
          case PRCMPolicy.Fixed(name) =>
            Some(
              domain.modes
                .find(_.name == name)
                .getOrElse(
                  throw new IllegalArgumentException(
                    s"chip mode ${mode.name} domain ${domain.name} mode $name is not declared"
                  )
                )
                .target
            )

given upickle.default.ReadWriter[PRCMChip] = upickle.default.macroRW

given mainargs.TokensReader.Simple[PRCMChip]:
  def shortName = "chip"
  def read(strs: Seq[String]): Right[Nothing, PRCMChip] =
    Right(upickle.default.read[PRCMChip](strs.head))

private[prcm] case class PRCMChipRegisterBank(chip: PRCMChip, dataWidth: Int):
  val request   = (0 until chip.modeWidth by 8).map: bit =>
    RegField(s"chip_request_${bit / 8}", (chip.modeWidth - bit).min(8)).readReadyValid.writeReadyValid
  val status    = RegField("chip_status", dataWidth).readReadyValid
  val registers = Seq(RegMapRegister(0, request), RegMapRegister(dataWidth / 8, Seq(status)))
