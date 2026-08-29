// SPDX-License-Identifier: Apache-2.0
package me.jiuyang.stdlib

// Plain Scala helpers at parameter-elaboration time -- Zaozi has no chisel3.util equivalent. Signatures mirror
// chisel3.util's Util.scala (the BigInt overload is canonical; the Int overload is a convenience wrapper).

/** The number of bits needed to represent `in` distinct values, i.e. `ceil(log2(in))`. `log2Ceil(1) == 0`. */
def log2Ceil(in: BigInt): Int = { require(in > 0, "in must be positive"); (in - 1).bitLength }
def log2Ceil(in: Int):    Int = log2Ceil(BigInt(in))

/** The number of bits needed to represent the value `in`, i.e. `floor(log2(in))`. `log2Floor(1) == 0`. */
def log2Floor(in: BigInt): Int = { require(in > 0, "in must be positive"); in.bitLength - 1 }
def log2Floor(in: Int):    Int = log2Floor(BigInt(in))

/** True if `in` is an exact power of 2. */
def isPow2(in: BigInt): Boolean = in > 0 && (in & (in - 1)) == 0
def isPow2(in: Int):    Boolean = isPow2(BigInt(in))
