// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.syntheke.circt

import scala.collection.mutable

import me.jiuyang.syntheke.*
import me.jiuyang.zaozi.{DVInterface, Generator, HWInterface, InstanceContext, LayerInterface, Parameter}
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.{Interface, ProbeInterface}
import org.llvm.mlir.scalalib.capi.ir.{Block, Context, Operation}

import java.lang.foreign.Arena

/** Binds one syntheke [[GeneratorEntry]] to the hardware implementation that enacts it (doc @sec-generator-contract).
  *
  * A backend consumes the serializable full parameter and produces the generator's circuit: `instantiate` creates the
  * instance operation inside the wrapper module currently under emission and dumps the generator's own module (and its
  * transitive children) as per-module `.mlirbc` circuits, which the elaborator links into the design circuit
  * afterwards.
  */
trait GeneratorBackend:
  def id: GeneratorId

  /** The module name is the linking key: instances reference it and the dumped `.mlirbc` file is found by it, so it
    * must be a faithful encoding of the identity (`GeneratorId`, canonical FullParam) — globally unique across
    * backends, not merely stable within one. Use [[GeneratorBackend.canonicalModuleName]].
    */
  def moduleName(fullParam: Any): String

  /** Create the instance operation in the current block; results are the ports, named by the `portNames` attribute. */
  def instantiate(
    fullParam:    Any,
    instanceName: String,
    loc:          SourceLocation
  )(
    using Arena,
    Context,
    Block
  ): Operation

object GeneratorBackend:
  /** The canonical linking key: sanitized qualified `GeneratorId` plus a strong hash over (qualified name, version,
    * canonical FullParam JSON). Distinct identities cannot collide in the flat symbol namespace the linker resolves by
    * name.
    */
  def canonicalModuleName[FP](entry: GeneratorEntry[FP], fullParam: FP): String =
    val payload = ujson.write(Dedup.canonical(entry.fullParamCodec.encode(fullParam)))
    val digest  = java.security.MessageDigest
      .getInstance("SHA-256")
      .digest(
        s"${entry.id.qualifiedName}\n${entry.id.version}\n$payload".getBytes(java.nio.charset.StandardCharsets.UTF_8)
      )
    val hash    = digest.take(8).map(b => f"$b%02x").mkString
    s"${entry.id.qualifiedName.map(c => if c.isLetterOrDigit then c else '_')}_$hash"

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
](val entry:     GeneratorEntry[FP],
  val generator: Generator[PARAM, L, I, P],
  toParam:       FP => PARAM)
    extends GeneratorBackend:

  def id: GeneratorId = entry.id

  private def param(fullParam: Any): PARAM = toParam(fullParam.asInstanceOf[FP])

  def moduleName(fullParam: Any): String =
    GeneratorBackend.canonicalModuleName(entry, fullParam.asInstanceOf[FP])

  /** zaozi mints both the instance's referenced symbol and the dumped file name from `Generator.moduleName`; route both
    * through the canonical name by delegating to a per-name view of the generator. Memoized per name so zaozi's
    * dump-once bookkeeping keeps working across instantiations.
    */
  private val delegates = mutable.Map.empty[String, Generator[PARAM, L, I, P]]
  private def delegate(name: String): Generator[PARAM, L, I, P] =
    delegates.getOrElseUpdate(
      name,
      new Generator[PARAM, L, I, P]:
        override def moduleName(parameter: PARAM):         String = name
        def architecture(parameter: PARAM): (
          Arena,
          Context,
          Block,
          Interface[I],
          ProbeInterface[P],
          L,
          InstanceContext
        ) ?=> Unit = generator.architecture(parameter)
        def layers(parameter:              PARAM):         L      = generator.layers(parameter)
        def interface(parameter:           PARAM):         I      = generator.interface(parameter)
        def probe(parameter:               PARAM):         P      = generator.probe(parameter)
        def parseParameter(args:           Seq[String]):   PARAM  = generator.parseParameter(args)
        def main(args:                     Array[String]): Unit   = generator.main(args)
    )

  def instantiate(
    fullParam:    Any,
    instanceName: String,
    loc:          SourceLocation
  )(
    using Arena,
    Context,
    Block
  ): Operation =
    given sourcecode.File         = sourcecode.File(loc.file)
    given sourcecode.Line         = sourcecode.Line(loc.line)
    given sourcecode.Name.Machine = sourcecode.Name.Machine(instanceName)
    given InstanceContext         = new InstanceContext
    delegate(moduleName(fullParam)).instantiate(param(fullParam)).operation
