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
  segments.foreach(DeclaredName.require(_, "LayerPath segment"))
  def show: String = segments.mkString(".")

sealed trait ProtocolInterface derives CanEqual, upickle.default.ReadWriter

object ProtocolInterface:
  final case class Bundle(fields: Vector[Field])              extends ProtocolInterface:
    require(fields.nonEmpty, "Bundle must contain at least one field")
    require(fields.map(_.name).distinct.sizeIs == fields.size, "Bundle field names must be unique")
  final case class Vec(size: Int, element: ProtocolInterface) extends ProtocolInterface:
    require(size > 0, "Vec size must be positive")
    require(!element.isInstanceOf[Flipped], "Vec elements cannot be Flipped")
  final case class UInt(width: Int)                           extends ProtocolInterface:
    require(width > 0, "UInt width must be positive")
  final case class SInt(width: Int)                           extends ProtocolInterface:
    require(width > 0, "SInt width must be positive")
  case object Bool                                            extends ProtocolInterface
  case object Clock                                           extends ProtocolInterface
  case object Reset                                           extends ProtocolInterface

  /** Read-only reference to an internal signal, confined to a FIRRTL layer. */
  final case class Probe(inner: ProtocolInterface, layer: LayerPath) extends ProtocolInterface:
    require(!containsFlipped(inner), "a probe is one-directional: no Flipped inside")
    require(!containsProbe(inner), "a probe references data: no Probe inside a Probe")

  /** Direction reversal relative to the root, legal only directly as a field's type (zaozi's `Flipped`); alignment is
    * the unmarked case.
    */
  final case class Flipped(inner: ProtocolInterface) extends ProtocolInterface:
    require(!inner.isInstanceOf[Flipped], "Flipped(Flipped(_)) is meaningless")

  final case class Field(name: String, tpe: ProtocolInterface) derives upickle.default.ReadWriter:
    DeclaredName.require(name, "interface field name")

  private[syntheke] def containsFlipped(t: ProtocolInterface): Boolean = t match
    case Bundle(fields) => fields.exists(f => containsFlipped(f.tpe))
    case Vec(_, e)      => containsFlipped(e)
    case Probe(i, _)    => containsFlipped(i)
    case _: Flipped => true
    case _ => false

  private[syntheke] def containsProbe(t: ProtocolInterface): Boolean = t match
    case Bundle(fields) => fields.exists(f => containsProbe(f.tpe))
    case Vec(_, e)      => containsProbe(e)
    case Flipped(i)     => containsProbe(i)
    case _: Probe => true
    case _ => false

/** The top-level Bundle of a protocol port: the root and every nested Bundle carry at least one field (invariant
  * enforced by [[ProtocolInterface.Bundle]]).
  */
type ProtocolBundle = ProtocolInterface.Bundle

object ProtocolBundle:
  def apply(fields: ProtocolInterface.Field*): ProtocolBundle = ProtocolInterface.Bundle(fields.toVector)

  /** Replace every probe leaf by its inner type: the shape a probe sink receives after `ref.resolve` at its parent
    * wrapper. FIRRTL forbids input probe ports, so sink generator ports use the stripped interface; the probe-typed
    * contract stays the protocol-level truth.
    */
  def stripProbes(tpe: ProtocolInterface): ProtocolInterface = tpe match
    case ProtocolInterface.Bundle(fields) =>
      ProtocolInterface.Bundle(fields.map(f => f.copy(tpe = stripProbes(f.tpe))))
    case ProtocolInterface.Vec(n, e)      => ProtocolInterface.Vec(n, stripProbes(e))
    case ProtocolInterface.Flipped(t)     => ProtocolInterface.Flipped(stripProbes(t))
    case ProtocolInterface.Probe(i, _)    => stripProbes(i)
    case leaf                             => leaf

  /** All signal leaves of an interface with their paths and probe layers. */
  def leaves(tpe: ProtocolInterface, prefix: InterfacePath = InterfacePath.root)
    : Vector[(InterfacePath, ProtocolInterface)] =
    tpe match
      case ProtocolInterface.Bundle(fields) =>
        fields.flatMap(f => leaves(f.tpe, prefix.field(f.name)))
      case ProtocolInterface.Vec(n, elem)   =>
        (0 until n).toVector.flatMap(i => leaves(elem, prefix.index(i)))
      case ProtocolInterface.Flipped(t)     => leaves(t, prefix)
      case leaf                             => Vector(prefix -> leaf)

/** A path from an interface root: named-field selections and Vec indices (doc @sec-dv-protocol). */
final case class InterfacePath(segments: Vector[InterfacePath.Segment]) derives upickle.default.ReadWriter:
  def field(name:       String):        InterfacePath = InterfacePath(segments :+ InterfacePath.Segment.Field(name))
  def index(i:          Int):           InterfacePath = InterfacePath(segments :+ InterfacePath.Segment.Index(i))
  def isPrefixOf(other: InterfacePath): Boolean       = other.segments.startsWith(segments)
  def ++(other:         InterfacePath): InterfacePath = InterfacePath(segments ++ other.segments)
  def show:                             String        = segments.map {
    case InterfacePath.Segment.Field(n) => s".$n"
    case InterfacePath.Segment.Index(i) => s"[$i]"
  }.mkString

  /** Path as port-name segments: field names verbatim, Vec indices as digits. Unambiguous because interface field names
    * cannot start with a digit ([[DeclaredName]]).
    */
  def nameSegments: Vector[String] = segments.map {
    case InterfacePath.Segment.Field(n) => n
    case InterfacePath.Segment.Index(i) => i.toString
  }

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
