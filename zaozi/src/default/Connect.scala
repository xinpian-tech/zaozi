// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.zaozi.default

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.reftpe.{Referable, Writable}
import me.jiuyang.zaozi.valuetpe.*

import org.llvm.circt.scalalib.dialect.firrtl.operation.{ConnectApi, SubfieldApi, SubindexApi, given}
import org.llvm.mlir.scalalib.capi.ir.{Block, Context, Value, given}

import java.lang.foreign.Arena

private enum ConnDir(val connectToConsumer: Boolean, val connectToProducer: Boolean):
  case Bi      extends ConnDir(true, true)
  case Aligned extends ConnDir(true, false)
  case Flipped extends ConnDir(false, true)

/** The one reason analog is refused, shared by the `:=` fast path and the analyze walk. */
private val analogNotConnectable = "cannot connect an Analog; FIRRTL connects analog via attach only"

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
      summon[ConnectApi]
        .op(
          src.refer,
          sink.refer,
          locate
        )
        .operation
        .appendToBlock()
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
private def connect(
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
  def fieldsOf(d: Data): Option[Seq[BundleField[?]]] = d match
    case b: Bundle => Some(b.elements)
    case r: Record => Some(r.elements)
    case _ => None

  def kindName(d: Data): String = d match
    case _: Bool   => "Bool"
    case _: UInt   => "UInt"
    case _: SInt   => "SInt"
    case _: Clock  => "Clock"
    case r: Reset  => if r._isAsync then "AsyncReset" else "Reset"
    case _: Bits   => "Bits"
    case _: Analog => "Analog"
    case _: Vec[?] => "Vec"
    case _: Bundle => "Bundle"
    case _: Record => "Record"
    case other => other.getClass.getSimpleName

  def isGround(d: Data): Boolean = d match
    case _: Analog  => false // attach-only in FIRRTL
    case _: Element => true
    case _ => false

  def atRoot(path:    String):               String = if path.isEmpty then "(root)" else path
  def childPath(path: String, name: String): String = if path.isEmpty then name else s"$path.$name"

  def analyze(sinkTpe: Data, srcTpe: Data, path: String): List[String] =
    (sinkTpe, srcTpe) match
      case (s: Vec[?], p: Vec[?]) =>
        val lenErrs =
          if s.count != p.count then List(s"${atRoot(path)}: vec length mismatch ${s.count} vs ${p.count}")
          else Nil
        lenErrs ++ analyze(s.elementType, p.elementType, s"$path[*]")
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
                else analyze(f.dataType, g.dataType, childPath(path, f.name))
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

  def isPassive(d: Data): Boolean = d match
    case _: Element => true
    case v: Vec[?]  => isPassive(v.elementType)
    case d =>
      fieldsOf(d) match
        case Some(fields) => fields.forall(f => !f.isFlipped && isPassive(f.dataType))
        case None         => true

  def hasSelectedLeaf(tpe: Data, net: Boolean): Boolean = tpe match
    case _: Element => if net then dir.connectToProducer else dir.connectToConsumer
    case v: Vec[?]  => v.count > 0 && hasSelectedLeaf(v.elementType, net)
    case d =>
      fieldsOf(d) match
        case Some(fields) => fields.exists(f => hasSelectedLeaf(f.dataType, net ^ f.isFlipped))
        case None         => false

  def raw(dest: Value, source: Value): Unit =
    summon[ConnectApi]
      .op(
        source,
        dest,
        locate
      )
      .operation
      .appendToBlock()

  def subfield(v: Value, i: Int): Value =
    val op = summon[SubfieldApi].op(v, i, locate)
    op.operation.appendToBlock()
    op.operation.getResult(0)

  def subindex(v: Value, i: Int): Value =
    val op = summon[SubindexApi].op(v, i, locate)
    op.operation.appendToBlock()
    op.operation.getResult(0)

  def emit(sv: Value, pv: Value, tpe: Data, net: Boolean): Unit = tpe match
    case t if isPassive(t) =>
      if hasSelectedLeaf(t, net) then if net then raw(pv, sv) else raw(sv, pv)
    case v: Vec[?] =>
      if hasSelectedLeaf(v.elementType, net) then
        (0 until v.count).foreach: i =>
          emit(subindex(sv, i), subindex(pv, i), v.elementType, net)
    case d =>
      fieldsOf(d) match
        case Some(fields) =>
          fields.zipWithIndex.foreach: (f, i) =>
            val childNet = net ^ f.isFlipped
            if hasSelectedLeaf(f.dataType, childNet) then emit(subfield(sv, i), subfield(pv, i), f.dataType, childNet)
        case None         =>
          throw ConnectException(s"internal error: unsupported leaf survived analyze (${kindName(d)})")

  val sinkTpe = sink.getType
  val srcTpe  = src.getType
  val errors  = analyze(sinkTpe, srcTpe, "")
  if errors.nonEmpty then
    val op = dir match
      case ConnDir.Bi      => ":<>="
      case ConnDir.Aligned => ":<="
      case ConnDir.Flipped => ":>="
    throw ConnectException(
      (s"$op failed with ${errors.size} error(s) at ${summon[sourcecode.File].value}:${summon[sourcecode.Line].value}"
        :: errors.map(e => s"  - $e")).mkString("\n")
    )
  if dir == ConnDir.Bi && sinkTpe.toMlirType.equal(srcTpe.toMlirType) then
    // Expected after successful analysis; the structural walk is the safety fallback.
    raw(sink.refer, src.refer)
  else emit(sink.refer, src.refer, sinkTpe, false)
