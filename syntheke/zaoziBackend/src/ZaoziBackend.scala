// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.syntheke.zaozi

import scala.collection.mutable

import me.jiuyang.syntheke.*
import me.jiuyang.syntheke.circt.GeneratorBackend
import me.jiuyang.zaozi.{DVInterface, Generator, HWInterface, InstanceContext, LayerInterface, Parameter}
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.{Interface, ProbeInterface}
import org.llvm.mlir.scalalib.capi.ir.{Block, Context, Operation}

import java.lang.foreign.Arena

/** The zaozi backend: a syntheke generator entry enacted by a zaozi [[Generator]].
  *
  * The entry's full parameter IS the generator's zaozi [[Parameter]] — one type, no conversion: the `parameters`
  * computation in the design body produces exactly the module parameter the zaozi generator consumes (doc
  * @sec-two-layer-params).
  */
final class ZaoziBackend[
  PARAM <: Parameter,
  L <: LayerInterface[PARAM],
  I <: HWInterface[PARAM],
  P <: DVInterface[PARAM, L]
](val entry:     GeneratorEntry[PARAM],
  val generator: Generator[PARAM, L, I, P])
    extends GeneratorBackend:

  private def param(fullParam: Any): PARAM = fullParam.asInstanceOf[PARAM]

  def moduleName(fullParam: Any): String =
    GeneratorBackend.canonicalModuleName(entry, param(fullParam))

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
    loc:          (sourcecode.File, sourcecode.Line)
  )(
    using Arena,
    Context,
    Block
  ): Operation =
    given sourcecode.File         = loc._1
    given sourcecode.Line         = loc._2
    given sourcecode.Name.Machine = sourcecode.Name.Machine(instanceName)
    given InstanceContext         = new InstanceContext
    delegate(moduleName(fullParam)).instantiate(param(fullParam)).operation
