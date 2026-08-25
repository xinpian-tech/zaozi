// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.syntheke

/** Serializable hardware-interface description (doc @sec-protocol-interface).
  *
  * Negotiation manipulates this data; the Elaborate phase translates it to FIRRTL types. Design-protocol interfaces are
  * built from Bundle / Vec / UInt / SInt / Bool / Clock / Reset; verification-protocol interfaces wrap every signal
  * leaf in Probe carrying a LayerPath.
  */

/** Non-empty name sequence from the FIRRTL layer root, e.g. `verification.cosim`. */
final case class LayerPath(segments: Vector[String]) derives upickle.default.ReadWriter:
  require(segments.nonEmpty, "LayerPath must be non-empty")
  require(segments.forall(_.nonEmpty), "LayerPath segments must be non-empty strings")
  def show: String = segments.mkString(".")

sealed trait ProtocolInterface derives CanEqual, upickle.default.ReadWriter

object ProtocolInterface:
  final case class Bundle(fields: Vector[Field])              extends ProtocolInterface:
    require(fields.nonEmpty, "Bundle must contain at least one field")
    require(fields.map(_.name).distinct.sizeIs == fields.size, "Bundle field names must be unique")
  final case class Vec(size: Int, element: ProtocolInterface) extends ProtocolInterface:
    require(size > 0, "Vec size must be positive")
  final case class UInt(width: Int)                           extends ProtocolInterface:
    require(width > 0, "UInt width must be positive")
  final case class SInt(width: Int)                           extends ProtocolInterface:
    require(width > 0, "SInt width must be positive")
  case object Bool                                            extends ProtocolInterface
  case object Clock                                           extends ProtocolInterface
  case object Reset                                           extends ProtocolInterface

  /** Read-only reference to an internal signal, confined to a FIRRTL layer. */
  final case class Probe(inner: ProtocolInterface, layer: LayerPath) extends ProtocolInterface

  final case class Field(name: String, flip: Boolean, tpe: ProtocolInterface) derives upickle.default.ReadWriter:
    require(name.nonEmpty, "field name must be non-empty")

/** The top-level Bundle of a protocol port: the root and every nested Bundle carry at least one field (invariant
  * enforced by [[ProtocolInterface.Bundle]]).
  */
type ProtocolBundle = ProtocolInterface.Bundle

object ProtocolBundle:
  def apply(fields: ProtocolInterface.Field*): ProtocolBundle = ProtocolInterface.Bundle(fields.toVector)

  /** All signal leaves of an interface with their paths and probe layers. */
  def leaves(tpe: ProtocolInterface, prefix: InterfacePath = InterfacePath.root)
    : Vector[(InterfacePath, ProtocolInterface)] =
    tpe match
      case ProtocolInterface.Bundle(fields) =>
        fields.flatMap(f => leaves(f.tpe, prefix.field(f.name)))
      case ProtocolInterface.Vec(n, elem)   =>
        (0 until n).toVector.flatMap(i => leaves(elem, prefix.index(i)))
      case leaf                             => Vector(prefix -> leaf)

/** A path from an interface root: named-field selections and Vec indices (doc @sec-dv-protocol). */
final case class InterfacePath(segments: Vector[InterfacePath.Segment]) derives upickle.default.ReadWriter:
  def field(name:       String):        InterfacePath = InterfacePath(segments :+ InterfacePath.Segment.Field(name))
  def index(i:          Int):           InterfacePath = InterfacePath(segments :+ InterfacePath.Segment.Index(i))
  def isPrefixOf(other: InterfacePath): Boolean       = other.segments.startsWith(segments)
  def show:                             String        = segments.map {
    case InterfacePath.Segment.Field(n) => s".$n"
    case InterfacePath.Segment.Index(i) => s"[$i]"
  }.mkString

object InterfacePath:
  val root: InterfacePath = InterfacePath(Vector.empty)
  enum Segment derives CanEqual, upickle.default.ReadWriter:
    case Field(name: String)
    case Index(i: Int)

  /** Resolve a path inside an interface; the last segment must land on a Bundle for sink paths. */
  def resolve(tpe: ProtocolInterface, path: InterfacePath): Option[ProtocolInterface] =
    path.segments.foldLeft(Option(tpe)) {
      case (Some(ProtocolInterface.Bundle(fields)), Segment.Field(n))                        => fields.find(_.name == n).map(_.tpe)
      case (Some(ProtocolInterface.Vec(size, elem)), Segment.Index(i)) if i >= 0 && i < size => Some(elem)
      case _                                                                                 => None
    }

/** Aggregated verification interfaces returned by `DVProtocol.interfacesOf` (doc @sec-dv-protocol). */
final case class DVInterfaces(
  sources:   Vector[ProtocolBundle],
  sink:      ProtocolBundle,
  sinkPaths: Vector[InterfacePath])
