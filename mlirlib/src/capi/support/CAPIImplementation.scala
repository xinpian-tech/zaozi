// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package org.llvm.mlir.scalalib.capi.support

import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*

inline def nativeResult[T: Tag](
  inline write: Ptr[T] => Unit
)(
  using Zone
): Ptr[T] =
  val result = summon[Zone].alloc(summon[Tag[T]].size).asInstanceOf[Ptr[T]]
  write(result)
  result

inline def nativeCopy[T: Tag](
  value: T
)(
  using Zone
): Ptr[T] = nativeResult[T](p => !p = value)

def nativeArray[T: Tag](
  items: Seq[T]
)(
  using Zone
): Ptr[T] =
  if items.isEmpty then null
  else
    // The element type is abstract here. Scala Native's sizeof intrinsic
    // requires a concrete type, while the Tag carries its runtime layout.
    val bytes  = items.size.toUSize * summon[Tag[T]].size.toUSize
    val result = summon[Zone].alloc(bytes).asInstanceOf[Ptr[T]]
    items.iterator.zipWithIndex.foreach((item, index) => result(index) = item)
    result

extension [T: Tag](items: Seq[NativeHandle[T]])
  def toMlirArray(
    using Zone
  ): Ptr[T] = nativeArray(items.map(item => !item.native))
