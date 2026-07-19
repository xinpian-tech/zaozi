// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.zaozi.default

import me.jiuyang.zaozi.{DocApi, TypeImpl}
import me.jiuyang.zaozi.reftpe.Documentable

import org.llvm.mlir.scalalib.capi.ir.{Context, given}

import java.lang.foreign.Arena

export given_DocApi.doc

private[default] def normalizeDocumentation(text: String): String =
  val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
  require(normalized.nonEmpty, "documentation must not be empty")
  normalized

given DocApi with
  extension [T <: Documentable](target: T)
    def doc(
      text: String
    )(
      using Arena,
      Context,
      TypeImpl
    ): T =
      target.operation.setInherentAttributeByName("comment", normalizeDocumentation(text).stringAttrGet)
      target
