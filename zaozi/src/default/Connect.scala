// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.zaozi.default

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.reftpe.{Referable, Writable}
import me.jiuyang.zaozi.valuetpe.*

import org.llvm.circt.scalalib.dialect.firrtl.operation.{ConnectApi, SubfieldApi, SubindexApi, given}
import org.llvm.mlir.scalalib.capi.ir.{Block, Context, Value, given}

import java.lang.foreign.Arena

private[zaozi] enum ConnDir(val connectToConsumer: Boolean, val connectToProducer: Boolean):
  case Bi      extends ConnDir(true, true)
  case Aligned extends ConnDir(true, false)
  case Flipped extends ConnDir(false, true)

/** The one reason analog is refused, shared by the `:=` fast path and the analyze walk. */
private[zaozi] val analogNotConnectable = "cannot connect an Analog; FIRRTL connects analog via attach only"

private def fieldsOf(d: Data): Option[Seq[BundleField[?]]] = d match
  case b: Bundle => Some(b.elements)
  case r: Record => Some(r.elements)
  case _ => None

private def kindName(d: Data): String = d match
  case _: Bool   => "Bool"
  case _: UInt   => "UInt"
  case _: SInt   => "SInt"
  case _: Clock  => "Clock"
  case _: Reset  => "Reset"
  case _: Bits   => "Bits"
  case _: Analog => "Analog"
  case _: Vec[?] => "Vec"
  case _: Bundle => "Bundle"
  case _: Record => "Record"
  case other => other.getClass.getSimpleName

private def isGround(d: Data): Boolean = d match
  case _: Analog  => false // attach-only in FIRRTL
  case _: Element => true
  case _ => false

private def atRoot(path:    String):               String = if path.isEmpty then "(root)" else path
private def childPath(path: String, name: String): String = if path.isEmpty then name else s"$path.$name"

private[zaozi] def analyzeConnectable(
  sinkTpe: Data,
  srcTpe:  Data,
  path:    String
)(
  using Arena,
  Context,
  TypeImpl
): List[String] =
  (sinkTpe, srcTpe) match
    case (s: Vec[?], p: Vec[?]) =>
      val lenErrs =
        if s.count != p.count then List(s"${atRoot(path)}: vec length mismatch ${s.count} vs ${p.count}")
        else Nil
      lenErrs ++ analyzeConnectable(s.elementType, p.elementType, s"$path[*]")
    case (s, p)                 =>
      (fieldsOf(s), fieldsOf(p)) match
        case (Some(se), Some(pe))                       =>
          def dups(es: Seq[BundleField[?]]): Seq[String] =
            es.groupBy(_.name).collect { case (n, xs) if xs.size > 1 => n }.toSeq
          val sd = dups(se)
          val pd        = dups(pe)
          val dupErrs   =
            (if sd.nonEmpty then List(s"${atRoot(path)}: duplicate sink field name(s) ${sd.mkString(",")}")
             else Nil)
              ++ (if pd.nonEmpty then List(s"${atRoot(path)}: duplicate producer field name(s) ${pd.mkString(",")}")
                  else Nil)
          val sn        = se.map(_.name)
          val pn        = pe.map(_.name)
          val shapeErrs =
            if sn.toSet != pn.toSet then
              val missingInProducer = sn.filterNot(pn.toSet)
              val missingInSink     = pn.filterNot(sn.toSet)
              (if missingInProducer.nonEmpty then
                 List(s"${atRoot(path)}: field(s) missing in producer: ${missingInProducer.mkString(",")}")
               else Nil)
                ++ (if missingInSink.nonEmpty then
                      List(s"${atRoot(path)}: field(s) missing in sink: ${missingInSink.mkString(",")}")
                    else Nil)
            else if sn != pn then
              List(
                s"${atRoot(path)}: field order mismatch (sink [${sn.mkString(",")}] vs producer [${pn.mkString(",")}])"
              )
            else Nil
          val dupSet    = (sd ++ pd).toSet
          val pByName   = pe.map(f => f.name -> f).toMap
          val childErrs = se
            .filter(f => !dupSet(f.name) && pByName.contains(f.name))
            .toList
            .flatMap: f =>
              val g = pByName(f.name)
              if f.isFlipped != g.isFlipped then List(s"${childPath(path, f.name)}: orientation mismatch")
              else analyzeConnectable(f.dataType, g.dataType, childPath(path, f.name))
          dupErrs ++ shapeErrs ++ childErrs
        case (None, None) if isGround(s) && isGround(p) =>
          if kindName(s) != kindName(p) then List(s"${atRoot(path)}: kind mismatch ${kindName(s)} vs ${kindName(p)}")
          else if s.width != p.width then List(s"${atRoot(path)}: width mismatch ${s.width} vs ${p.width}")
          else Nil
        case _                                          =>
          def isProbe(d: Data):  Boolean = d match
            case _: RProbe[?] | _: RWProbe[?] | _: ProbeBundle | _: ProbeRecord => true
            case _                                                              => false
          def isAnalog(d: Data): Boolean = d match
            case _: Analog => true
            case _ => false
          if isProbe(s) || isProbe(p) then
            List(s"${atRoot(path)}: probe types do not participate in bulk connects; use <== instead")
          else if isAnalog(s) || isAnalog(p) then List(s"${atRoot(path)}: $analogNotConnectable")
          else List(s"${atRoot(path)}: incompatible or unsupported types (${kindName(s)} vs ${kindName(p)})")

private[zaozi] def isPassive(
  d: Data
)(
  using TypeImpl
): Boolean = d match
  case _: Element => true
  case v: Vec[?]  => isPassive(v.elementType)
  case d =>
    fieldsOf(d) match
      case Some(fields) => fields.forall(f => !f.isFlipped && isPassive(f.dataType))
      case None         => true

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
  extension [SINK <: Writable[A]](sink:  SINK)
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
    def :<=[SRC <: Referable[A]](
      src: SRC
    )(
      using Arena,
      Context,
      Block,
      TypeImpl,
      sourcecode.File,
      sourcecode.Line
    ): Unit = connect(sink, src, ConnDir.Aligned)
  extension [SINK <: Referable[A]](sink: SINK)
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
    case t if isPassive(t) =>
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
          throw ConnectException(s"internal error: unsupported leaf survived analyze (${kindName(d)})")

  val sinkTpe = sink.getType
  val srcTpe  = src.getType
  val opName  = dir match
    case ConnDir.Bi      => ":<>="
    case ConnDir.Aligned => ":<="
    case ConnDir.Flipped => ":>="
  val errors  = analyzeConnectable(sinkTpe, srcTpe, "")
  if errors.nonEmpty then
    throw ConnectException(
      (s"$opName failed with ${errors.size} error(s) at ${summon[sourcecode.File].value}:${summon[sourcecode.Line].value}"
        :: errors.map(e => s"  - $e")).mkString("\n")
    )
  if dir == ConnDir.Bi && sinkTpe.toMlirType.equal(srcTpe.toMlirType) then
    // Expected after successful analysis; the structural walk is the safety fallback.
    rawConnect(sink.refer, src.refer)
  else emit(sink.refer, src.refer, sinkTpe, false)
