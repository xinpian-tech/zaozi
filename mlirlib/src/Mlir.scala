// SPDX-License-Identifier: Apache-2.0
package org.llvm.mlir.scalalib

import java.io.ByteArrayOutputStream
import org.llvm.mlir.scalalib.capi.ir.{*, given}
import org.llvm.mlir.scalalib.capi.pass.{*, given}
import org.llvm.mlir.scalalib.capi.support.given
import scala.scalanative.unsafe.{CSize, Ptr, Zone}

object Mlir:
  def withContext[A](body: (Zone, Context) ?=> A): A =
    Zone.acquire { zone =>
      // MLIR can enter Scala from several native worker threads in the same context.
      given Zone = new Zone:
        def alloc(size: CSize): Ptr[Byte] = zone.synchronized(zone.alloc(size))
        def isClosed:           Boolean   = zone.isClosed
        def close():            Unit      = zone.synchronized(zone.close())
      given context: Context = summon[ContextApi].contextCreate
      try body
      finally context.destroy()
    }

  def withPassManager[A](
    body: PassManager => A
  )(
    using Zone,
    Context
  ): A =
    val manager = summon[PassManagerApi].passManagerCreate
    try body(manager)
    finally manager.destroy()

  def runPipeline(
    module:   Module,
    pipeline: String
  )(
    using Zone,
    Context
  ): Unit =
    withPassManager { manager =>
      val messages = new StringBuilder()
      require(
        manager.getAsOpPassManager.parsePassPipeline(pipeline, msg => messages.append(msg)).succeeded,
        messages.toString
      )
      require(manager.runOnOp(module.getOperation).succeeded, "MLIR pipeline failed")
    }

  def bytecode(
    module: Module
  )(
    using Zone
  ): Array[Byte] =
    val output = new ByteArrayOutputStream()
    module.getOperation.writeBytecode(chunk => output.write(chunk))
    output.toByteArray

/** A complete bytecode-to-bytecode invocation. All live IR belongs to this invocation. */
final class ModuleTransform(
  registerDialects: (Zone, Context) ?=> Unit
)(transform: (Zone, Context) ?=> Module => Unit):
  def apply(input: Array[Byte]): Array[Byte] = Mlir.withContext {
    require(
      input.length >= 4 && input(0) == 0x4d && input(1) == 0x4c &&
        input(2) == 0xef.toByte && input(3) == 0x52,
      "expected MLIR bytecode"
    )
    registerDialects
    val module = summon[ModuleApi].moduleCreateParse(input)
    require(!module.isNull, "could not read MLIR module")
    try
      transform(module)
      require(module.getOperation.verify, "transformed MLIR module failed verification")
      Mlir.bytecode(module)
    finally module.destroy()
  }

  def main(args: Array[String]): Unit =
    try
      require(args.isEmpty, "this plugin reads MLIR bytecode from stdin")
      val result = apply(System.in.readAllBytes())
      System.out.write(result)
      System.out.flush()
      if System.out.checkError() then throw new java.io.IOException("could not write MLIR bytecode")
    catch
      case scala.util.control.NonFatal(error) =>
        System.err.println(error.getMessage)
        sys.exit(1)
