// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.decodertest

import me.jiuyang.decoder.{*, given}
import utest.*

object BitSetSpec extends TestSuite {
  val tests = Tests {
    test("hashCode agrees with equals") {
      val pattern = BitSet.bitpat("1??")
      val split   = BitSet.bitset("10?|11?")
      val range   = BitSet.range(BigInt(4), BigInt(4), 3)
      assert(pattern == split, pattern.hashCode == split.hashCode)
      assert(pattern == range, pattern.hashCode == range.hashCode)
      assert(BitSet.bitpat("101").hashCode == BitSet.bitpat(BigInt(5), BigInt(7), 3).hashCode)
      assert(BitSet.empty.hashCode == BitSet(Set.empty[BitSet]).hashCode)

      val minterms = (0 until 16).map(i => BitSet.bitpat(BigInt(i), BigInt(15), 4))
      assert((minterms ++ minterms.map(m => BitSet.bitpat(m.value, m.mask, m.width))).toSet.size == 16)
    }

    test("BitSet.bitpat") {
      test("creation") {
        val pat1: BitPat = BitSet.bitpat("101")
        val pat2: BitPat = BitSet.bitpat(BigInt(5), BigInt(7), 3) // 101 with mask 111

        assert(pat1.value == BigInt(5))
        assert(pat1.mask == BigInt(7))
        assert(pat1.width == 3)
        assert(pat1 == pat2)
      }

      test("string representation") {
        val pat = BitSet.bitpat("10?")
        assert(pat.toString == "10?")
      }

      test("don't care bits") {
        val pat = BitSet.bitpat("10?")
        assert(pat.hasDontCares)
        assert(!pat.allZeros)
        assert(!pat.allOnes)
        assert(!pat.allDontCares)

        val allDC = BitSet.bitpat("???")
        assert(allDC.allDontCares)

        val allZeros = BitSet.bitpat("000")
        assert(allZeros.allZeros)

        val allOnes = BitSet.bitpat("111")
        assert(allOnes.allOnes)
      }
    }

    test("BitSet factory methods") {
      test("empty") {
        val empty = BitSet.empty
        assert(empty.isEmpty)
        assert(empty.terms.isEmpty)
      }

      test("Y, N, DC") {
        assert(BitSet.y().toString == "1")
        assert(BitSet.n().toString == "0")
        assert(BitSet.all().toString == "?")

        assert(BitSet.y(3).toString == "111")
        assert(BitSet.n(3).toString == "000")
        assert(BitSet.all(3).toString == "???")
      }

      test("range") {
        // 1x
        val range = BitSet.range(BigInt(2), BigInt(2))
        assert(range.start == BigInt(2))
        assert(range.length == BigInt(2))

        assert(range == BitSet.bitpat("11").union(BitSet.bitpat("10")))
        assert(range == BitSet.bitpat("1?"))
      }
    }

    test("BitSet operations") {
      val a = BitSet.bitpat("101")
      val b = BitSet.bitpat("1?1")
      val c = BitSet.bitpat("100")

      test("overlap") {
        assert(a.overlap(b))
        assert(b.overlap(a))
        assert(!b.overlap(c))
      }

      test("cover") {
        assert(b.cover(a))
        assert(!b.cover(c))
      }

      test("intersect") {
        val ab = a.intersect(b)
        assert(ab.toString == "101")

        val ac = a.intersect(c)
        assert(ac == BitSet.empty)

        val bc = b.intersect(c)
        assert(bc.isEmpty)
      }

      test("union") {
        val ab = a.union(b)
        assert(ab.terms.size == 2)
        assert(ab.terms.contains(a))
        assert(ab.terms.contains(b))

        val ac = a.union(c)
        assert(ac.terms.size == 2)
        assert(ac.terms.contains(a))
        assert(ac.terms.contains(c))
      }

      test("subtract") {
        val ab = a.subtract(b)
        assert(ab.isEmpty)

        val ba = b.subtract(a)
        assert(ba.toString == "111")

        val ac = a.subtract(c)
        assert(ac == a)
      }

      test("inverse") {
        val notA = a.inverse
        assert(
          Seq(
            BitSet.bitpat("000"),
            BitSet.bitpat("001"),
            BitSet.bitpat("010"),
            BitSet.bitpat("011"),
            BitSet.bitpat("100"),
            BitSet.bitpat("110"),
            BitSet.bitpat("111")
          ).forall(notA.cover)
        )
      }
    }

    test("BitPat concatenation") {
      val a = BitSet.bitpat("10")
      val b = BitSet.bitpat("01")

      val c = a ## b
      assert(c.toString == "1001")
      assert(c.width == 4)
      assert(c.value == BigInt(9))
      assert(c.mask == BigInt(15))
    }

    test("BitSet equality") {
      val a = BitSet.bitpat("101")
      val b = BitSet.bitpat(BigInt(5), BigInt(7), 3) // Also 101
      val c = BitSet.bitpat("1?1")

      assert(a == b)
      assert(a != c)

      // Create BitSet from multiple BitPats
      val multiSet1 = BitSet(Set(a, b))
      assert(multiSet1.terms.size == 1) // Should be deduplicated

      val multiSet2 = BitSet(Set(a, c))
      assert(multiSet2.terms.size == 2)
    }

    test("JSON serialization") {
      test("BitPat serialization") {
        val a            = BitSet.bitpat("101")
        val json         = upickle.default.write(a)
        val deserialized = upickle.default.read[BitPat](json)

        assert(deserialized == a)
        assert(json == "\"101\"")
      }

      test("BitPat with don't cares") {
        val a            = BitSet.bitpat("10?")
        val json         = upickle.default.write(a)
        val deserialized = upickle.default.read[BitPat](json)

        assert(deserialized == a)
        assert(json == "\"10?\"")
        assert(deserialized.hasDontCares)
      }

      test("BitPat all don't cares") {
        val a            = BitSet.bitpat("???")
        val json         = upickle.default.write(a)
        val deserialized = upickle.default.read[BitPat](json)

        assert(deserialized == a)
        assert(json == "\"???\"")
        assert(deserialized.allDontCares)
      }

      test("BitSet as BitSet (single term)") {
        val a            = BitSet.bitpat("101")
        val json         = upickle.default.write[BitSet](a)
        val deserialized = upickle.default.read[BitSet](json)

        assert(deserialized == a)
        assert(json == "\"101\"")
      }

      test("BitSet with multiple terms") {
        val a        = BitSet.bitpat("101")
        val b        = BitSet.bitpat("111")
        val multiSet = BitSet(Set(a, b))

        val json         = upickle.default.write[BitSet](multiSet)
        val deserialized = upickle.default.read[BitSet](json)

        assert(deserialized == multiSet)
        assert(json == "\"101|111\"")
        assert(deserialized.terms.size == 2)
        assert(deserialized.terms.contains(a))
        assert(deserialized.terms.contains(b))
      }

      test("EmptyBitSet serialization") {
        val empty        = BitSet.empty
        val json         = upickle.default.write[BitSet](empty)
        val deserialized = upickle.default.read[BitSet](json)

        assert(deserialized == empty)
        assert(json == "\"EMPTY\"")
        assert(deserialized.isEmpty)
        assert(deserialized.terms.isEmpty)
      }

      test("RangeBitSet serialization") {
        val range        = BitSet.range(BigInt(2), BigInt(2))
        val json         = upickle.default.write[BitSet](range)
        val deserialized = upickle.default.read[BitSet](json)

        assert(deserialized == range)
        assert(deserialized.cover(BitSet.bitpat("10")))
        assert(deserialized.cover(BitSet.bitpat("11")))
      }

      test("Complex BitSet round-trip") {
        val a          = BitSet.bitpat("10?")
        val b          = BitSet.bitpat("1?1")
        val c          = BitSet.bitpat("0??")
        val complexSet = a.union(b).union(c)

        val json         = upickle.default.write[BitSet](complexSet)
        val deserialized = upickle.default.read[BitSet](json)

        assert(deserialized == complexSet)
        assert(deserialized.terms.size == complexSet.terms.size)
        complexSet.terms.foreach(term => assert(deserialized.terms.contains(term)))
      }

      test("BitPat edge cases") {
        // All zeros
        val allZeros          = BitSet.bitpat("000")
        val jsonZeros         = upickle.default.write(allZeros)
        val deserializedZeros = upickle.default.read[BitPat](jsonZeros)
        assert(deserializedZeros == allZeros)
        assert(deserializedZeros.allZeros)

        // All ones
        val allOnes          = BitSet.bitpat("111")
        val jsonOnes         = upickle.default.write(allOnes)
        val deserializedOnes = upickle.default.read[BitPat](jsonOnes)
        assert(deserializedOnes == allOnes)
        assert(deserializedOnes.allOnes)

        // Single bit
        val singleBit          = BitSet.bitpat("1")
        val jsonSingle         = upickle.default.write(singleBit)
        val deserializedSingle = upickle.default.read[BitPat](jsonSingle)
        assert(deserializedSingle == singleBit)
        assert(jsonSingle == "\"1\"")
      }
    }
  }
}
