// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Yuhang Zeng <unlsycn@unlsycn.com>
package me.jiuyang.omlib

import scala.collection.mutable.LinkedHashMap

import org.llvm.circt.scalalib.capi.dialect.om.{*, given}
import org.llvm.circt.scalalib.capi.dialect.hw.given_AttributeApi
import org.llvm.mlir.scalalib.capi.ir.{*, given}

import scala.scalanative.unsafe.Zone
import upickle.default._

sealed trait OMValue:
  def value: Any

  def objOpt = this match
    case OMObj(value) => Some(value)
    case _            => None
  def obj    = objOpt.get

  def strOpt = this match
    case OMString(value) => Some(value)
    case _               => None
  def str    = strOpt.get

  def intOpt = this match
    case OMInt(value) => Some(value)
    case _            => None
  def int    = intOpt.get

  def boolOpt = this match
    case OMBool(value) => Some(value)
    case _             => None
  def bool    = boolOpt.get

  def refOpt = this match
    case OMRef(value) => Some(value)
    case _            => None
  def ref    = refOpt.get

  def pathOpt = this match
    case OMPath(value) => Some(value)
    case _             => None
  def path    = pathOpt.get

  def listOpt = this match
    case OMList(value) => Some(value)
    case _             => None
  def list    = listOpt.get

  def flatten: Seq[OMValue] = Seq(this) ++ (this match
    case OMObj(value)  => value.flatMap((_, child) => child.flatten).toSeq
    case OMList(value) => value.flatMap(_.flatten).toSeq
    case _             => Seq())

  // Due to https://github.com/com-lihaoyi/upickle/issues/394, we convert OMValue to ujson.Value for serialization
  def toUjson: ujson.Value = this match
    case OMObj(value)    => ujson.Obj.from(value.mapValues(_.toUjson))
    case OMString(value) => ujson.Str(value)
    case OMInt(value)    => ujson.Num(value)
    case OMBool(value)   => ujson.Bool(value)
    case OMList(value)   => ujson.Arr.from(value.map(_.toUjson))
    case OMRef(value)    => ujson.Obj(("module", value._1), ("name", value._2))
    case OMPath(value)   => ujson.Str(value)

end OMValue

given omValueWriter: Writer[OMValue] = writer[ujson.Value].comap(_.toUjson)

case class OMObj(value: LinkedHashMap[String, OMValue]) extends OMValue
case class OMString(value: String)                      extends OMValue
case class OMInt(value: Long)                           extends OMValue
case class OMBool(value: Boolean)                       extends OMValue
case class OMList(value: Array[OMValue])                extends OMValue
case class OMRef(value: Tuple2[String, String])         extends OMValue
case class OMPath(value: String)                        extends OMValue

extension (evaluatorValue: OMEvaluatorValue)
  def toScala(
    using Zone,
    Context
  ): OMValue = evaluatorValue match
    case obj if obj.isObject                =>
      val fieldNames = obj.objectGetFieldNames
      OMObj(
        LinkedHashMap.from(
          Seq
            .tabulate(fieldNames.arrayAttrGetNumElements)(i => fieldNames.arrayAttrGetElement(i).stringAttrGetValue)
            .zipWithIndex
            .map((fieldName, i) => (fieldName, obj.objectGetField(fieldName).toScala))
        )
      )
    case primitive if primitive.isPrimitive =>
      primitive.getPrimitive match
        case string if string.isString       => OMString(string.stringAttrGetValue)
        // match bool before integer since a bool is also an integer
        case bool if bool.isBool             => OMBool(bool.boolAttrGetValue)
        case int if int.isInteger            => OMInt(int.integerAttrGetValueSInt)
        case omInt if omInt.isIntegerAttr    => OMInt(omInt.integerAttrGetInt.integerAttrGetValueSInt)
        // since firtool 1.22.0 there should be no longer list attrs but we still support it for backward compatibility
        case listAttr if listAttr.isListAttr =>
          OMList(
            Array.tabulate(listAttr.listAttrGetNumElements)(i =>
              listAttr.listAttrGetElement(i).toEvaluatorValue.toScala
            )
          )
        case ref if ref.isReferenceAttr      =>
          val innerRef = ref.referenceAttrGetInnerRef
          OMRef((innerRef.innerRefAttrGetModule.stringAttrGetValue, innerRef.innerRefAttrGetName.stringAttrGetValue))
        // TODO: support SymbolRefAttr
        case _                               => throw new Exception(s"Unsupport MLIR Attribute Type")
    case list if list.isList                =>
      OMList(
        Array.tabulate(list.listGetNumElements)(i => list.listGetElement(i).toScala)
      )
    case path if path.isPath                => OMPath(path.pathGetAsString.stringAttrGetValue)
    case basepath if basepath.isBasePath    => throw new Exception(s"not support BasePath")
    case _                                  => throw new Exception(s"Unsupport ObjectModel Evaluator Type")
