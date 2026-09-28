// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.tblib.default

import me.jiuyang.tblib.{Testbench, TestbenchIO, TestbenchPort}
import me.jiuyang.zaozi.{DpiArg, DpiCallResult, DpiFunction, HWInterface, SimApi}
import me.jiuyang.zaozi.default.given
import me.jiuyang.zaozi.valuetpe.{BundleField, Data}
import org.llvm.circt.scalalib.dialect.hw.operation.Port
import org.llvm.mlir.MlirOperation
import org.llvm.mlir.scalalib.capi.ir.{Block, Context, Value, given}

import java.lang.foreign.Arena

private[default] final class DefaultTestbench[I <: HWInterface[?]](
  val clock:                  Value,
  private val stimulusFields: Seq[BundleField[?]],
  private val stimulusPorts:  Seq[Port]
)(
  using Arena,
  Context,
  Block)
    extends Testbench[I]:
  require(
    stimulusFields.size == stimulusPorts.size && stimulusFields
      .zip(stimulusPorts)
      .forall((field, port) => field.name == port.name),
    "testbench stimulus fields must match the HW ports"
  )

  val fallingClock: Value = clock.clockInv
  private val driven = Array.fill[Option[Value]](stimulusPorts.size)(None)
  val io: TestbenchIO[I] = new TestbenchIO[I](this)

  def dpiFunction(
    symbol:    String,
    cName:     Option[String],
    arguments: Seq[DpiArg]
  )(
    using Arena,
    Context,
    Block
  ): DpiFunction =
    val module = Iterator
      .iterate(summon[Block].getParentOperation)(_.getParentOperation)
      .takeWhile(operation => MlirOperation.ptr(operation.segment).address != 0)
      .find(_.getName.str == "builtin.module")
      .getOrElse(throw new IllegalArgumentException("DPI declaration requires an enclosing builtin.module"))
    summon[SimApi].dpiFunction(symbol, cName, arguments)(
      using summon[Arena],
      summon[Context],
      module.getFirstRegion.getFirstBlock
    )

  def dpiCall(
    function: DpiFunction,
    inputs:   Seq[Value],
    enabled:  Option[Value]
  )(
    using Arena,
    Context,
    Block
  ): DpiCallResult = summon[SimApi].dpiCall(function, fallingClock, enabled, inputs)

  def finish(
    condition: Value,
    success:   Boolean
  )(
    using Arena,
    Context,
    Block
  ): Unit = summon[SimApi].clockedTerminate(clock, condition, success)

  private[tblib] def port[T <: Data](name: String): TestbenchPort[T] =
    portOption[T](name).getOrElse(throw new IllegalArgumentException(s"$name is not a driven testbench input"))

  private[tblib] def portOption[T <: Data](name: String): Option[TestbenchPort[T]] =
    stimulusFields.find(_.name == name).map(field => new TestbenchPort(field.asInstanceOf[BundleField[T]], this))

  private[tblib] def bind(
    field: BundleField[?],
    value: Value
  )(
    using Arena
  ): Unit =
    val index = stimulusFields.indexWhere(candidate => candidate.eq(field))
    require(index >= 0, s"${field.name} is not a driven testbench input")
    val port  = stimulusPorts(index)
    require(driven(index).isEmpty, s"testbench input driven twice: ${port.name}")
    require(value.getType.equal(port.tpe), s"testbench input type mismatch: ${port.name}")
    driven(index) = Some(value)

  private[tblib] def inputValues: Seq[Value] =
    Seq(clock) ++ stimulusPorts
      .zip(driven)
      .map: (port, value) =>
        value.getOrElse(throw new IllegalArgumentException(s"testbench input is not driven: ${port.name}"))
