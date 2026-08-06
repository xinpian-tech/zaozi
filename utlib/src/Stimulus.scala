// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.utlib

import scala.language.dynamics

import me.jiuyang.utlib.magic.stimulusInterfaceSelectDynamic
import me.jiuyang.zaozi.HWInterface

/** Serializable representation kept independent of the live Zaozi interface. */
private[utlib] final case class StimulusData(
  dut:    String,
  cycles: Int,
  inputs: Map[String, Vector[BigInt]]):

  require(cycles > 0, "cycles must be positive")
  require(inputs.values.forall(_.size == cycles), "every input must contain exactly one value per cycle")

private[utlib] object StimulusData:
  given upickle.default.ReadWriter[StimulusData] = upickle.default.macroRW

/** Concrete values for one flat numeric DUT input. */
final class StimulusPort private[utlib] (
  val name: String,
  val values: Vector[BigInt]):

  val cycles: Int = values.size

  def at(cycle: Int): BigInt =
    require(cycle >= 0 && cycle < cycles, s"cycle $cycle is outside 0 until $cycles")
    values(cycle)

/** A typed view of solved values with the same field lookup as the DUT interface. */
final class StimulusInterface[I <: HWInterface[?]] private[utlib] (
  data: StimulusData)
    extends Dynamic:

  private val portsByName = data.inputs.map { case (name, values) =>
    name -> new StimulusPort(name, values)
  }.toMap

  def field(name: String): StimulusPort =
    portsByName.getOrElse(name, throw new IllegalArgumentException(s"$name is not a flat numeric input of the DUT"))

  transparent inline def selectDynamic(name: String): Any = ${ stimulusInterfaceSelectDynamic('this, 'name) }

/** Solved input values retaining the DUT's Scala interface type for typed lookup. */
final class SolvedStimulus[I <: HWInterface[?]] private[utlib] (private[utlib] val data: StimulusData):

  val dut:    String               = data.dut
  val cycles: Int                  = data.cycles
  val io:     StimulusInterface[I] = new StimulusInterface(data)

  override def equals(other: Any): Boolean = other match
    case that: SolvedStimulus[?] => data == that.data
    case _ => false

  override def hashCode(): Int = data.hashCode

  override def toString: String = s"SolvedStimulus(${data.dut},${data.cycles},${data.inputs})"

private[utlib] object SolvedStimulus:
  def apply[I <: HWInterface[?]](data: StimulusData): SolvedStimulus[I] = new SolvedStimulus(data)
