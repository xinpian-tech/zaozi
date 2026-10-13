// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.zaozi.sv.reftpe

import me.jiuyang.zaozi.sv.valuetpe.{Bits, Data}
import me.jiuyang.zaozi.sv.{AggregateApi, TypeImpl}
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}
import java.lang.foreign.Arena
import scala.language.dynamics

abstract class Node[T <: Data] extends ValueReferable[T], Dynamic:
  transparent inline def selectDynamic(
    inline name: String
  )(
    using arena: Arena,
    context:     Context,
    block:       Block,
    impl:        TypeImpl,
    api:         AggregateApi,
    file:        sourcecode.File,
    line:        sourcecode.Line
  ): Any =
    ${
      me.jiuyang.zaozi.sv.magic
        .aggregateSelect('this, 'name, false)('arena, 'context, 'block, 'impl, 'api, 'file, 'line)
    }
  transparent inline def apply(
    index:       Referable[Bits]
  )(
    using arena: Arena,
    context:     Context,
    block:       Block,
    impl:        TypeImpl,
    api:         AggregateApi,
    file:        sourcecode.File,
    line:        sourcecode.Line
  ): Any =
    ${
      me.jiuyang.zaozi.sv.magic
        .aggregateIndex('this, 'index, false)('arena, 'context, 'block, 'impl, 'api, 'file, 'line)
    }

  transparent inline def applyDynamic(
    inline name: String
  )(index:       Referable[Bits]
  )(
    using arena: Arena,
    context:     Context,
    block:       Block,
    impl:        TypeImpl,
    api:         AggregateApi,
    file:        sourcecode.File,
    line:        sourcecode.Line
  ): Any =
    ${
      me.jiuyang.zaozi.sv.magic
        .aggregateApply('this, 'name, 'index, false)('arena, 'context, 'block, 'impl, 'api, 'file, 'line)
    }
