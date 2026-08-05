// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.zaozi.default

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.reftpe.{Interface, Referable, Writable}
import me.jiuyang.zaozi.valuetpe.*

import org.llvm.mlir.scalalib.capi.ir.{Block, Context, given}

import java.lang.foreign.Arena

private def bulkShape(x: Interface[?] | Referable[?]): Data = x match
  case iface: Interface[?] => iface.getType
  case r:     Referable[?] => r.getType

private def bulkFieldRef(
  x:    Interface[?] | Referable[?],
  idx:  Int,
  name: String
)(
  using Arena,
  Block,
  Context,
  TypeImpl,
  sourcecode.File,
  sourcecode.Line,
  sourcecode.Name.Machine
): Referable[Data] = x match
  case iface: Interface[?] => iface.portRef(idx)
  case r:     Referable[?] => r.subRef(name)

private[zaozi] def bulkConnect(
  sink: Interface[?] | Referable[?],
  src:  Interface[?] | Referable[?],
  dir:  ConnDir
)(
  using Arena,
  Context,
  Block,
  TypeImpl,
  sourcecode.File,
  sourcecode.Line,
  sourcecode.Name.Machine
): Unit =
  val opName  = dir match
    case ConnDir.Bi      => ":<>="
    case ConnDir.Aligned => ":<="
    case ConnDir.Flipped => ":>="
  val sinkTpe = bulkShape(sink)
  val srcTpe  = bulkShape(src)
  val fields  = sinkTpe.asInstanceOf[Aggregate].elements.zipWithIndex

  def effectiveDir(isFlipped: Boolean): ConnDir =
    if !isFlipped then dir
    else
      dir match
        case ConnDir.Bi      => ConnDir.Bi
        case ConnDir.Aligned => ConnDir.Flipped
        case ConnDir.Flipped => ConnDir.Aligned

  checkConnectable(sinkTpe, srcTpe, opName)

  fields.foreach: (f, idx) =>
    val sinkF = bulkFieldRef(sink, idx, f.name)
    val srcF  = bulkFieldRef(src, idx, f.name)
    if f.isFlipped then connect(srcF, sinkF, effectiveDir(true))
    else connect(sinkF, srcF, dir)

extension [T <: HWInterface[?]](sink: Interface[T])
  def :<>=(
    src: Interface[T] | Referable[T]
  )(
    using Arena,
    Context,
    Block,
    TypeImpl,
    sourcecode.File,
    sourcecode.Line,
    sourcecode.Name.Machine
  ): Unit = bulkConnect(sink, src, ConnDir.Bi)
  def :<=(
    src: Interface[T] | Referable[T]
  )(
    using Arena,
    Context,
    Block,
    TypeImpl,
    sourcecode.File,
    sourcecode.Line,
    sourcecode.Name.Machine
  ): Unit = bulkConnect(sink, src, ConnDir.Aligned)
  def :>=(
    src: Interface[T] | Referable[T]
  )(
    using Arena,
    Context,
    Block,
    TypeImpl,
    sourcecode.File,
    sourcecode.Line,
    sourcecode.Name.Machine
  ): Unit = bulkConnect(sink, src, ConnDir.Flipped)
  def dontCare(
  )(
    using Arena,
    Context,
    Block,
    TypeImpl,
    sourcecode.File,
    sourcecode.Line
  ): Unit = interfaceDontCare(sink)

extension [T <: HWInterface[?]](sink: Writable[T])
  def :<>=(
    src: Interface[T]
  )(
    using Arena,
    Context,
    Block,
    TypeImpl,
    sourcecode.File,
    sourcecode.Line,
    sourcecode.Name.Machine
  ): Unit = bulkConnect(sink, src, ConnDir.Bi)
  def :<=(
    src: Interface[T]
  )(
    using Arena,
    Context,
    Block,
    TypeImpl,
    sourcecode.File,
    sourcecode.Line,
    sourcecode.Name.Machine
  ): Unit = bulkConnect(sink, src, ConnDir.Aligned)

extension [T <: HWInterface[?]](sink: Referable[T])
  def :>=(
    src: Interface[T]
  )(
    using Arena,
    Context,
    Block,
    TypeImpl,
    sourcecode.File,
    sourcecode.Line,
    sourcecode.Name.Machine
  ): Unit = bulkConnect(sink, src, ConnDir.Flipped)
