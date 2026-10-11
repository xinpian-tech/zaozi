// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package org.llvm.circt.scalalib.dialect.sv.operation

import org.llvm.circt.scalalib.dialect.sv.attr.*
import org.llvm.mlir.scalalib.capi.ir.{Context, Location, Operation, Value}
import org.llvm.mlir.scalalib.capi.support.HasOperation
import java.lang.foreign.Arena

/** `sv.assert`. */
class Assert(val _operation: Operation)
trait AssertApi extends HasOperation[Assert]:
  def op(
    expression:    Value,
    defer:         DeferAssert,
    label:         Option[String] = None,
    message:       Option[String] = None,
    substitutions: Seq[Value] = Seq.empty,
    location:      Location
  )(
    using arena:   Arena,
    context:       Context
  ): Assert
end AssertApi

/** `sv.assert.concurrent`. */
class AssertConcurrent(val _operation: Operation)
trait AssertConcurrentApi extends HasOperation[AssertConcurrent]:
  def op(
    event:         EventControl,
    clock:         Value,
    property:      Value,
    label:         Option[String] = None,
    message:       Option[String] = None,
    substitutions: Seq[Value] = Seq.empty,
    location:      Location
  )(
    using arena:   Arena,
    context:       Context
  ): AssertConcurrent
end AssertConcurrentApi

/** `sv.assert_property`. */
class AssertProperty(val _operation: Operation)

/** Accepts i1, LTL sequence or LTL property values. clock and event must be supplied together; disable has disable_iff
  * polarity.
  */
trait AssertPropertyApi extends HasOperation[AssertProperty]:
  def op(
    property:    Value,
    event:       Option[EventControl] = None,
    clock:       Option[Value] = None,
    disable:     Option[Value] = None,
    label:       Option[String] = None,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): AssertProperty
end AssertPropertyApi

/** `sv.assume`. */
class Assume(val _operation: Operation)
trait AssumeApi extends HasOperation[Assume]:
  def op(
    expression:    Value,
    defer:         DeferAssert,
    label:         Option[String] = None,
    message:       Option[String] = None,
    substitutions: Seq[Value] = Seq.empty,
    location:      Location
  )(
    using arena:   Arena,
    context:       Context
  ): Assume
end AssumeApi

/** `sv.assume.concurrent`. */
class AssumeConcurrent(val _operation: Operation)
trait AssumeConcurrentApi extends HasOperation[AssumeConcurrent]:
  def op(
    event:         EventControl,
    clock:         Value,
    property:      Value,
    label:         Option[String] = None,
    message:       Option[String] = None,
    substitutions: Seq[Value] = Seq.empty,
    location:      Location
  )(
    using arena:   Arena,
    context:       Context
  ): AssumeConcurrent
end AssumeConcurrentApi

/** `sv.assume_property`. */
class AssumeProperty(val _operation: Operation)

/** Accepts i1, LTL sequence or LTL property values. clock and event must be supplied together; disable has disable_iff
  * polarity.
  */
trait AssumePropertyApi extends HasOperation[AssumeProperty]:
  def op(
    property:    Value,
    event:       Option[EventControl] = None,
    clock:       Option[Value] = None,
    disable:     Option[Value] = None,
    label:       Option[String] = None,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): AssumeProperty
end AssumePropertyApi

/** `sv.cover`. */
class Cover(val _operation: Operation)
trait CoverApi extends HasOperation[Cover]:
  def op(
    expression:  Value,
    defer:       DeferAssert,
    label:       Option[String] = None,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): Cover
end CoverApi

/** `sv.cover.concurrent`. */
class CoverConcurrent(val _operation: Operation)
trait CoverConcurrentApi extends HasOperation[CoverConcurrent]:
  def op(
    event:       EventControl,
    clock:       Value,
    property:    Value,
    label:       Option[String] = None,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): CoverConcurrent
end CoverConcurrentApi

/** `sv.cover_property`. */
class CoverProperty(val _operation: Operation)

/** Accepts i1, LTL sequence or LTL property values. clock and event must be supplied together; disable has disable_iff
  * polarity.
  */
trait CoverPropertyApi extends HasOperation[CoverProperty]:
  def op(
    property:    Value,
    event:       Option[EventControl] = None,
    clock:       Option[Value] = None,
    disable:     Option[Value] = None,
    label:       Option[String] = None,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): CoverProperty
end CoverPropertyApi
