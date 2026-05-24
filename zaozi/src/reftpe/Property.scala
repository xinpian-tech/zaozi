// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <clo91eaf@qq.com>
package me.jiuyang.zaozi.reftpe

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.valuetpe.*
import me.jiuyang.zaozi.default.given_TypeImpl
import me.jiuyang.zaozi.magic.macros.{referableApplyDynamic, referableApplyDynamicNamed, referableSelectDynamic}
import org.llvm.circt.scalalib.dialect.firrtl.operation.Module as CirctModule
import org.llvm.mlir.scalalib.capi.ir.{Block, Context, Operation, Type, Value}

import java.lang.foreign.Arena

trait Property extends LTLTPE:
  def operation(
    using TypeImpl
  ): Operation = this.operationImpl

  def refer(
    using Arena,
    TypeImpl
  ): Value = this.referImpl

  def toMlirType(
    using Arena,
    Context,
    TypeImpl
  ): Type = this.toMlirTypeImpl
