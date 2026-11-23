// SPDX-License-Identifier: Apache-2.0
package org.llvm.mlir.scalalib.capi.support

import java.nio.charset.StandardCharsets.UTF_8
import org.llvm.mlir.raw.structs.MlirStringRef
import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*

given StringRefApi with
  extension (ref: StringRef)
    inline def sizeOf:        Int         = sizeof[MlirStringRef].toInt
    inline def toBytes:       Array[Byte] = NativeCallbacks.copyBytes((!ref.native).data, (!ref.native).length)
    inline def toScalaString: String      = new String(toBytes, UTF_8)

  extension (value: String)
    inline def toStringRef(
      using Zone
    ): StringRef = value.getBytes(UTF_8).toStringRef

  extension (bytes: Array[Byte])
    inline def toStringRef(
      using Zone
    ): StringRef =
      // MLIR's parser can inspect the sentinel after a buffer. Its length
      // remains explicit so embedded zero bytes survive a bytecode round trip.
      val data = summon[Zone].alloc(bytes.length.toUSize + 1.toUSize)
      if bytes.nonEmpty then scala.scalanative.libc.string.memcpy(data, bytes.at(0), bytes.length.toUSize)
      data(bytes.length) = 0.toByte
      StringRef(nativeResult[MlirStringRef] { ref =>
        (!ref).data = data
        (!ref).length = bytes.length.toUSize
      })

given StringCallbackApi with
  extension (callback: String => Unit)
    inline def stringToStringCallback(
      using Zone
    ): StringCallback =
      StringCallback(bytes => callback(new String(bytes, UTF_8)))
  extension (callback: Array[Byte] => Unit)
    inline def bytesToStringCallback(
      using Zone
    ): StringCallback = StringCallback(callback)
