// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.zaozi.sv

import me.jiuyang.zaozi.default.bundleFieldName
import me.jiuyang.zaozi.sv.valuetpe.Data
import me.jiuyang.zaozi.sv.reftpe.{Referable, Writable}
import org.llvm.mlir.scalalib.capi.ir.{Context, Type, Value}
import org.llvm.circt.scalalib.dialect.sv.attr.ModportDirection
import java.lang.foreign.Arena

/** A native SystemVerilog interface definition, separate from a module's SVBundle. */
abstract class VerilogInterface extends Data:
  def name: String = getClass.getSimpleName.stripSuffix("$")
  private[sv] val signals  = collection.mutable.ArrayBuffer.empty[InterfaceSignal[?]]
  private[sv] val modports = collection.mutable.ArrayBuffer.empty[Modport[?]]
  protected def Signal[T <: Data](
    tpe: T
  )(
    using sourcecode.Name.Machine
  ): InterfaceSignal[T] =
    val signal = new InterfaceSignal(this, bundleFieldName, tpe)
    signals += signal
    signal
  protected def Modport(
    ports: ModportField*
  )(
    using sourcecode.Name.Machine
  ): me.jiuyang.zaozi.sv.Modport[this.type] =
    val port = new me.jiuyang.zaozi.sv.Modport[this.type](this, bundleFieldName, ports.toSeq)
    modports += port
    port
  private[sv] def rawType(
    using Arena,
    Context,
    TypeImpl
  ): Type = this.toMlirTypeImpl

final class InterfaceSignal[T <: Data] private[sv] (val owner: VerilogInterface, val name: String, val dataType: T):
  def input:  ModportField = ModportField(this, ModportDirection.Input)
  def output: ModportField = ModportField(this, ModportDirection.Output)
  def inout:  ModportField = ModportField(this, ModportDirection.InOut)
case class ModportField(signal: InterfaceSignal[?], direction: ModportDirection)
final class Modport[I <: VerilogInterface] private[sv] (
  val interface:         I,
  val name:              String,
  private[sv] val ports: Seq[ModportField])
    extends Data:
  private[sv] def rawType(
    using Arena,
    Context,
    TypeImpl
  ): Type = this.toMlirTypeImpl

final class InterfaceSignalRef[T <: Data] private[sv] (
  private[sv] val interfaceValue: Value,
  private[sv] val signal:         InterfaceSignal[T],
  private[sv] val modport:        Option[Modport[?]] = None)
    extends Referable[T],
      Writable[T]:
  private[zaozi] val _tpe = signal.dataType
