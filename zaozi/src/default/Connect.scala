// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.zaozi.default

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.reftpe.{Interface, Readable, Referable, Writable}
import me.jiuyang.zaozi.valuetpe.*

import org.llvm.circt.scalalib.capi.dialect.firrtl.given
import org.llvm.circt.scalalib.dialect.firrtl.operation.{ConnectApi, SubfieldApi, SubindexApi, given}
import org.llvm.mlir.scalalib.capi.ir.{Block, Context, Value, given}

import java.lang.foreign.Arena

private[zaozi] enum ConnDir(val connectToConsumer: Boolean, val connectToProducer: Boolean):
  case Bi      extends ConnDir(true, true)
  case Aligned extends ConnDir(true, false)
  case Flipped extends ConnDir(false, true)

/** The one reason analog is refused, shared by the `:=` fast path and `checkConnectable`. */
private[zaozi] val analogNotConnectable = "cannot connect an Analog; FIRRTL connects analog via attach only"

private def fieldsOf(d: Data): Option[Seq[BundleField[?]]] = d match
  case b: Bundle => Some(b.elements)
  case r: Record => Some(r.elements)
  case _ => None

private[zaozi] def checkConnectable(
  sink:   Data,
  src:    Data,
  opName: String
)(
  using Arena,
  Context,
  TypeImpl,
  sourcecode.File,
  sourcecode.Line
): Unit =
  def isProbe(d: Data):  Boolean = d match
    case _: RProbe[?] | _: RWProbe[?] | _: ProbeBundle | _: ProbeRecord => true
    case _                                                              => false
  def isAnalog(d: Data): Boolean = d match
    case _: Analog => true
    case _ => false
  if isProbe(sink) || isProbe(src) then
    throw ConnectException("probe types do not participate in bulk connects; use <== instead")
  if isAnalog(sink) || isAnalog(src) then throw ConnectException(analogNotConnectable)
  val sinkTpe = sink.toMlirType
  val srcTpe = src.toMlirType
  if !sinkTpe.isEquivalentTo(srcTpe, requireSameWidths = true) then
    val message = StringBuilder(
      s"$opName failed at ${summon[sourcecode.File].value}:${summon[sourcecode.Line].value}: type mismatch between "
    )
    sinkTpe.print(message ++= _)
    message ++= " and "
    srcTpe.print(message ++= _)
    throw ConnectException(message.toString)

private[zaozi] def rawConnect(
  dest:   Value,
  source: Value
)(
  using Arena,
  Context,
  Block,
  sourcecode.File,
  sourcecode.Line
): Unit =
  val connectOp = summon[ConnectApi].op(source, dest, locate).operation
  connectOp.appendToBlock()

private[zaozi] def subfieldValue(
  v: Value,
  i: Int
)(
  using Arena,
  Context,
  Block,
  sourcecode.File,
  sourcecode.Line
): Value =
  val op = summon[SubfieldApi].op(v, i, locate)
  op.operation.appendToBlock()
  op.operation.getResult(0)

private[zaozi] def subindexValue(
  v: Value,
  i: Int
)(
  using Arena,
  Context,
  Block,
  sourcecode.File,
  sourcecode.Line
): Value =
  val op = summon[SubindexApi].op(v, i, locate)
  op.operation.appendToBlock()
  op.operation.getResult(0)

given [A <: Connectable]: Connect[A] with
  extension [SINK <: Referable[A] & Writable[A]](sink: SINK)
    def :=[SRC <: Referable[A]](
      src: SRC
    )(
      using A <:< Element,
      Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line
    ): Unit =
      sink.getType match
        case _: Analog => throw ConnectException(analogNotConnectable)
        case _ => ()
      rawConnect(sink.refer, src.refer)
  extension [SINK <: Writable[A]](sink:                SINK)
    def :<>=[SRC <: Writable[A]](
      src: SRC
    )(
      using Arena,
      Context,
      Block,
      TypeImpl,
      sourcecode.File,
      sourcecode.Line
    ): Unit = connect(sink, src, ConnDir.Bi)
    def :<=[SRC <: Readable[A]](
      src: SRC
    )(
      using Arena,
      Context,
      Block,
      TypeImpl,
      sourcecode.File,
      sourcecode.Line
    ): Unit = connect(sink, src, ConnDir.Aligned)
  extension [SINK <: Readable[A]](sink:                SINK)
    def :>=[SRC <: Writable[A]](
      src: SRC
    )(
      using Arena,
      Context,
      Block,
      TypeImpl,
      sourcecode.File,
      sourcecode.Line
    ): Unit = connect(sink, src, ConnDir.Flipped)

/** Validate before emission so failed connects leave no IR. Half-connects emit maximal selected passive subtrees;
  * within such a subtree all leaves share one accumulated flip.
  */
private[zaozi] def connect(
  sink: Readable[?],
  src:  Readable[?],
  dir:  ConnDir
)(
  using Arena,
  Context,
  Block,
  TypeImpl,
  sourcecode.File,
  sourcecode.Line
): Unit = (sink, src) match
  case (sink: Referable[?], src: Referable[?]) => connectReferable(sink, src, dir)
  case _                                       => bulkConnect(sink, src, dir)

private def connectReferable(
  sink: Referable[?],
  src:  Referable[?],
  dir:  ConnDir
)(
  using Arena,
  Context,
  Block,
  TypeImpl,
  sourcecode.File,
  sourcecode.Line
): Unit =
  def hasSelectedLeaf(tpe: Data, net: Boolean): Boolean = tpe match
    case _: Element => if net then dir.connectToProducer else dir.connectToConsumer
    case v: Vec[?]  => v.count > 0 && hasSelectedLeaf(v.elementType, net)
    case d =>
      fieldsOf(d) match
        case Some(fields) => fields.exists(f => hasSelectedLeaf(f.dataType, net ^ f.isFlipped))
        case None         => false

  def emit(sv: Value, pv: Value, tpe: Data, net: Boolean): Unit = tpe match
    case t if t.toMlirType.isPassive =>
      if hasSelectedLeaf(t, net) then if net then rawConnect(pv, sv) else rawConnect(sv, pv)
    case v: Vec[?] =>
      if hasSelectedLeaf(v.elementType, net) then
        (0 until v.count).foreach: i =>
          emit(subindexValue(sv, i), subindexValue(pv, i), v.elementType, net)
    case d =>
      fieldsOf(d) match
        case Some(fields) =>
          fields.zipWithIndex.foreach: (f, i) =>
            val childNet = net ^ f.isFlipped
            if hasSelectedLeaf(f.dataType, childNet) then
              emit(subfieldValue(sv, i), subfieldValue(pv, i), f.dataType, childNet)
        case None         =>
          throw ConnectException(s"internal error: unsupported leaf survived analyze (${d.getClass.getSimpleName})")

  val sinkTpe = sink.getType
  val srcTpe  = src.getType
  val opName  = dir match
    case ConnDir.Bi      => ":<>="
    case ConnDir.Aligned => ":<="
    case ConnDir.Flipped => ":>="
  checkConnectable(sinkTpe, srcTpe, opName)
  if dir == ConnDir.Bi then rawConnect(sink.refer, src.refer)
  else emit(sink.refer, src.refer, sinkTpe, false)
