// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.zaozi.valuetpe

import me.jiuyang.zaozi.TypeImpl
import org.llvm.mlir.scalalib.capi.ir.{Context, Type}

import scala.scalanative.unsafe.Zone

trait Reset extends Element with CanProbe:
  final def toMlirType(
    using Zone,
    Context,
    TypeImpl
  ): Type = this.toMlirTypeImpl
