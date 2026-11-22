// SPDX-License-Identifier: Apache-2.0

// DEFINE: %{test} = scala-cli --server=false --java-home=%JAVAHOME --extra-jars=%RUNCLASSPATH --scala-version=%SCALAVERSION -O="-experimental" %JAVAOPTS %s
// RUN: rm -rf %t.dir && mkdir -p %t.dir && cd %t.dir
// RUN: env ZAOZI_OUTDIR=%t.dir %{test}
// RUN: circt-opt %t.dir/probe.mlirbc -o /dev/null
// RUN: rm -rf %t.dir

package me.jiuyang.zaozi.default

import java.lang.foreign.Arena
import java.nio.file.Files
import scala.util.Using
import org.llvm.mlir.scalalib.capi.ir.{*, given}

object BytecodeWriteFailure:
  def main(args: Array[String]): Unit =
    val marker = new IllegalStateException("failed bytecode sink")
    var calls = 0

    def publish(configured: Boolean, fail: Boolean): Unit =
      Elaborate.generate("probe") { file =>
        given arena: Arena = Arena.ofConfined()
        try
          given context: Context = summon[ContextApi].contextCreateWithThreading(false)
          try
            val module = summon[ModuleApi].moduleCreateParse("module @probe {}")
            try
              Using.resource(Files.newOutputStream(file.toNIO)) { out =>
                val sink = (chunk: Array[Byte]) =>
                  if fail then
                    calls += 1
                    out.write(0)
                    throw marker
                  out.write(chunk)
                if configured then
                  val config = given_BytecodeWriterConfigApi.bytecodeWriterConfigCreate()
                  try module.getOperation.writeBytecodeWithConfig(config, sink)
                  finally config.destroy()
                else module.getOperation.writeBytecode(sink)
              }
            finally module.destroy()
          finally context.destroy()
        finally arena.close()
      }

    val file = os.pwd / "probe.mlirbc"
    publish(configured = false, fail = false)
    val previous = os.read.bytes(file)
    for configured <- Seq(false, true) do
      calls = 0
      try
        publish(configured, fail = true)
        throw new AssertionError("write returned after its sink failed")
      catch case error: IllegalStateException if error eq marker => ()
      assert(calls == 1 && os.read.bytes(file).sameElements(previous))
      assert(os.list(os.pwd).toSeq == Seq(file))
