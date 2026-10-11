// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package org.llvm.circt.scalalib.dialect.hw.tpe

import org.llvm.circt.scalalib.dialect.hw.HWEncoding
import org.llvm.mlir.scalalib.capi.ir.{Attribute, Context, Type, given}
import org.llvm.circt.scalalib.capi.dialect.hw.{given_TypeApi as HWType, HWModulePort}
import java.lang.foreign.Arena

given TypeApi with
  def getArray(
    element: Type,
    size:    Attribute
  )(
    using Arena,
    Context
  ): Type =
    if size.isInteger then HWType.arrayTypeGet(element, size.integerAttrGetValueInt.toInt)
    else HWEncoding.tpe(s"!hw.array<${HWEncoding.untyped(size)}x${HWEncoding.show(element)}>")
  def getUnpackedArray(
    element: Type,
    size:    Attribute
  )(
    using Arena,
    Context
  ): Type =
    HWEncoding.tpe(s"!hw.uarray<${HWEncoding.untyped(size)}x${HWEncoding.show(element)}>")
  def getString(
    using Arena,
    Context
  ): Type = HWEncoding.tpe("!hw.string")
  def getEnum(
    fields: Seq[String]
  )(
    using Arena,
    Context
  ): Type =
    HWEncoding.tpe(s"!hw.enum<${fields.mkString(", ")}>")
  def getUnpackedArray(
    element: Type,
    size:    Int
  )(
    using Arena,
    Context
  ): Type =
    HWEncoding.tpe(s"!hw.uarray<${size}x${HWEncoding.show(element)}>")
  def getModule(
    ports:       Seq[ModulePort]
  )(
    using arena: Arena,
    context:     Context
  ): Type =
    val nativePorts = ports.map: port =>
      val native = org.llvm.circt.HWModulePort.allocate(arena)
      org.llvm.circt.HWModulePort.name(native, port.name.stringAttrGet.segment)
      org.llvm.circt.HWModulePort.`type`(native, port.tpe.segment)
      org.llvm.circt.HWModulePort.dir(native, port.direction.ordinal)
      HWModulePort(native)
    HWType.moduleTypeGet(nativePorts.size, nativePorts)
  def getStruct(
    fields:      Seq[(String, Type)]
  )(
    using arena: Arena,
    context:     Context
  ): Type =
    import org.llvm.circt.scalalib.capi.dialect.hw.{HWStructFieldInfo, given_HWStructFieldInfoApi}
    val native = fields.map: (name, tpe) =>
      val field = org.llvm.circt.HWStructFieldInfo.allocate(arena)
      org.llvm.circt.HWStructFieldInfo.name(field, name.identifierGet.segment)
      org.llvm.circt.HWStructFieldInfo.`type`(field, tpe.segment)
      HWStructFieldInfo(field)
    HWType.structTypeGet(native.size, native)
  def getUnion(
    fields: Seq[(String, Type)]
  )(
    using Arena,
    Context
  ): Type =
    HWEncoding.tpe("!hw.union<" + fields.map((name, tpe) => s"$name: ${HWEncoding.show(tpe)}").mkString(", ") + ">")
