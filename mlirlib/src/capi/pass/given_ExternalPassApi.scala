// SPDX-License-Identifier: Apache-2.0
package org.llvm.mlir.scalalib.capi.pass

import java.util.concurrent.atomic.AtomicReference
import org.llvm.mlir.raw.{functions as api, structs as raw}
import org.llvm.mlir.scalalib.capi.ir.{*, given}
import org.llvm.mlir.scalalib.capi.support.{*, given}
import scala.scalanative.unsafe.*

private final class PassCallbacks(
  val construct:  () => Unit,
  val destruct:   () => Unit,
  val initialize: Option[Context => LogicalResult],
  val clonePass:  () => Unit,
  val run:        (Operation, ExternalPass) => Unit,
  val failure: AtomicReference[UpcallFailure | Null]):
  @volatile var invocation: Zone = null

  // The C trampoline exposes stack-local by-value handles. Store their contents
  // with the caller's run, whose lifetime can outlast the pass manager itself.
  def copyHandle[T: Tag](address: Ptr[T]): Ptr[T] =
    val zone = invocation
    zone.synchronized {
      nativeCopy(!address)(
        using summon[Tag[T]],
        zone
      )
    }

  def record(t: Throwable, op: Ptr[Byte] = null, pass: Ptr[Byte] = null): Unit =
    failure.set(UpcallFailure(t, op, pass, System.nanoTime()))

@extern
private object PassTrampoline:
  @name("zaozi_pass_callbacks")
  def fill(callbacks: Ptr[raw.MlirExternalPassCallbacks], initialize: Boolean): Unit = extern

private object PassEntryPoints:
  private def callbacks(id: Ptr[Byte]): PassCallbacks = NativeCallbacks.entry[PassCallbacks](id).value

  def withInvocation[A](passes: java.util.ArrayList[Pass], zone: Zone)(body: => A): A =
    def setInvocation(value: Zone): Unit =
      val itr = passes.iterator()
      while itr.hasNext do
        val id = itr.next()._callbackState
        if id != null then callbacks(id).invocation = value
    setInvocation(zone)
    try body
    finally setInvocation(null)

  private def invoke(id: Ptr[Byte])(f: PassCallbacks => Unit): Unit =
    val state = callbacks(id)
    try f(state)
    catch case t: Throwable => state.record(t)

  @exported("zaozi_pass_construct")
  def construct(id: Ptr[Byte]):                                 Unit    = invoke(id)(_.construct())
  @exported("zaozi_pass_destruct")
  def destruct(id:  Ptr[Byte]):                                 Unit    = invoke(id)(_.destruct())
  @exported("zaozi_pass_clone")
  def clonePass(id: Ptr[Byte]):                                 Unit    = invoke(id)(_.clonePass())
  @exported("zaozi_pass_initialize")
  def initialize(context: Ptr[raw.MlirContext], id: Ptr[Byte]): Boolean =
    val state = callbacks(id)
    try state.initialize.forall(f => f(Context(state.copyHandle(context))).succeeded)
    catch
      case t: Throwable =>
        state.record(t)
        false

  @exported("zaozi_pass_run")
  def run(op: Ptr[raw.MlirOperation], pass: Ptr[raw.MlirExternalPass], id: Ptr[Byte]): Unit =
    val state = callbacks(id)
    try state.run(Operation(state.copyHandle(op)), ExternalPass(pass))
    catch
      case t: Throwable =>
        state.record(t, (!op).ptr, (!pass).ptr)
        api.mlirExternalPassSignalFailure(pass)

given PassApi with
  def createExternalPass(
    passId:             TypeID,
    name:               String,
    argument:           String,
    description:        String,
    opName:             String,
    dependentDialects:  Seq[DialectHandle],
    constructCallback:  () => Unit,
    destructCallback:   () => Unit,
    initializeCallback: Option[Context => LogicalResult],
    cloneCallback:      () => Unit,
    runCallback:        (Operation, ExternalPass) => Unit
  )(
    using Zone
  ): Pass =
    val failure = new AtomicReference[UpcallFailure | Null]()
    val state   = NativeCallbacks.retain(
      PassCallbacks(constructCallback, destructCallback, initializeCallback, cloneCallback, runCallback, failure)
    )
    try
      val callbacks = alloc[raw.MlirExternalPassCallbacks]()
      PassTrampoline.fill(callbacks, initializeCallback.isDefined)
      val pass      = nativeResult[raw.MlirPass](out =>
        api.mlirCreateExternalPass(
          passId.native,
          name.toStringRef.native,
          argument.toStringRef.native,
          description.toStringRef.native,
          opName.toStringRef.native,
          dependentDialects.size.toSize,
          dependentDialects.toMlirArray,
          callbacks,
          state
        )(out)
      )
      Pass(pass, state, failure)
    catch
      case t: Throwable =>
        NativeCallbacks.release(state)
        throw t

  extension (pass: Pass)
    inline def sizeOf:            Int                   = sizeof[raw.MlirPass].toInt
    def destroy(
    )(
      using Zone,
      Context
    ): Unit =
      val manager = summon[PassManagerApi].passManagerCreate
      try manager.addOwnedPass(pass)
      finally manager.destroy()
    inline def lastUpcallFailure: Option[UpcallFailure] = Option(pass._lastFailure.get())

given ExternalPassApi with
  extension (pass: ExternalPass)
    inline def signalFailure(): Unit = api.mlirExternalPassSignalFailure(pass.native)
    inline def sizeOf:          Int  = sizeof[raw.MlirExternalPass].toInt

given ExternalPassCallbacksApi with
  extension (callbacks: ExternalPassCallbacks) inline def sizeOf: Int = sizeof[raw.MlirExternalPassCallbacks].toInt
