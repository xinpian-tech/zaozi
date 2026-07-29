// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.zaozi.valuetpe

import me.jiuyang.zaozi.{LayerTree, TypeImpl}
import org.llvm.mlir.scalalib.capi.ir.{Context, Type}

import java.lang.foreign.Arena

/** Marker for [[Data]] types that a debug/verification probe can be taken of: [[Bits]], [[UInt]], [[SInt]], [[Bool]],
  * [[Clock]], [[Reset]]. Notably `Bundle`/`Record` are not marked -- probes are taken per-leaf-field, not of a whole
  * aggregate at once.
  */
trait CanProbe

// UInt is a Data type, Probe[UInt] is either a Data,
// But it cannot be a UInt to avoid the UInt extension exposing to it.
/** A read-only probe reference to a `T`, produced by [[ProbeBundle.ProbeRead]]/[[ProbeRecord.ProbeRead]] and
  * layer-colored ([[LayerTree]]) so it's only observable when that layer is enabled. `RProbe[T]` is a `Data` in its own
  * right, deliberately not a `T`, so the arithmetic/bitwise extension methods defined on `T` don't leak onto the probe
  * reference itself.
  */
trait RProbe[T <: CanProbe & Data] extends Data:
  private[zaozi] val _baseType: T
  private[zaozi] val _color:    LayerTree

  final def toMlirType(
    using Arena,
    Context,
    TypeImpl
  ): Type = this.toMlirTypeImpl

/** A read-write probe reference to a `T` (can be forced, not just observed), produced by
  * [[ProbeBundle.ProbeReadWrite]]/[[ProbeRecord.ProbeReadWrite]]; see [[RProbe]] for why this is not a `T`.
  */
trait RWProbe[T <: CanProbe & Data] extends Data:
  private[zaozi] val _baseType: T
  private[zaozi] val _color:    LayerTree

  final def toMlirType(
    using Arena,
    Context,
    TypeImpl
  ): Type = this.toMlirTypeImpl
