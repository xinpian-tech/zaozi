package me.jiuyang.syntheke.zaozi

import me.jiuyang.syntheke.*
import me.jiuyang.zaozi.{DVInterface, HWRecord, Parameter}
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.{Interface, Node}
import me.jiuyang.zaozi.syntheke.PublicProbes
import me.jiuyang.zaozi.valuetpe.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

import java.lang.foreign.Arena
import upickle.default.Writer

private[zaozi] final class ZaoziProbeDeclaration(
  val generator: AnyRef,
  val declaration: DVInterface[?, ?],
  val generatorName: String,
  val parameter: ujson.Value,
  val ports: Vector[ProbePort]) extends ProbeDeclaration

final class Probe[T <: Data & CanProbe] private[zaozi] (
  val dataType: T,
  private[zaozi] val node: ResolvedPublicPort,
  private val generatorName: String,
  private val parameter: ujson.Value):
  private[zaozi] def id: ModuleNodeId = node.id

object Probe:
  given [T <: Data & CanProbe]: Writer[Probe[T]] = upickle.default.writer[ujson.Value].comap { handle =>
    ujson.Obj(
      "binding" -> upickle.default.writeJs(ProbeBindings.from(Vector(handle.node)).ports.head),
      "generator" -> handle.generatorName,
      "parameter" -> handle.parameter
    )
  }

abstract class ProbeIO[FP <: Parameter, A](parameter: FP, observations: A)(using shape: ProbeShape[A])
  extends HWRecord(parameter):
  private[zaozi] val handles: Vector[Probe[?]] = shape.handles(observations).distinctBy(_.node)
  private[zaozi] val plan: ProbeBindings = ProbeBindings.from(handles.map(_.node))

  handles.zip(plan.ports).foreach { (handle, binding) =>
    Flipped(binding.portName, handle.dataType)
  }

final class BoundProbe[T <: Data & CanProbe] private[zaozi] (
  private val value: Node[T]):
  def read(): Node[T] = value

extension [T <: Data & CanProbe](handle: Probe[T])
  def bind[I <: ProbeIO[?, ?]](io: Interface[I])(
    using Arena, Context, Block, sourcecode.File, sourcecode.Line
  ): BoundProbe[T] =
    require(io.getType.handles.exists(_.node eq handle.node), s"${handle.id.show}: source is not observed by this interface")
    val binding = io.getType.plan.ports.find(_.source == handle.id).get
    val value = PublicProbes.node(handle.dataType, io.field[Data](binding.portName).refer)
    new BoundProbe(value)
