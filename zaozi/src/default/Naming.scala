// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.zaozi.default

import org.llvm.mlir.scalalib.capi.ir.{given_LocationApi, Context, Location, LocationApi}

import java.lang.foreign.Arena
import me.jiuyang.zaozi.Parameter
import me.jiuyang.zaozi.HWInterface
import me.jiuyang.zaozi.DVInterface
import org.llvm.mlir.scalalib.capi.ir.Block
import me.jiuyang.zaozi.reftpe.{Const, Interface, Node, Propagated, Referable}
import me.jiuyang.zaozi.valuetpe.Data
import me.jiuyang.zaozi.ConstructorApi
import me.jiuyang.zaozi.InstanceContext
import javax.naming.NameNotFoundException

/** Every default `given` implementation's source-location tag, derived from the Scala call site (`sourcecode.File`/
  * `sourcecode.Line`) -- this is why FIRRTL/Verilog output carries `// path/to/File.scala:42` comments back to the
  * generator source.
  */
private inline def locate(
  using Arena,
  Context,
  sourcecode.File,
  sourcecode.Line
): Location =
  summon[LocationApi].locationFileLineColGet(
    summon[sourcecode.File].value,
    summon[sourcecode.Line].value,
    0
  )

/** The name a new signal/node gets: the enclosing `val`'s name when there is one (`sourcecode.Name.Machine` captures it
  * automatically), or `_GEN_<n>` from `InstanceContext`'s per-elaboration counter for anonymous expressions (e.g. an
  * intermediate value in a chained `.a.b.c` expression with no `val` of its own).
  */
private inline def valName(
  using sourcecode.Name.Machine,
  InstanceContext
): String = summon[sourcecode.Name.Machine].value match
  case actualName if !sourcecode.Util.isSyntheticName(actualName) => actualName
  case _                                                          => s"_GEN_${summon[InstanceContext].anonSignalCounter.inc()}"

/** Like [[valName]], but for `Bundle`/`Record` field declarations, which must always have a real name (there's no
  * anonymous-field fallback -- an unnamed field is a usage error, not something to paper over with `_GEN_n`).
  */
private inline def bundleFieldName(
  using sourcecode.Name.Machine
): String = summon[sourcecode.Name.Machine].value match
  case actualName if !sourcecode.Util.isSyntheticName(actualName) => actualName
  case anonName                                                   => throw NameNotFoundException(s"Cannot find name for BundleField ${anonName}")

/** Preserves `Const`-ness across a cast/derived value: if `source` is a `Const` (a literal), the result is too (see
  * [[me.jiuyang.zaozi.reftpe.Propagated]]), which is what lets e.g. `0.U(8).asBits` still satisfy `RegInit`'s
  * `Const[T]` requirement.
  */
private[zaozi] def propagate[R <: Referable[?], RET <: Data](
  source: R,
  tpe:    RET,
  op:     org.llvm.mlir.scalalib.capi.ir.Operation
): Propagated[R, RET] =
  val result = source match
    case _: Const[?] =>
      new Const[RET]:
        val _tpe       = tpe
        val _operation = op
    case _ =>
      new Node[RET]:
        val _tpe       = tpe
        val _operation = op
  result.asInstanceOf[Propagated[R, RET]]
