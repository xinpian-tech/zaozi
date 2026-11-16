// SPDX-License-Identifier: Apache-2.0
package me.jiuyang.tblib.default

import java.lang.foreign.Arena
import org.llvm.circt.scalalib.capi.dialect.firrtl.{FirrtlBundleField, TypeApi as FirrtlTypeApi, given}
import org.llvm.circt.scalalib.dialect.firrtl.operation.{ConnectApi, RefDefineApi, given}
import org.llvm.mlir.scalalib.capi.ir.{Block, Context, LocationApi, Value, given}

/** Default port selection and wiring shared by IR and Scala testbenches. */
private[tblib] object TestbenchConnections:
  private def isClock(
    field: FirrtlBundleField
  )(
    using Arena
  ): Boolean =
    summon[FirrtlTypeApi].isClock(field.getType)

  def exposed(
    fields: Seq[FirrtlBundleField]
  )(
    using Arena
  ): Seq[Int] =
    val clocks  = fields.filter(isClock)
    require(
      clocks.size == 1 && clocks.head.getIsFlip,
      "DUT must have exactly one Clock input; multiple clocks are unsupported"
    )
    val indices = fields.indices.filter(i => !isClock(fields(i)) && (fields(i).getIsFlip || fields(i).getType.isRef))
    require(
      indices.forall(i =>
        !fields(i).getIsFlip || fields(i).getType.isUInt || fields(i).getType.isSInt || fields(i).getType.isReset
      ),
      "automatic testbench requires scalar integer/reset stimulus inputs"
    )
    indices

  def connect(
    dut:   Seq[(FirrtlBundleField, Value)],
    clock: Seq[(FirrtlBundleField, Value)],
    ports: Map[String, Value]
  )(
    using Arena,
    Context,
    Block
  ): Unit =
    val fields  = dut.map(_._1)
    val indices = exposed(fields)
    require(
      clock.size == 1 && !clock.head._1.getIsFlip && isClock(clock.head._1),
      "clock module must have no inputs and exactly one Clock output"
    )
    require(
      ports.keySet == indices.map(i => fields(i).getName).toSet,
      "testbench ports do not match DUT stimulus and probe ports"
    )
    val loc     = summon[LocationApi].locationUnknownGet
    summon[ConnectApi]
      .op(src = clock.head._2, dst = dut(fields.indexWhere(isClock))._2, location = loc)
      .operation
      .appendToBlock()
    indices.foreach: i =>
      val (field, value) = dut(i)
      val port           = ports(field.getName)
      if field.getIsFlip then summon[ConnectApi].op(src = port, dst = value, location = loc).operation.appendToBlock()
      else summon[RefDefineApi].op(port, value, loc).operation.appendToBlock()
