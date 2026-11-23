// SPDX-License-Identifier: Apache-2.0
package org.llvm.mlir.scalalib.capi.support

import scala.collection.mutable
import scala.scalanative.unsafe.*
import org.llvm.mlir.raw.aliases.{MlirDiagnosticHandler, MlirOperationWalkCallback, MlirStringCallback}
import org.llvm.mlir.raw.structs.{MlirDiagnostic, MlirOperation}
import org.llvm.mlir.scalalib.capi.diagnostic.Diagnostic
import org.llvm.mlir.scalalib.capi.ir.{Operation, WalkResultEnum, given}

/** MLIR retains C function pointers and opaque user data. Keep the Scala state reachable until the synchronous call
  * returns or its native owner releases it.
  */
object NativeCallbacks:
  final class Entry[A](val value: A):
    @volatile var failure: Throwable = null

  private var nextId  = 0L
  private val entries = mutable.LongMap.empty[Entry[?]]

  def retain[A](value: A): Ptr[Byte] = synchronized {
    nextId += 1
    entries.update(nextId, new Entry(value))
    nextId.toPtr[Byte]
  }

  def entry[A](id: Ptr[Byte]): Entry[A] = synchronized {
    entries(id.toLong).asInstanceOf[Entry[A]]
  }

  def release(id: Ptr[Byte]): Unit = synchronized { entries.remove(id.toLong); () }

  def scoped[A, B](value: A)(body: Ptr[Byte] => B): B =
    val id = retain(value)
    try
      val result = body(id)
      val error  = entry[A](id).failure
      if error != null then throw error
      result
    finally release(id)

  @exported("zaozi_string_chunk")
  def stringChunk(data: Ptr[Byte], size: CSize, id: Ptr[Byte]): Unit =
    val state = entry[Array[Byte] => Unit](id)
    if state.failure == null then
      try state.value(copyBytes(data, size))
      catch case t: Throwable => state.failure = t

  @exported("zaozi_diagnostic")
  def diagnostic(diagnostic: Ptr[MlirDiagnostic], id: Ptr[Byte]): Boolean =
    val state = entry[Diagnostic => Boolean](id)
    try state.value(Diagnostic(diagnostic))
    catch
      case t: Throwable =>
        state.failure = t
        false

  @exported("zaozi_walk")
  def walk(operation: Ptr[MlirOperation], id: Ptr[Byte]): CInt =
    val state = entry[Ptr[MlirOperation] => WalkResultEnum](id)
    try state.value(operation).toNative
    catch
      case t: Throwable =>
        state.failure = t
        WalkResultEnum.Interrupt.toNative

  @exported("zaozi_release")
  def releaseFromC(id: Ptr[Byte]): Unit = release(id)

  def copyBytes(data: Ptr[Byte], size: CSize): Array[Byte] =
    require(size.toLong <= Int.MaxValue)
    val bytes = new Array[Byte](size.toInt)
    if bytes.nonEmpty then scala.scalanative.libc.string.memcpy(bytes.at(0), data, size)
    bytes

@extern
object CallbackFunctions:
  @name("zaozi_string_callback")
  def string():     MlirStringCallback         = extern
  @name("zaozi_diagnostic_callback")
  def diagnostic(): MlirDiagnosticHandler      = extern
  @name("zaozi_delete_callback")
  def release():    CFuncPtr1[Ptr[Byte], Unit] = extern
  @name("zaozi_walk_callback")
  def walk():       MlirOperationWalkCallback  = extern
