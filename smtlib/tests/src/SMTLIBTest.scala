// SPDX-License-Identifier: Apache-2.0
package me.jiuyang.smtlib.tests

import java.lang.foreign.Arena
import org.llvm.mlir.scalalib.capi.ir.{Context, ContextApi, given}

// Only resource lifetime is shared; tests invoke the public solver API directly.
def withSMTContext[T](body: (Arena, Context) ?=> T): T =
  val arena = Arena.ofConfined()
  try
    given Arena = arena
    val context = summon[ContextApi].contextCreate
    try
      body(
        using arena,
        context
      )
    finally context.destroy()
  finally arena.close()
