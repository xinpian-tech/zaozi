// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package org.llvm.circt.scalalib.dialect.hw.attr

import org.llvm.circt.scalalib.dialect.hw.HWEncoding
import org.llvm.mlir.scalalib.capi.ir.{Attribute, Context, Type, given}
import java.lang.foreign.Arena

given AttributeApi with
  def getInteger(
    value: BigInt,
    tpe:   Type
  )(
    using Arena,
    Context
  ): Attribute =
    HWEncoding.attribute(s"$value : ${HWEncoding.show(tpe)}")
  def getParamExpression(
    opcode:   ParamExpression,
    operands: Seq[Attribute]
  )(
    using Arena,
    Context
  ): Attribute =
    HWEncoding.attribute(
      s"#hw.param.expr.${opcode.mnemonic}<${operands.map(HWEncoding.untyped).mkString(", ")}> : ${HWEncoding.show(operands.head.getType)}"
    )
  def getParamVerbatim(
    value: String,
    tpe:   Type
  )(
    using Arena,
    Context
  ): Attribute =
    HWEncoding.attribute(s"#hw.param.verbatim<${HWEncoding.quote(value)}> : ${HWEncoding.show(tpe)}")
  def getParamReference(
    name: String,
    tpe:  Type
  )(
    using Arena,
    Context
  ): Attribute =
    HWEncoding.attribute(s"#hw.param.decl.ref<${HWEncoding.quote(name)}> : ${HWEncoding.show(tpe)}")
  def getEnumField(
    name: String,
    tpe:  Type
  )(
    using Arena,
    Context
  ): Attribute =
    HWEncoding.attribute(s"#hw.enum.field<$name, ${HWEncoding.show(tpe)}>")
