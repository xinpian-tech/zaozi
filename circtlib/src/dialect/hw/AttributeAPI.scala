// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package org.llvm.circt.scalalib.dialect.hw.attr

import org.llvm.mlir.scalalib.capi.ir.{Attribute, Context, Type}
import java.lang.foreign.Arena

enum ParamExpression(val mnemonic: String):
  case Add       extends ParamExpression("add")
  case Mul       extends ParamExpression("mul")
  case And       extends ParamExpression("and")
  case Or        extends ParamExpression("or")
  case Xor       extends ParamExpression("xor")
  case Shl       extends ParamExpression("shl")
  case ShrU      extends ParamExpression("shru")
  case ShrS      extends ParamExpression("shrs")
  case DivU      extends ParamExpression("divu")
  case DivS      extends ParamExpression("divs")
  case ModU      extends ParamExpression("modu")
  case ModS      extends ParamExpression("mods")
  case CLog2     extends ParamExpression("clog2")
  case StrConcat extends ParamExpression("str.concat")

trait AttributeApi:
  def getInteger(
    value: BigInt,
    tpe:   Type
  )(
    using Arena,
    Context
  ): Attribute
  def getParamExpression(
    opcode:   ParamExpression,
    operands: Seq[Attribute]
  )(
    using Arena,
    Context
  ): Attribute
  def getParamVerbatim(
    value: String,
    tpe:   Type
  )(
    using Arena,
    Context
  ): Attribute
  def getParamReference(
    name: String,
    tpe:  Type
  )(
    using Arena,
    Context
  ): Attribute
  def getEnumField(
    name: String,
    tpe:  Type
  )(
    using Arena,
    Context
  ): Attribute
