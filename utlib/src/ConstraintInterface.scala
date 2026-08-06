// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.utlib

import scala.language.dynamics

import me.jiuyang.smtlib.default.{smtValue, given}
import me.jiuyang.smtlib.tpe.{Ref as SMTRef, SInt as SMTInt}
import me.jiuyang.utlib.magic.constraintInterfaceSelectDynamic
import me.jiuyang.zaozi.{HWInterface, TypeImpl}
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.valuetpe.{Bits, Bool, Clock, Data, Reset, SInt, UInt}

import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

import java.lang.foreign.Arena

private[utlib] enum ConstraintInputKind:
  case Bool, Bits, UInt, SInt

/** One SMT integer input across the solve's cycle range.
  *
  * The hardware field is used only to derive the name, width and default range. Constraint expressions operate solely
  * on smtlib values.
  */
final class ConstraintPort private[utlib] (
  val name:   String,
  val width:  Int,
  val kind:   ConstraintInputKind,
  val cycles: Int,
  owner: ConstraintInterface[?]):

  def at(
    cycle: Int
  )(
    using Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line
  ): SMTRef[SMTInt] = owner.value(name, cycle)

/** A typed symbolic view of a Zaozi hardware interface.
  *
  * `io.A` has the same compile-time field lookup as Zaozi's `Interface`, but the selected [[ConstraintPort]] carries no
  * hardware value type. Calling `at(cycle)` returns an smtlib integer reference, appending one `smt.declare_fun` on
  * first use and reusing it afterwards.
  */
final class ConstraintInterface[I <: HWInterface[?]](
  val interfaceType: I,
  val cycles:        Int
)(
  using Arena,
  Context,
  TypeImpl)
    extends Dynamic:

  require(cycles > 0, "cycles must be positive")

  private val materializedInterfaceType = interfaceType.toMlirType
  private val declared                  = scala.collection.mutable.Map.empty[(String, Int), SMTRef[SMTInt]]

  val inputPorts: Seq[ConstraintPort] = interfaceType.elements.collect {
    case field if field.isFlipped && inputKind(field.dataType).nonEmpty =>
      val width = field.dataType.width
      require(
        width >= 1 && width <= 30,
        s"input ${field.name}: width $width is out of the supported 1..30 range " +
          "(the smtlib integer-constant binding takes an Int)"
      )
      new ConstraintPort(field.name, width, inputKind(field.dataType).get, cycles, this)
  }

  interfaceType.elements.foreach { field =>
    if field.isFlipped && inputKind(field.dataType).isEmpty then
      field.dataType match
        case _: Clock | _: Reset => ()
        case other               =>
          throw new IllegalArgumentException(
            s"input ${field.name}: ${other.getClass.getSimpleName} is not a supported flat numeric UT input"
          )
  }

  private val portsByName = inputPorts.map(port => port.name -> port).toMap

  def field(name: String): ConstraintPort =
    portsByName
      .getOrElse(
        name,
        throw new IllegalArgumentException(s"$name is not a flat numeric input of the DUT")
      )

  transparent inline def selectDynamic(name: String): Any = ${ constraintInterfaceSelectDynamic('this, 'name) }

  private[utlib] def variableName(port: String, cycle: Int): String = s"ut_${port}_$cycle"

  private[utlib] def value(
    port:  String,
    cycle: Int
  )(
    using Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line
  ): SMTRef[SMTInt] =
    require(cycle >= 0 && cycle < cycles, s"cycle $cycle is outside 0 until $cycles")
    require(portsByName.contains(port), s"unknown UT input: $port")
    declared.getOrElseUpdate(port -> cycle, smtValue(variableName(port, cycle), me.jiuyang.smtlib.default.SInt))

  private def inputKind(data: Data): Option[ConstraintInputKind] = data match
    case _: Bool => Some(ConstraintInputKind.Bool)
    case _: Bits => Some(ConstraintInputKind.Bits)
    case _: UInt => Some(ConstraintInputKind.UInt)
    case _: SInt => Some(ConstraintInputKind.SInt)
    case _ => None
