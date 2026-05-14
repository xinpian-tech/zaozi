// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package org.llvm.mlir.scalalib.capi.support

import org.llvm.mlir.*

import java.lang.foreign.{Arena, MemoryLayout, MemorySegment, ValueLayout}

given [T <: Int | Long | Float | Double]: ScalaTpeToMlirArray[T] with
  extension (xs: Seq[T])
    inline def toMlirArray(
      using arena: Arena
    ) =
      if (xs.nonEmpty)
        val buffer = arena.allocate(xs.head match
          case int:    Int    => MemoryLayout.sequenceLayout(xs.size, ValueLayout.JAVA_INT)
          case long:   Long   => MemoryLayout.sequenceLayout(xs.size, ValueLayout.JAVA_LONG)
          case float:  Float  => MemoryLayout.sequenceLayout(xs.size, ValueLayout.JAVA_FLOAT)
          case double: Double => MemoryLayout.sequenceLayout(xs.size, ValueLayout.JAVA_DOUBLE))
        xs.zipWithIndex.foreach:
          case (int: Int, i: Int)       => buffer.setAtIndex(ValueLayout.JAVA_INT, i, int)
          case (long: Long, i: Int)     => buffer.setAtIndex(ValueLayout.JAVA_LONG, i, long)
          case (float: Float, i: Int)   => buffer.setAtIndex(ValueLayout.JAVA_FLOAT, i, float)
          case (double: Double, i: Int) => buffer.setAtIndex(ValueLayout.JAVA_DOUBLE, i, double)
        buffer
      else MemorySegment.NULL

end given

given [E]: ToMlirArray[E] with
  extension (xs: Seq[E])
    inline def toMlirArray(
      using arena: Arena,
      api:         HasSizeOf[E] & (HasSegment[E] | EnumHasToNative[E])
    ): MemorySegment =
      if (xs.nonEmpty)
        val sizeOfT: Int = xs.head.sizeOf
        val buffer = arena.allocate(sizeOfT * xs.length)
        xs.zipWithIndex.foreach: (x, i) =>
          scala.compiletime.summonFrom:
            case hasSeg:    HasSegment[E]      =>
              buffer.asSlice(sizeOfT * i, sizeOfT).copyFrom(x.segment)
            case hasNative: EnumHasToNative[E] =>
              buffer.setAtIndex(ValueLayout.JAVA_INT, i, x.toNative)
            case int:       Int                =>
              buffer.setAtIndex(ValueLayout.JAVA_INT, i, int)
            case long:      Long               =>
              buffer.setAtIndex(ValueLayout.JAVA_LONG, i, long)
            case float:     Float              =>
              buffer.setAtIndex(ValueLayout.JAVA_FLOAT, i, float)
            case double:    Double             =>
              buffer.setAtIndex(ValueLayout.JAVA_DOUBLE, i, double)

        buffer
      else MemorySegment.NULL
end given
