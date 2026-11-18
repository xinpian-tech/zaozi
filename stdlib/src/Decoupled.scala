// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Yuhang Zeng <unlsycn@unlsycn.com>
package me.jiuyang.stdlib

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*

import org.llvm.mlir.scalalib.capi.ir.{Block, Context, Module as MlirModule}

import scala.scalanative.unsafe.Zone

trait HasFire[T <: HardwareDataType, R <: Referable[T]]:
  extension (ref: R)
    def fire(
      using ctx: Context,
      file:      sourcecode.File,
      line:      sourcecode.Line,
      valName:   sourcecode.Name.Machine,
      instCtx:   InstanceContext
    )(
      using Zone,
      Block
    ): Node[Bool]

object Decoupled:
  def apply[T <: HardwareDataType](
    bits: T
  )(
    using TypeImpl,
    ConstructorApi
  ) = new DecoupledIO[T](bits)

class DecoupledIO[T <: HardwareDataType](
  _bits: T
)(
  using TypeImpl,
  ConstructorApi)
    extends Bundle:
  val ready: BundleField[Bool] = Flipped(summon[ConstructorApi].Bool())
  val valid: BundleField[Bool] = Aligned(summon[ConstructorApi].Bool())
  val bits:  BundleField[T]    = Aligned(_bits)

object Valid:
  def apply[T <: Data](
    bits: T
  )(
    using TypeImpl,
    ConstructorApi
  ): ValidIO[T] = new ValidIO[T](bits)

class ValidIO[T <: Data](
  _bits: T
)(
  using TypeImpl,
  ConstructorApi)
    extends Bundle:
  val valid: BundleField[Bool] = Aligned(summon[ConstructorApi].Bool())
  val bits:  BundleField[T]    = Aligned(_bits)
