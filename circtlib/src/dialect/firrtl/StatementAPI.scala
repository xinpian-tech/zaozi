// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package org.llvm.circt.scalalib.dialect.firrtl.operation

import org.llvm.mlir.scalalib.capi.support.HasOperation
import org.llvm.mlir.scalalib.capi.ir.{Context, Location, Operation, Value}

import java.lang.foreign.Arena

class Assert(val _operation: Operation)
class Assume(val _operation: Operation)
class Attach(val _operation: Operation)
class Connect(val _operation: Operation)
trait ConnectApi extends HasOperation[Connect]:
end ConnectApi

class Cover(val _operation: Operation)
class Force(val _operation: Operation)
class LayerBlock(val _operation: Operation)
trait LayerBlockApi        extends HasOperation[LayerBlock]:
end LayerBlockApi
class Match(val _operation: Operation)
class MatchingConnect(val _operation: Operation)
class Printf(val _operation: Operation)
trait PrintfApi            extends HasOperation[Printf]:
  /** `firrtl.printf` — print `formatString` on the rising edge of `clock` when `cond` is high.
    *
    * firtool lowers this to the `sim` dialect (`sim.print` over `sim.fmt.*`), so it is the way to get a simulation
    * trace out of a design written in the Zaozi DSL, where signals are typed references rather than IR handles.
    */
  def op(
    clock:         Value,
    cond:          Value,
    formatString:  String,
    substitutions: Seq[Value],
    name:          String,
    location:      Location
  )(
    using Arena,
    Context
  ): Printf
end PrintfApi
class Propassign(val _operation: Operation)
class RefDefine(val _operation: Operation)
trait RefDefineApi         extends HasOperation[RefDefine]:
end RefDefineApi
class RefForceInitial(val _operation: Operation)
trait RefForceInitialApi   extends HasOperation[RefForceInitial]:
end RefForceInitialApi
class RefForce(val _operation: Operation)
trait RefForceApi          extends HasOperation[RefForce]:
end RefForceApi
class RefReleaseInitial(val _operation: Operation)
trait RefReleaseInitialApi extends HasOperation[RefReleaseInitial]:
end RefReleaseInitialApi
class RefRelease(val _operation: Operation)
trait RefReleaseApi        extends HasOperation[RefRelease]:
end RefReleaseApi
class Skip(val _operation: Operation)
class Stop(val _operation: Operation)
trait StopApi extends HasOperation[Stop]:
  /** `firrtl.stop` — end the simulation on the rising edge of `clock` when `cond` is high: `$finish` when `exitCode`
    * is zero, `$fatal` otherwise.
    *
    * firtool lowers this to the `sim` dialect (`sim.terminate`), so it is the way to end a simulation from a design
    * written in the Zaozi DSL — the counterpart to [[Printf]] for control rather than output.
    */
  def op(
    clock:    Value,
    cond:     Value,
    exitCode: Int,
    name:     String,
    location: Location
  )(
    using Arena,
    Context
  ): Stop
end StopApi
class VerifAssert(val _operation: Operation)
trait VerifAssertApi       extends HasOperation[VerifAssert]:
  def op(
    property: Value,
    label:    scala.Option[String],
    location: Location
  )(
    using Arena,
    Context
  ): VerifAssert = op(property, None, label, location)

  def op(
    property: Value,
    enable:   scala.Option[Value],
    label:    scala.Option[String],
    location: Location
  )(
    using Arena,
    Context
  ): VerifAssert
end VerifAssertApi
class VerifAssume(val _operation: Operation)
trait VerifAssumeApi       extends HasOperation[VerifAssume]:
  def op(
    property: Value,
    label:    scala.Option[String],
    location: Location
  )(
    using Arena,
    Context
  ): VerifAssume = op(property, None, label, location)

  def op(
    property: Value,
    enable:   scala.Option[Value],
    label:    scala.Option[String],
    location: Location
  )(
    using Arena,
    Context
  ): VerifAssume
end VerifAssumeApi
class VerifCover(val _operation: Operation)
trait VerifCoverApi        extends HasOperation[VerifCover]:
  def op(
    property: Value,
    label:    scala.Option[String],
    location: Location
  )(
    using Arena,
    Context
  ): VerifCover = op(property, None, label, location)

  def op(
    property: Value,
    enable:   scala.Option[Value],
    label:    scala.Option[String],
    location: Location
  )(
    using Arena,
    Context
  ): VerifCover
end VerifCoverApi
class VerifRequire(val _operation: Operation)
trait VerifRequireApi      extends HasOperation[VerifRequire]:
  def op(
    property: Value,
    label:    scala.Option[String],
    location: Location
  )(
    using Arena,
    Context
  ): VerifRequire
end VerifRequireApi
class VerifEnsure(val _operation: Operation)
trait VerifEnsureApi       extends HasOperation[VerifEnsure]:
  def op(
    property: Value,
    label:    scala.Option[String],
    location: Location
  )(
    using Arena,
    Context
  ): VerifEnsure
end VerifEnsureApi
class When(val _operation: Operation)
trait WhenApi              extends HasOperation[When]:
end WhenApi
