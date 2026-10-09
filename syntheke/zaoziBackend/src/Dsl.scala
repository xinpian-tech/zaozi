package me.jiuyang.syntheke.zaozi

import java.lang.foreign.Arena
import me.jiuyang.syntheke.*
import me.jiuyang.zaozi.{DVInterface, Generator, HWInterface, LayerInterface, Parameter}
import me.jiuyang.zaozi.reftpe.{Interface, Node}
import me.jiuyang.zaozi.valuetpe.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}
import upickle.default.Writer

object zaozi:
  def apply[FP <: Parameter, L <: LayerInterface[FP], I <: HWInterface[FP], P <: DVInterface[FP, L]](
    generator: Generator[FP, L, I, P]
  )(using Writer[FP]): GeneratorDefinition[FP] = ZaoziDefinitions(generator)

/** A probe contract served by zaozi generators: probes of it publish data of type `T` and carry parameters `P`. */
final class ProbeBindingFor[P: Writer, T <: Data & CanProbe] private () extends ProbeContract[P]:
  def from[FP <: Parameter, L <: LayerInterface[FP], I <: HWInterface[FP], D <: DVInterface[FP, L]](
    generator: Generator[FP, L, I, D]
  )(
    select: (FP, D) => Option[(P, BundleField[RProbe[T]])]
  ): ProbeSelector[FP, P] = ProbeBindingSupport.selector(this, generator)(select)

object ProbeBindingFor:
  def apply[P: Writer, T <: Data & CanProbe](): ProbeBindingFor[P, T] = new ProbeBindingFor[P, T]()

extension [P](resolved: ResolvedProbe[P])
  def observe[T <: Data & CanProbe](using association: ProbeBindingFor[P, T]): Probe[T] =
    ProbeBindingSupport.observe(association, resolved)

extension [T <: Data & CanProbe](handle: Probe[T])
  def bind[I <: ProbeIO[?, ?]](io: Interface[I])(
    using Arena, Context, Block, sourcecode.File, sourcecode.Line
  ): BoundProbe[T] = ProbeAccess.bind(handle, io)

extension [T <: Data & CanProbe](probe: BoundProbe[T])
  def read(): Node[T] = probe.value
