// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.zaozitest

import java.lang.foreign.Arena
import utest.*

// Exception-injection regression guard for the `try/finally arena.close()`
// pattern used by every test/benchmark entry point that allocates a confined
// arena. Removing the `finally arena.close()` line must make these cases
// fail.
object ArenaLifetimeSpec extends TestSuite:

  private def withConfinedArena[A](body: Arena => A): A =
    val arena = Arena.ofConfined()
    try body(arena)
    finally arena.close()

  val tests: Tests = Tests:
    test("arena closes when body returns normally"):
      var capturedArena: Arena = null
      withConfinedArena: arena =>
        capturedArena = arena
        arena.allocate(8L) // proves arena is open
        ()
      // After return, the arena MUST be closed. Calling any allocator on a
      // closed confined Arena throws IllegalStateException.
      val closed =
        try
          capturedArena.allocate(8L)
          false
        catch case _: IllegalStateException => true
      closed ==> true

    test("arena closes even when body throws"):
      var capturedArena: Arena = null
      val expected = new RuntimeException("synthetic")
      val caught   =
        try
          withConfinedArena: arena =>
            capturedArena = arena
            arena.allocate(8L)
            throw expected
          null: Throwable
        catch
          case t: Throwable => t
      // The injected exception must propagate to the caller — try/finally
      // does not swallow it.
      (caught eq expected) ==> true
      // The arena must still have been closed by the `finally` block.
      val closed   =
        try
          capturedArena.allocate(8L)
          false
        catch case _: IllegalStateException => true
      closed ==> true
