// SPDX-License-Identifier: Apache-2.0
package org.llvm.ffi

import scala.scalanative.unsafe.{CSSize, CSize}
import scala.scalanative.unsigned.*

/** ISO C integer types shared by both generated APIs, independent of libc's private typedef names. */
object StdInt:
  type int8_t    = Byte
  type uint8_t   = UByte
  type int16_t   = Short
  type uint16_t  = UShort
  type int32_t   = Int
  type uint32_t  = UInt
  type int64_t   = Long
  type uint64_t  = ULong
  type intptr_t  = CSSize
  type uintptr_t = CSize
  type size_t    = CSize

  // sn-bindgen also exports the companion of aliased integer types.
  object uint64_t:
    inline def apply(value: ULong): uint64_t = value
  object int32_t:
    inline def apply(value: Int): int32_t = value
