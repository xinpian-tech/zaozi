// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.syntheke.tests.axi

import utest.*

/** The [[AddressSet]] base/mask algebra and [[TransferSizes]], mirroring diplomacy's semantics. */
object Axi4Spec extends TestSuite:

  val tests = Tests {

    test("membership: base fixes the bits outside the mask, mask bits vary freely") {
      val s       = AddressSet(0x1000L, 0xffL)
      assert(s.contains(0x1000L), s.contains(0x10ffL), !s.contains(0x1100L), !s.contains(0xfffL))
      assert(s.contiguous, s.alignment == 0x100L, s.max == 0x10ffL)
      // A non-contiguous mask selects a lattice, not a range; alignment measures the mask's contiguous low run.
      val lattice = AddressSet(0x0L, 0x1010L)
      assert(lattice.contains(0x1010L), lattice.contains(0x10L), !lattice.contains(0x8L))
      assert(!lattice.contiguous, lattice.alignment == 0x1L, lattice.max == 0x1010L)
      intercept[IllegalArgumentException](AddressSet(0x1008L, 0xfL)) // base not aligned to mask
    }

    test("set relations: contains, overlaps, intersect") {
      val big   = AddressSet(0x1000L, 0xfffL)
      val small = AddressSet(0x1200L, 0xffL)
      assert(big.contains(small), !small.contains(big), big.overlaps(small))
      assert(small.intersect(big).contains(small))
      assert(AddressSet(0x0L, 0xffL).intersect(AddressSet(0x200L, 0xffL)).isEmpty)
      // Two lattices intersect where both constraints hold.
      assert(AddressSet(0x0L, 0xff0L).intersect(AddressSet(0x0L, 0xf0fL)).contains(AddressSet(0x0L, 0xf00L)))
    }

    test("subtract carves the remainder into one set per freed bit") {
      val hole = AddressSet(0x0L, 0xffL)
      assert(
        AddressSet(0x0L, 0xfffL).subtract(hole) == Vector(
          AddressSet(0x100L, 0xeffL),
          AddressSet(0x200L, 0xdffL),
          AddressSet(0x400L, 0xbffL),
          AddressSet(0x800L, 0x7ffL)
        )
      )
      assert(AddressSet(0x0L, 0xffL).subtract(AddressSet(0x200L, 0xffL)) == Vector(AddressSet(0x0L, 0xffL)))
    }

    test("widen frees bits; unify merges buddies and drops contained sets") {
      assert(AddressSet(0x1000L, 0xffL).widen(0xf00L) == AddressSet(0x1000L, 0xfffL))
      assert(
        AddressSet.unify(Vector(AddressSet(0x0L, 0xffL), AddressSet(0x100L, 0xffL))) == Vector(AddressSet(0x0L, 0x1ffL))
      )
      assert(
        AddressSet.unify(Vector(AddressSet(0x0L, 0xfffL), AddressSet(0x100L, 0xffL))) == Vector(
          AddressSet(0x0L, 0xfffL)
        )
      )
      // Buddy merging cascades: four quarters reunite pairwise into the whole.
      val quarters = Vector(0x0L, 0x100L, 0x200L, 0x300L).map(AddressSet(_, 0xffL))
      assert(AddressSet.unify(quarters) == Vector(AddressSet(0x0L, 0x3ffL)))
    }

    test("misaligned decomposes [base, base+size) into maximal aligned chunks") {
      assert(AddressSet.misaligned(0x80000000L, 0x80000000L) == Vector(AddressSet(0x80000000L, 0x7fffffffL)))
      assert(
        AddressSet.misaligned(0x10000800L, 0x1000L) == Vector(
          AddressSet(0x10000800L, 0x7ffL),
          AddressSet(0x10001000L, 0x7ffL)
        )
      )
      assert(AddressSet.misaligned(0x0L, 0x3000L) == Vector(AddressSet(0x0L, 0x1fffL), AddressSet(0x2000L, 0xfffL)))
    }

    test("transfer sizes are powers of two with a none element") {
      val t = TransferSizes(4, 64)
      assert(t.contains(4), t.contains(64), !t.contains(2), !t.contains(3), !t.contains(128))
      assert(t.intersect(TransferSizes(1, 8)) == TransferSizes(4, 8))
      assert(t.intersect(TransferSizes(128, 256)).none)
      assert(!TransferSizes.none.contains(1))
      intercept[IllegalArgumentException](TransferSizes(3, 8))
    }
  }
