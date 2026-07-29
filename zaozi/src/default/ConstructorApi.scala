// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.zaozi.default

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*

import org.llvm.circt.scalalib.capi.dialect.firrtl.{
  given_FirrtlBundleFieldApi,
  given_FirrtlDirectionApi,
  given_TypeApi,
  FirrtlConvention,
  FirrtlNameKind
}
import org.llvm.circt.scalalib.dialect.firrtl.operation
import org.llvm.circt.scalalib.dialect.firrtl.operation.{
  AsResetPrimApi,
  ConnectApi,
  ConstantApi,
  InstanceApi,
  LayerBlockApi,
  ModuleApi,
  NodeApi,
  OpenSubfieldApi,
  RefDefineApi,
  RegApi,
  RegResetApi,
  SubfieldApi,
  When,
  WhenApi,
  WireApi,
  given
}
import org.llvm.mlir.scalalib.capi.ir.{Block, Context, LocationApi, Operation, given}

import java.lang.foreign.Arena

// When Import the default, all method in ConstructorApi should be exported
export given_ConstructorApi.*

/** Implements `ConstructorApi`; see that trait in `me.jiuyang.zaozi.Api` for each constructor's semantics. Also defines
  * `BigInt#B(width: Int)`, a width-explicit `Bits` literal, beyond what the abstract trait declares (still usable once
  * this `given` is imported).
  */
given ConstructorApi with
  def Clock(): Clock = new Object with Clock
  def Reset(): Reset = new Object with Reset

  def UInt(_width: Int): UInt =
    val w = _width
    new UInt:
      private[zaozi] val _width: Int = w

  def Bits(_width: Int): Bits =
    val w = _width
    new Bits:
      private[zaozi] val _width: Int = w

  def SInt(_width: Int): SInt =
    val w = _width
    new SInt:
      private[zaozi] val _width: Int = w

  def Bool(): Bool = new Object with Bool

  def Vec[T <: Data](size: Int, tpe: T): Vec[T] =
    new Vec[T]:
      private[zaozi] val _elementType = tpe
      private[zaozi] val _count       = size

  def when[COND <: Referable[Bool]](
    cond: COND
  )(body: (Arena, Context, Block) ?=> Unit
  )(
    using Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line
  ): When =
    val op0 = summon[WhenApi].op(cond.refer, locate)
    op0.operation.appendToBlock()
    body(
      using summon[Arena],
      summon[Context],
      op0.condBlock
    )
    op0

  extension (when: When)
    def otherwise(
      body: (Arena, Context, Block) ?=> Unit
    )(
      using Arena,
      Context
    ): Unit =
      given Block = when.elseBlock
      body

  extension (layer: LayerTree)
    def apply(name: String): LayerTree =
      layer.children(name)

  extension (layers: Seq[LayerTree])
    def apply(name: String): LayerTree =
      layers
        .find(_.name == name)
        .getOrElse(
          throw new Exception(s"No valid layer named: \"${name}\" found in ${layers.map(_.name).mkString(",")}")
        )

  def layer(
    layerName: String
  )(body:      (
      Arena,
      Context,
      Block,
      LayerTree,      // Current Layer
      Seq[LayerTree], // Children Layers
      sourcecode.File,
      sourcecode.Line
    ) ?=> Unit
  )(
    using Arena,
    Context,
    Block,
    Seq[LayerTree],
    sourcecode.File,
    sourcecode.Line
  ): Unit =
    val op0 = summon[LayerBlockApi].op(summon[Seq[LayerTree]](layerName).nameHierarchy, locate)
    op0.operation.appendToBlock()
    body(
      using summon[Arena],
      summon[Context],
      op0.block,
      summon[Seq[LayerTree]](layerName),
      summon[Seq[LayerTree]](layerName).children,
      summon[sourcecode.File],
      summon[sourcecode.Line]
    )

  def Wire[T <: Data](
    refType: T
  )(
    using Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line,
    sourcecode.Name.Machine,
    InstanceContext
  ): Wire[T] =
    val wireOp = summon[WireApi].op(
      name = valName,
      location = locate,
      nameKind = FirrtlNameKind.Interesting,
      tpe = refType.toMlirType
    )
    wireOp.operation.appendToBlock()
    new Wire[T]:
      val _tpe:       T         = refType
      val _operation: Operation = wireOp.operation

  def Reg[T <: Data](
    refType: T
  )(
    using ClockScope
  )(
    using Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line,
    sourcecode.Name.Machine,
    InstanceContext
  ): Reg[T] =
    val clockScope = summon[ClockScope]
    val regOp      = summon[RegApi].op(
      name = valName,
      location = locate,
      nameKind = FirrtlNameKind.Interesting,
      tpe = refType.toMlirType,
      clock = clockScope.clock.refer,
      clockEdge = clockScope.clockEdge
    )
    regOp.operation.appendToBlock()
    new Reg[T]:
      val _tpe:       T         = refType
      val _operation: Operation = regOp.operation

  def RegInit[T <: Data](
    input: Const[T]
  )(
    using ClockScope
  )(
    using ResetScope
  )(
    using Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line,
    sourcecode.Name.Machine,
    InstanceContext
  ): Reg[T] =
    val clockScope = summon[ClockScope]
    val resetScope = summon[ResetScope]
    val asResetOp  = summon[AsResetPrimApi].op(resetScope.reset.refer, locate)
    asResetOp.operation.appendToBlock()
    val regResetOp = summon[RegResetApi].op(
      name = valName,
      location = locate,
      nameKind = FirrtlNameKind.Interesting,
      tpe = input._tpe.toMlirType,
      clock = clockScope.clock.refer,
      reset = asResetOp.result,
      resetValue = input.refer,
      clockEdge = clockScope.clockEdge,
      resetType = resetScope.resetType,
      resetPolarity = resetScope.resetPolarity
    )
    regResetOp.operation.appendToBlock()
    new Reg[T]:
      val _tpe:       T         = input._tpe
      val _operation: Operation = regResetOp.operation

  def Node[T <: Data](
    ref: Referable[T]
  )(
    using Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line,
    sourcecode.Name.Machine,
    InstanceContext
  ): Node[T] =
    val nodeOp = summon[NodeApi].op(
      name = valName,
      location = locate,
      nameKind = FirrtlNameKind.Interesting,
      input = ref.refer
    )
    nodeOp.operation.appendToBlock()
    new Node[T]:
      val _tpe:       T         = ref._tpe
      val _operation: Operation = nodeOp.operation

  extension (bigInt: BigInt)
    def U(
      width: Int
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line
    ): Const[UInt] =
      val constOp = summon[ConstantApi].op(
        input = bigInt,
        width = width,
        signed = false,
        location = locate
      )
      constOp.operation.appendToBlock()
      new Const[UInt]:
        val _tpe:       UInt      = new UInt:
          private[zaozi] val _width = constOp.operation.getResult(0).getType.getBitWidth(true).toInt
        val _operation: Operation = constOp.operation
    def U(
      using Arena,
      Context,
      Block
    ): Const[UInt] = bigInt.U(scala.math.max(1, bigInt.bitLength))
    def B(
      width: Int
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line
    ): Const[Bits] =
      val constOp = summon[ConstantApi].op(
        input = bigInt,
        width = width,
        signed = false,
        location = locate
      )
      constOp.operation.appendToBlock()
      new Const[Bits]:
        val _tpe:       Bits      = new Bits:
          private[zaozi] val _width = constOp.operation.getResult(0).getType.getBitWidth(true).toInt
        val _operation: Operation = constOp.operation
    def B(
      using Arena,
      Context,
      Block
    ): Const[Bits] = bigInt.B(scala.math.max(1, bigInt.bitLength))
    def S(
      width: Int
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line
    ): Const[SInt] =
      val constOp = summon[ConstantApi].op(
        input = bigInt,
        width = width,
        signed = true,
        location = locate
      )
      constOp.operation.appendToBlock()
      new Const[SInt]:
        val _tpe:       SInt      = new SInt:
          private[zaozi] val _width = constOp.operation.getResult(0).getType.getBitWidth(true).toInt
        val _operation: Operation = constOp.operation
    def S(
      using Arena,
      Context,
      Block
    ): Const[SInt] =
      // MSB for sign
      bigInt.S(bigInt.bitLength + 1)
  extension (bool:   Boolean)
    def B(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line
    ): Const[Bool] =
      val constOp = summon[ConstantApi].op(
        input = if (bool) 1 else 0,
        width = 1,
        signed = false,
        location = locate
      )
      constOp.operation.appendToBlock()
      new Const[Bool]:
        val _tpe:       Bool      = new Object with Bool
        val _operation: Operation = constOp.operation
end given
