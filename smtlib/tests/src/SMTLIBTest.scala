// SPDX-License-Identifier: Apache-2.0
package me.jiuyang.smtlib.tests

import scala.scalanative.unsafe.Zone
import org.llvm.mlir.scalalib.capi.ir.{Context, ContextApi, given}

// Only resource lifetime is shared; tests invoke the public solver API directly.
def withSMTContext[T](body: (Zone, Context) ?=> T): T =
  val zone = Zone.open()
  try
    given Zone  = zone
    val context = summon[ContextApi].contextCreate
    try
      body(
        using zone,
        context
      )
    finally context.destroy()
  finally zone.close()
