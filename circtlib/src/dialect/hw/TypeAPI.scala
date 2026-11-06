// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package org.llvm.circt.scalalib.dialect.hw.tpe

import org.llvm.mlir.scalalib.capi.ir.{Attribute, Context, Type}
import java.lang.foreign.Arena

enum PortDirection:
  case Input, Output, InOut

case class ModulePort(name: String, tpe: Type, direction: PortDirection)

/** HW types for which the pinned CIRCT C API has no constructor. */
trait TypeApi:
  def getArray(
    element: Type,
    size:    Attribute
  )(
    using Arena,
    Context
  ): Type
  def getUnpackedArray(
    element: Type,
    size:    Attribute
  )(
    using Arena,
    Context
  ): Type
  def getString(
    using Arena,
    Context
  ): Type
  def getEnum(
    fields: Seq[String]
  )(
    using Arena,
    Context
  ): Type
  def getUnpackedArray(
    element: Type,
    size:    Int
  )(
    using Arena,
    Context
  ): Type
  def getModule(
    ports: Seq[ModulePort]
  )(
    using Arena,
    Context
  ): Type
  def getStruct(
    fields: Seq[(String, Type)]
  )(
    using Arena,
    Context
  ): Type
  def getUnion(
    fields: Seq[(String, Type)]
  )(
    using Arena,
    Context
  ): Type
