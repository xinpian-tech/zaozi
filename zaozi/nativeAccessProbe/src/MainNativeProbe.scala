// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.zaozi.probe

import org.llvm.mlir.scalalib.capi.ir.{given_ContextApi, Context, ContextApi}

import java.lang.foreign.Arena

// Native-access probe. The assembly is built with the JEP 454
// `Enable-Native-Access: ALL-UNNAMED` JAR manifest attribute; running it via
// `java --illegal-native-access=deny -jar ...` exits 0 only because the
// manifest opts the unnamed module into native access. Stripping the
// manifest attribute reproduces an `IllegalCallerException` at FFM-class
// initialization — that is the load-bearing negative check.
//
// The body performs one trivial FFM round-trip (create + destroy an MLIR
// context inside a confined arena) so the JVM actually exercises FFM rather
// than short-circuiting before any native call.
object MainNativeProbe:
  def main(args: Array[String]): Unit =
    val arena = Arena.ofConfined()
    try
      given Arena = arena
      val context = summon[ContextApi].contextCreate
      // Destroy the context before the arena closes so the FFM round-trip
      // covers both `mlirContextCreate` AND `mlirContextDestroy`; this also
      // releases the native context-side state rather than leaking it on
      // every probe invocation.
      context.destroy()
      // Print a deterministic line so the caller can grep success.
      println("zaozi.nativeAccessProbe: FFM round-trip OK")
    finally arena.close()
