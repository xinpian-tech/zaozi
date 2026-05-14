// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package org.llvm.mlir.scalalib.capi.support

import org.llvm.mlir.scalalib.capi.ir.{*, given}

import java.lang.foreign.{Arena, MemorySegment}

trait HasSegment[T]:
  extension (ref: T) inline def segment: MemorySegment
end HasSegment

trait HasSizeOf[T]:
  extension (ref: T) inline def sizeOf: Int
end HasSizeOf

trait EnumHasToNative[T]:
  extension (int: Int) inline def fromNative: T
  extension (ref: T) inline def toNative:     Int
end EnumHasToNative

trait HasOperation[T]:
  extension (ref: T) def operation: Operation
end HasOperation

trait ScalaTpeToMlirArray[T <: Int | Long | Float | Double]:
  extension (xs: Seq[T])
    inline def toMlirArray(
      using arena: Arena
    ): MemorySegment

trait ToMlirArray[E]:
  extension (xs: Seq[E])
    inline def toMlirArray(
      using arena: Arena,
      api:         HasSizeOf[E] & (HasSegment[E] | EnumHasToNative[E])
    ): MemorySegment

class StringRef(val _segment: MemorySegment)
trait StringRefApi extends HasSegment[StringRef] with HasSizeOf[StringRef]:
  extension (string:    String)
    inline def toStringRef(
      using arena: Arena
    ):    StringRef
  extension (stringRef: StringRef)
    inline def toBytes:       Array[Byte]
    inline def toScalaString: String
end StringRefApi

class StringCallback(val _segment: MemorySegment)
trait StringCallbackApi extends HasSegment[StringCallback]:
  extension (stringCallBack: String => Unit)
    inline def stringToStringCallback(
      using arena: Arena
    ): StringCallback
  extension (bytesCallBack:  Array[Byte] => Unit)
    inline def bytesToStringCallback(
      using arena: Arena
    ): StringCallback
end StringCallbackApi

class LogicalResult(val _segment: MemorySegment)
trait LogicalResultApi extends HasSegment[LogicalResult] with HasSizeOf[LogicalResult]:
  extension (logicalResult: LogicalResult)
    inline def succeeded: Boolean
    inline def failed:    Boolean

class LlvmThreadPool(val _segment: MemorySegment)
trait LlvmThreadPoolApi extends HasSegment[LlvmThreadPool] with HasSizeOf[LlvmThreadPool]:
  inline def llvmThreadPoolCreate(
  )(
    using arena: Arena
  ):                                                               LlvmThreadPool
  extension (llvmThreadPool: LlvmThreadPool) inline def destroy(): Unit
end LlvmThreadPoolApi

class TypeID(val _segment: MemorySegment)
trait TypeIDApi extends HasSegment[TypeID] with HasSizeOf[TypeID]

class TypeIDAllocator(val _segment: MemorySegment)
trait TypeIDAllocatorApi extends HasSegment[TypeIDAllocator] with HasSizeOf[TypeIDAllocator]
