// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.syntheke.circt

import me.jiuyang.syntheke.*
import me.jiuyang.zaozi.{DVInterface, Generator, HWInterface, InstanceContext, LayerInterface, Parameter}
import me.jiuyang.zaozi.default.{*, given}
import org.llvm.mlir.scalalib.capi.ir.{Block, Context, Operation}

import java.lang.foreign.Arena

/** Binds one syntheke [[GeneratorEntry]] to the hardware implementation that enacts it (doc @sec-generator-contract).
  *
  * A backend consumes the serializable full parameter and produces the generator's circuit: `instantiate` creates
  * the instance operation inside the wrapper module currently under emission and dumps the generator's own module
  * (and its transitive children) as per-module `.mlirbc` circuits, which the elaborator links into the design
  * circuit afterwards.
  */
trait GeneratorBackend:
  def id: GeneratorId

  /** The deduplicated module name for this full parameter; must be stable per (GeneratorId, canonical FullParam). */
  def moduleName(fullParam: Any): String

  /** Create the instance operation in the current block; results are the ports, named by the `portNames` attribute. */
  def instantiate(
    fullParam:    Any,
    instanceName: String,
    loc:          SourceLocation
  )(using Arena, Context, Block): Operation

/** The zaozi backend: a syntheke generator entry enacted by a zaozi [[Generator]].
  *
  * `toParam` recovers the zaozi parameter from the syntheke full parameter — typically an identity or a projection,
  * since the full parameter is designed to be exactly what the generator needs (doc @sec-two-layer-params).
  */
final class ZaoziBackend[
  FP,
  PARAM <: Parameter,
  L <: LayerInterface[PARAM],
  I <: HWInterface[PARAM],
  P <: DVInterface[PARAM, L]
](val entry: GeneratorEntry[FP],
  val generator: Generator[PARAM, L, I, P],
  toParam:       FP => PARAM)
    extends GeneratorBackend:

  def id: GeneratorId = entry.id

  private def param(fullParam: Any): PARAM = toParam(fullParam.asInstanceOf[FP])

  def moduleName(fullParam: Any): String = generator.moduleName(param(fullParam))

  def instantiate(
    fullParam:    Any,
    instanceName: String,
    loc:          SourceLocation
  )(using Arena, Context, Block): Operation =
    given sourcecode.File         = sourcecode.File(loc.file)
    given sourcecode.Line         = sourcecode.Line(loc.line)
    given sourcecode.Name.Machine = sourcecode.Name.Machine(instanceName)
    given InstanceContext         = new InstanceContext
    generator.instantiate(param(fullParam)).operation
