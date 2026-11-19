// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package org.llvm.mlir.scalalib.capi.support

import org.llvm.mlir.raw.structs as MlirStructs
import scala.scalanative.unsafe.*

import org.llvm.mlir.scalalib.capi.ir.{*, given}

abstract class NativeHandle[T](val native: Ptr[T])

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

class StringRef(handle: Ptr[MlirStructs.MlirStringRef]) extends NativeHandle(handle)
trait StringRefApi                                      extends HasSizeOf[StringRef]:
  extension (string:    String)
    inline def toStringRef(
      using zone: Zone
    ):    StringRef
  extension (stringRef: StringRef)
    inline def toBytes:       Array[Byte]
    inline def toScalaString: String
end StringRefApi

class StringCallback(val consume: Array[Byte] => Unit):
  def use[A](f: (org.llvm.mlir.raw.aliases.MlirStringCallback, Ptr[Byte]) => A): A =
    NativeCallbacks.scoped(consume)(data => f(CallbackFunctions.string(), data))
trait StringCallbackApi:
  extension (stringCallBack: String => Unit)
    inline def stringToStringCallback(
      using zone: Zone
    ): StringCallback
  extension (bytesCallBack:  Array[Byte] => Unit)
    inline def bytesToStringCallback(
      using zone: Zone
    ): StringCallback
end StringCallbackApi

class LogicalResult(handle: Ptr[MlirStructs.MlirLogicalResult]) extends NativeHandle(handle)
trait LogicalResultApi                                          extends HasSizeOf[LogicalResult]:
  extension (logicalResult: LogicalResult)
    inline def succeeded: Boolean
    inline def failed:    Boolean

class LlvmThreadPool(handle: Ptr[MlirStructs.MlirLlvmThreadPool]) extends NativeHandle(handle)
trait LlvmThreadPoolApi                                           extends HasSizeOf[LlvmThreadPool]:
  inline def llvmThreadPoolCreate(
  )(
    using zone: Zone
  ):                                                               LlvmThreadPool
  extension (llvmThreadPool: LlvmThreadPool) inline def destroy(): Unit
end LlvmThreadPoolApi

class TypeID(handle: Ptr[MlirStructs.MlirTypeID]) extends NativeHandle(handle)
trait TypeIDApi                                   extends HasSizeOf[TypeID]

class TypeIDAllocator(handle: Ptr[MlirStructs.MlirTypeIDAllocator]) extends NativeHandle(handle)
trait TypeIDAllocatorApi                                            extends HasSizeOf[TypeIDAllocator]
