package me.jiuyang.syntheke.zaozi

import scala.collection.mutable

import me.jiuyang.syntheke.*
import me.jiuyang.syntheke.circt.GeneratorBackend
import me.jiuyang.zaozi.{
  DVInterface,
  Generator,
  HWInterface,
  InstanceContext,
  LayerInterface,
  Parameter
}
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.{Interface, ProbeInterface}
import me.jiuyang.zaozi.syntheke.PublicProbes
import org.llvm.mlir.scalalib.capi.ir.{Block, Context, Operation}

import java.lang.foreign.Arena
import upickle.default.Writer

object zaozi:
  def apply[PARAM <: Parameter, L <: LayerInterface[PARAM], I <: HWInterface[PARAM], P <: DVInterface[PARAM, L]](
    generator: Generator[PARAM, L, I, P]
  )(
    using Writer[PARAM]
  ): GeneratorDefinition[PARAM] =
    val name = generator.getClass.getSimpleName.stripSuffix("$").stripSuffix("Gen")
    GeneratorBackend.define[PARAM](name)(
      fullParam => describePublicProbes(name, generator, fullParam)
    )(definition => new ZaoziBackend(definition, generator))

  def testbench[PARAM <: Parameter, L <: LayerInterface[PARAM], I <: ProbeIO[PARAM, ?], P <: DVInterface[PARAM, L]](
    generator: Generator[PARAM, L, I, P]
  )(
    using Writer[PARAM]
  ): TestbenchDefinition[PARAM] =
    val name = generator.getClass.getSimpleName.stripSuffix("$").stripSuffix("Gen")
    GeneratorBackend.defineTestbench[PARAM](name)(
      probeFn = fullParam => describePublicProbes(name, generator, fullParam),
      observationFn = fullParam => generator.interface(fullParam).plan
    )(definition => new ZaoziBackend(definition, generator))

  private def describePublicProbes[
    PARAM <: Parameter,
    L <: LayerInterface[PARAM],
    I <: HWInterface[PARAM],
    P <: DVInterface[PARAM, L]
  ](
    name: String,
    generator: Generator[PARAM, L, I, P],
    fullParam: PARAM
  )(using Writer[PARAM]): ProbeDeclaration =
    val declaration = generator.probe(fullParam)
    new ZaoziProbeDeclaration(
      generator,
      declaration,
      name,
      upickle.default.writeJs(fullParam),
      PublicProbes.ports(declaration, name)
    )

private final class ZaoziBackend[
  PARAM <: Parameter,
  L <: LayerInterface[PARAM],
  I <: HWInterface[PARAM],
  P <: DVInterface[PARAM, L]
](definition: GeneratorDefinition[PARAM],
  generator:      Generator[PARAM, L, I, P])
    extends GeneratorBackend:

  private def param(fullParam: Any): PARAM = fullParam.asInstanceOf[PARAM]

  def moduleName(fullParam: Any): String =
    val fp = param(fullParam)
    val abi = definition match
      case testbench: TestbenchDefinition[PARAM] => Some(upickle.default.writeJs(testbench.observations(fp)))
      case _ => None
    GeneratorBackend.canonicalModuleName(definition, fp, abi)

  override def layers(fullParam: Any): Seq[Vector[String]] =
    def paths(l: me.jiuyang.zaozi.Layer, prefix: Vector[String]): Seq[Vector[String]] =
      val here = prefix :+ l.name
      here +: l.children.flatMap(paths(_, here))
    generator.layers(param(fullParam)).layers.flatMap(paths(_, Vector.empty))

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
