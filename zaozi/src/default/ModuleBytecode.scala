// SPDX-License-Identifier: Apache-2.0
package me.jiuyang.zaozi.default

import org.llvm.mlir.scalalib.capi.ir.{given_ModuleApi, given_OperationApi, Module}

import java.lang.foreign.Arena
import java.nio.file.Files
import scala.util.Using

private object ModuleBytecode:
  def write(
    module: Module,
    file:   os.Path
  )(
    using Arena
  ): Unit =
    Using.resource(Files.newOutputStream(file.toNIO)) { out =>
      module.getOperation.writeBytecode(out.write(_))
    }
