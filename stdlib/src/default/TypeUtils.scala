// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Yuhang Zeng <unlsycn@unlsycn.com>
package me.jiuyang.stdlib.default

import me.jiuyang.stdlib.*
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*

import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

import java.lang.foreign.Arena

given TypeUtilsApi with
  extension [D <: HardwareDataType, R <: Referable[D]](ref: R)
    inline def asBits(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Propagated[R, Bits] = (ref.getType match
      case _: Bool   => summon[AsBits[Bool]].asBits(ref.asInstanceOf[Referable[Bool]])
      case _: Bundle => summon[AsBits[Bundle]].asBits(ref.asInstanceOf[Referable[Bundle]])
      case _: Record => summon[AsBits[Record]].asBits(ref.asInstanceOf[Referable[Record]])
      case _: SInt   => summon[AsBits[SInt]].asBits(ref.asInstanceOf[Referable[SInt]])
      case _: UInt   => summon[AsBits[UInt]].asBits(ref.asInstanceOf[Referable[UInt]])
      case _: Vec[?] =>
        summon[AsBits[Vec[Bool]]].asBits(
          // we don't care about the type parameter of Vec here
          ref.asInstanceOf[Referable[Vec[Bool]]]
        )
    ).asInstanceOf[Propagated[R, Bits]]

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
    ): Propagated[R, D] = (tpe match
      case _: Bool   => ref.asBool
      case x: Bundle => ref.asBundle[Bundle](x)
      case x: Record => ref.asRecord[Record](x)
      case _: SInt   => ref.asSInt
      case _: UInt   => ref.asUInt
      case x: Vec[?] => ref.asVec(x.elementType)
    ).asInstanceOf[Propagated[R, D]]

end given
