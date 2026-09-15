package me.jiuyang.zaozi.syntheke

import me.jiuyang.syntheke.{LayerPath, ProbePort, ProtocolInterface}
import me.jiuyang.zaozi.{DVInterface, TypeImpl, nameHierarchy}
import me.jiuyang.zaozi.reftpe.Node
import me.jiuyang.zaozi.valuetpe.*
import org.llvm.mlir.scalalib.capi.ir.{Context, Value, given}

import java.lang.foreign.Arena

private[jiuyang] object PublicProbes:
  def dataType[T <: Data & CanProbe](probe: RProbe[T]): T = probe._baseType

  def node[T <: Data](tpe: T, value: Value)(using Arena, Context, TypeImpl): Node[T] =
    require(tpe.toMlirType.equal(value.getType), "Node declaration must match the native value type")
    new Node[T]:
      val _tpe: T = tpe
      val _refer: Value = value

  def owns(declaration: DVInterface[?, ?], field: BundleField[?]): Boolean =
    declaration._elements.exists(_ eq field)

  def ports(declaration: DVInterface[?, ?], generatorName: String): Vector[ProbePort] =
    declaration._elements.toVector.map { field =>
      require(!field.isFlipped, s"generator $generatorName public Probe '${field.name}' must be an output")
      translate(field.dataType) match
        case probe: ProtocolInterface.Probe => ProbePort(field.name, probe)
        case _ =>
          throw new IllegalArgumentException(
            s"generator $generatorName public Probe '${field.name}' must be a reference port; " +
              "nested open aggregates of references are not supported"
          )
    }

  private def reference(probe: RProbe[?]): ProtocolInterface.Probe =
    ProtocolInterface.Probe(
      translate(probe._baseType),
      Some(LayerPath(probe._color.nameHierarchy.toVector))
    )

  private def translate(data: Data): ProtocolInterface = data match
    case ref: UInt       => ProtocolInterface.UInt(ref._width)
    case ref: SInt       => ProtocolInterface.SInt(ref._width)
    case ref: Bits       => ProtocolInterface.UInt(ref._width)
    case ref: Analog     => ProtocolInterface.Analog(ref._width)
    case _:   Bool       => ProtocolInterface.UInt(1)
    case _:   Reset      => ProtocolInterface.UInt(1)
    case _:   Clock      => ProtocolInterface.Clock
    case ref: Vec[?]     => ProtocolInterface.Vec(ref._count, translate(ref.elementType))
    case ref: RProbe[?] => reference(ref)
    case ref: Aggregate =>
      ProtocolInterface.Bundle(ref._elements.toVector.map { field =>
        val inner = translate(field.dataType)
        ProtocolInterface.Field(field.name, if field.isFlipped then ProtocolInterface.Flipped(inner) else inner)
      })
    case unsupported =>
      throw new IllegalArgumentException(s"Unsupported interface declaration type: ${unsupported.getClass.getName}")
