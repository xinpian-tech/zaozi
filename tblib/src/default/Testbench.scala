// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.tblib.default

import me.jiuyang.tblib.{Testbench, TestbenchIO, TestbenchPort}
import me.jiuyang.zaozi.{DpiArg, DpiCallResult, DpiFunction, HWInterface, SVApi, SimApi}
import me.jiuyang.zaozi.default.given
import me.jiuyang.zaozi.valuetpe.{BundleField, Data}
import org.llvm.circt.scalalib.dialect.hw.operation.{Port, PortDirection}
import org.llvm.circt.scalalib.capi.dialect.sim.DPIDirection
import org.llvm.mlir.MlirOperation
import org.llvm.mlir.scalalib.capi.ir.{Block, Context, Value, given}

import java.lang.foreign.Arena

private[default] final class DefaultTestbench[I <: HWInterface[?]](
  val clock:          Value,
  private val fields: Seq[BundleField[?]],
  private val ports:  Seq[Port]
)(
  using Arena,
  Context,
  Block)
    extends Testbench[I]:
  require(
    fields.size == ports.size && fields
      .zip(ports)
      .forall((field, port) => field.name == port.name && field.isFlipped && port.direction == PortDirection.Input),
    "testbench fields must match the HW input ports"
  )

  val fallingClock: Value = clock.clockInv
  private val driven = Array.fill[Option[Value]](ports.size)(None)
  val io: TestbenchIO[I] = new TestbenchIO[I](this)
  private val testbenchBlock = summon[Block]

  def initial(
    body: Block ?=> Unit
  )(
    using Arena,
    Context,
    Block
  ): Unit =
    require(summon[Block].getParentOperation.getName.str == "hw.module", "initial requires the testbench module scope")
    summon[SVApi].initial(body)

  private def inProcedure(
    using Arena,
    Block
  ): Boolean =
    Iterator
      .iterate(summon[Block].getParentOperation)(_.getParentOperation)
      .takeWhile(operation => MlirOperation.ptr(operation.segment).address != 0)
      .exists(operation => Set("sv.always", "sv.initial").contains(operation.getName.str))

  def onClock(
    clock:   Value,
    enabled: Option[Value]
  )(body:    Block ?=> Unit
  )(
    using Arena,
    Context,
    Block
  ): Unit =
    require(summon[Block].getParentOperation.getName.str == "hw.module", "onClock requires the testbench module scope")
    summon[SVApi].onClock(clock, enabled)(body)

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
    locally:
      given Block = module.getFirstRegion.getFirstBlock
      summon[SVApi].dpiFunction(symbol, cName, arguments)

  def dpiCall(
    function: DpiFunction,
    inputs:   Seq[Value],
    enabled:  Option[Value]
  )(
    using Arena,
    Context,
    Block
  ): DpiCallResult =
    require(
      summon[Block].getParentOperation.getName.str == "hw.module",
      "dpiCall creates an independent process; use dpiCallProcedural inside onClock"
    )
    val sv = summon[SVApi]
    val outputs = function.arguments.filter(arg =>
      arg.direction == DPIDirection.Out || arg.direction == DPIDirection.Return
    )
    val registers = outputs.map: arg =>
      val tpe = if arg.signed then arg.width.integerTypeSignedGet else arg.width.integerTypeGet
      arg.name -> sv.reg(tpe, s"${function.symbol}_${arg.name}")
    onClock(fallingClock, enabled):
      val values = sv.dpiCallProcedural(function, inputs)
      registers.foreach((name, register) => sv.nonBlockingAssign(register, values(name)))
    DpiCallResult(registers.map((name, register) => name -> register.readInOut).toMap)

  def dpiCallProcedural(
    function: DpiFunction,
    inputs:   Seq[Value]
  )(
    using Arena,
    Context,
    Block
  ): DpiCallResult = summon[SVApi].dpiCallProcedural(function, inputs)

  def finish(
    condition: Value,
    success:   Boolean
  )(
    using Arena,
    Context,
    Block
  ): Unit =
    if inProcedure then
      summon[SVApi].ifElse(condition) {
        summon[SimApi].terminate(success)
      }()
    else summon[SimApi].clockedTerminate(clock, condition, success)

  private[tblib] def port[T <: Data](name: String): TestbenchPort[T] =
    portOption[T](name).getOrElse(throw new IllegalArgumentException(s"$name is not a testbench port"))

  private[tblib] def portOption[T <: Data](name: String): Option[TestbenchPort[T]] =
    fields.find(_.name == name).map(field => new TestbenchPort(field.asInstanceOf[BundleField[T]], this))

  private[tblib] def bind(
    field: BundleField[?],
    value: Value
  )(
    using Arena,
    Context,
    Block
  ): Unit =
    val index = fields.indexWhere(candidate => candidate.eq(field))
    require(index >= 0, s"${field.name} is not a driven testbench input")
    val port  = ports(index)
    require(driven(index).isEmpty, s"testbench input driven twice: ${port.name}")
    require(value.getType.equal(port.tpe), s"testbench input type mismatch: ${port.name}")
    val input = if inProcedure then
      // Procedure-local SSA results cannot escape the region. Store them in testbench registers instead.
      val (register, read) =
        given Block  = testbenchBlock
        val register = summon[SVApi].reg(port.tpe, s"${port.name}_stimulus")
        (register, register.readInOut)
      summon[SVApi].nonBlockingAssign(register, value)
      read
    else value
    driven(index) = Some(input)

  private[tblib] def inputValues: Seq[Value] =
    ports
      .zip(driven)
      .map: (port, value) =>
        value.getOrElse(throw new IllegalArgumentException(s"testbench input is not driven: ${port.name}"))
