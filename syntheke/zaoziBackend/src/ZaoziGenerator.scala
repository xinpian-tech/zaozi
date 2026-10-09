package me.jiuyang.syntheke.zaozi

import scala.collection.mutable

import me.jiuyang.syntheke.*
import me.jiuyang.syntheke.circt.{CirctGenerator, Instantiated}
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
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

import java.lang.foreign.Arena
import upickle.default.Writer

/** A zaozi generator as a syntheke generator: its public probes and observations come from its probe declaration and
  * interface, its module from elaborating it under the name syntheke gives the full parameter.
  */
private[zaozi] final class ZaoziGenerator[
  PARAM <: Parameter,
  L <: LayerInterface[PARAM],
  I <: HWInterface[PARAM],
  P <: DVInterface[PARAM, L]
](generator: Generator[PARAM, L, I, P])(using Writer[PARAM])
    extends CirctGenerator[PARAM](generator.getClass.getSimpleName.stripSuffix("$").stripSuffix("Gen")):

  def probes(fullParam: PARAM): ProbeDeclaration =
    val declaration = generator.probe(fullParam)
    new ZaoziProbeDeclaration(generator, declaration, PublicProbes.ports(declaration, name))

  def observations(fullParam: PARAM): ProbeBindings = generator.interface(fullParam) match
    case observed: ProbeIO[?] => observed.plan
    case _                    => ProbeBindings.empty

  def layers(fullParam: PARAM): LayerTree =
    def paths(l: me.jiuyang.zaozi.Layer, prefix: Vector[String]): Seq[Vector[String]] =
      val here = prefix :+ l.name
      here +: l.children.flatMap(paths(_, here))
    generator.layers(fullParam).layers.flatMap(paths(_, Vector.empty)).foldLeft(LayerTree.empty)((t, p) => t.add(LayerPath(p)))

  private val renamed = mutable.Map.empty[String, Generator[PARAM, L, I, P]]
  private def named(module: String): Generator[PARAM, L, I, P] =
    renamed.getOrElseUpdate(
      module,
      new Generator[PARAM, L, I, P]:
        override def moduleName(parameter: PARAM):         String = module
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

  def instantiate(fullParam: PARAM, instanceName: String, loc: SourceLoc)(using Arena, Context, Block): Instantiated =
    given sourcecode.File         = loc._1
    given sourcecode.Line         = loc._2
    given sourcecode.Name.Machine = sourcecode.Name.Machine(instanceName)
    given InstanceContext         = new InstanceContext
    val (instance, definitions)   = Elaborate.collect(named(moduleName(fullParam)).instantiate(fullParam).operation)
    Instantiated(instance, definitions)
