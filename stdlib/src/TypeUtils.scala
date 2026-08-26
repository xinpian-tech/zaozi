// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Yuhang Zeng <unlsycn@unlsycn.com>
package me.jiuyang.stdlib

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*

import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

import java.lang.foreign.Arena

private type _BaseHardwareDataType = Bits | Bool | Bundle | Record | SInt | UInt
// there is no recursive type in Scala so ...
type HardwareDataType              = _BaseHardwareDataType | Vec[_BaseHardwareDataType] | Vec[Vec[_BaseHardwareDataType]] |
  Vec[Vec[Vec[_BaseHardwareDataType]]]

trait TypeUtilsApi:
  extension [D <: HardwareDataType, R <: Referable[D]](ref: R)
    inline def asBits(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Propagated[R, Bits]

  extension [R <: Referable[Bits]](ref: R)
    inline def asType[D <: HardwareDataType](
      tpe: D
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Propagated[R, D]
