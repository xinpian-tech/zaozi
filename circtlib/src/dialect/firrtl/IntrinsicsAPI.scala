// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package org.llvm.circt.scalalib.dialect.firrtl.operation

import org.llvm.mlir.scalalib.capi.support.HasOperation
import org.llvm.mlir.scalalib.capi.ir.{Context, Location, Operation, Value}

import java.lang.foreign.Arena

class ClockDividerIntrinsic(val _operation: Operation)
class ClockGateIntrinsic(val _operation: Operation)
class ClockInverterIntrinsic(val _operation: Operation)
class DPICallIntrinsic(val _operation: Operation)
class FPGAProbeIntrinsic(val _operation: Operation)
class GenericIntrinsic(val _operation: Operation)
class HasBeenResetIntrinsic(val _operation: Operation)
class IsXIntrinsic(val _operation: Operation)
class PlusArgsTestIntrinsic(val _operation: Operation)
class PlusArgsValueIntrinsic(val _operation: Operation)
class UnclockedAssumeIntrinsic(val _operation: Operation)

class VerifAssertIntrinsic(val _operation: Operation)
trait VerifAssertIntrinsicApi extends HasOperation[VerifAssertIntrinsic]:
  def op(
    property: Value,
    enable:   Value,
    label:    String,
    location: Location
  )(
    using Arena,
    Context
  ): VerifAssertIntrinsic
end VerifAssertIntrinsicApi

class VerifAssumeIntrinsic(val _operation: Operation)
trait VerifAssumeIntrinsicApi extends HasOperation[VerifAssumeIntrinsic]:
  def op(
    property: Value,
    enable:   Value,
    label:    String,
    location: Location
  )(
    using Arena,
    Context
  ): VerifAssumeIntrinsic
end VerifAssumeIntrinsicApi

class VerifCoverIntrinsic(val _operation: Operation)
trait VerifCoverIntrinsicApi extends HasOperation[VerifCoverIntrinsic]:
  def op(
    property: Value,
    enable:   Value,
    label:    String,
    location: Location
  )(
    using Arena,
    Context
  ): VerifCoverIntrinsic
end VerifCoverIntrinsicApi

class VerifEnsureIntrinsic(val _operation: Operation)
trait VerifEnsureIntrinsicApi extends HasOperation[VerifEnsureIntrinsic]:
  def op(
    property: Value,
    label:    scala.Option[String],
    location: Location
  )(
    using Arena,
    Context
  ): VerifEnsureIntrinsic
  def op(
    property: Value,
    enable:   Value,
    label:    String,
    location: Location
  )(
    using Arena,
    Context
  ): VerifEnsureIntrinsic
end VerifEnsureIntrinsicApi

class VerifRequireIntrinsic(val _operation: Operation)
trait VerifRequireIntrinsicApi extends HasOperation[VerifRequireIntrinsic]:
  def op(
    property: Value,
    label:    scala.Option[String],
    location: Location
  )(
    using Arena,
    Context
  ): VerifRequireIntrinsic
  def op(
    property: Value,
    enable:   Value,
    label:    String,
    location: Location
  )(
    using Arena,
    Context
  ): VerifRequireIntrinsic
end VerifRequireIntrinsicApi
