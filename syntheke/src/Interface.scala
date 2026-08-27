// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.syntheke

/** Serializable hardware-interface description (doc @sec-protocol-interface).
  *
  * Negotiation manipulates this data; the Elaborate phase translates it to FIRRTL types. Design-protocol interfaces are
  * built from Bundle / Vec / UInt / SInt / Bool / Clock / Reset; verification-protocol interfaces wrap every signal
  * leaf in Probe carrying a LayerPath.
  *
  * Every type here has exactly one JSON encoding — the custom ReadWriters below — used by the manifest, the tooling
  * exports and the canonical linking hash alike.
  */

/** Non-empty name sequence from the FIRRTL layer root, e.g. `verification.cosim`; encoded as a bare string array. */
final case class LayerPath(segments: Vector[String]):
  require(segments.nonEmpty, "LayerPath must be non-empty")
  segments.foreach(DeclaredName.require(_, "LayerPath segment"))
  def show: String = segments.mkString(".")

object LayerPath:
  given upickle.default.ReadWriter[LayerPath] =
    upickle.default
      .readwriter[ujson.Value]
      .bimap[LayerPath](
        l => ujson.Arr.from(l.segments.map(ujson.Str(_))),
        v => LayerPath(v.arr.toVector.map(_.str))
      )

sealed trait ProtocolInterface derives CanEqual

object ProtocolInterface:
  final case class Bundle(fields: Vector[Field])              extends ProtocolInterface:
    require(fields.nonEmpty, "Bundle must contain at least one field")
    require(fields.map(_.name).distinct.sizeIs == fields.size, "Bundle field names must be unique")
  final case class Vec(size: Int, element: ProtocolInterface) extends ProtocolInterface:
    require(size > 0, "Vec size must be positive")
    require(!element.isInstanceOf[Flipped], "Vec elements cannot be Flipped")

  /** Unsigned integer of positive width. `UInt(1)` and [[Bool]] translate to the same hardware type but stay distinct
    * declarations — what the author wrote is what the spec and the exports carry, told apart by their type tags.
    */
  final case class UInt(width: Int) extends ProtocolInterface:
    require(width > 0, "UInt width must be positive")
  final case class SInt(width: Int) extends ProtocolInterface:
    require(width > 0, "SInt width must be positive")
  case object Bool                  extends ProtocolInterface
  case object Clock                 extends ProtocolInterface
  case object Reset                 extends ProtocolInterface

  /** Read-only reference to an internal signal, confined to a FIRRTL layer. */
  final case class Probe(inner: ProtocolInterface, layer: LayerPath) extends ProtocolInterface:
    require(!containsFlipped(inner), "a probe is one-directional: no Flipped inside")
    require(!containsProbe(inner), "a probe references data: no Probe inside a Probe")

  /** Direction reversal relative to the root, legal only directly as a field's type (zaozi's `Flipped`); alignment is
    * the unmarked case.
    */
  final case class Flipped(inner: ProtocolInterface) extends ProtocolInterface:
    require(!inner.isInstanceOf[Flipped], "Flipped(Flipped(_)) is meaningless")

  final case class Field(name: String, tpe: ProtocolInterface):
    DeclaredName.require(name, "interface field name")

  given upickle.default.ReadWriter[ProtocolInterface] =
    upickle.default.readwriter[ujson.Value].bimap[ProtocolInterface](encode, decode)

  private def encode(t: ProtocolInterface): ujson.Value = t match
    case Bundle(fields) =>
      ujson.Obj(
        "type"   -> ujson.Str("bundle"),
        "fields" -> ujson.Arr.from(fields.map { f =>
          ujson.Obj("name" -> ujson.Str(f.name), "tpe" -> encode(f.tpe))
        })
      )
    case Vec(n, e)      => ujson.Obj("type" -> ujson.Str("vec"), "size" -> ujson.Num(n), "element" -> encode(e))
    case Flipped(i)     => ujson.Obj("type" -> ujson.Str("flipped"), "inner" -> encode(i))
    case UInt(w)        => ujson.Obj("type" -> ujson.Str("uint"), "width" -> ujson.Num(w))
    case SInt(w)        => ujson.Obj("type" -> ujson.Str("sint"), "width" -> ujson.Num(w))
    case Bool           => ujson.Obj("type" -> ujson.Str("bool"))
    case Clock          => ujson.Obj("type" -> ujson.Str("clock"))
    case Reset          => ujson.Obj("type" -> ujson.Str("reset"))
    case Probe(i, l)    =>
      ujson.Obj("type" -> ujson.Str("probe"), "inner" -> encode(i), "layer" -> upickle.default.writeJs(l))

  private def decode(v: ujson.Value): ProtocolInterface = v("type").str match
    case "bundle"  => Bundle(v("fields").arr.toVector.map(f => Field(f("name").str, decode(f("tpe")))))
    case "vec"     => Vec(v("size").num.toInt, decode(v("element")))
    case "flipped" => Flipped(decode(v("inner")))
    case "uint"    => UInt(v("width").num.toInt)
    case "sint"    => SInt(v("width").num.toInt)
    case "bool"    => Bool
    case "clock"   => Clock
    case "reset"   => Reset
    case "probe"   => Probe(decode(v("inner")), upickle.default.read[LayerPath](v("layer")))

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

/** A path from an interface root: named-field selections and Vec indices (doc @sec-dv-protocol); encoded as an array of
  * `{"field": name}` / `{"index": i}` objects.
  */
final case class InterfacePath(segments: Vector[InterfacePath.Segment]):
  def field(name: String): InterfacePath = InterfacePath(segments :+ InterfacePath.Segment.Field(name))
  def index(i:    Int):    InterfacePath = InterfacePath(segments :+ InterfacePath.Segment.Index(i))
  def show:                String        = segments.map {
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
  enum Segment derives CanEqual:
    case Field(name: String)
    case Index(i: Int)

  given upickle.default.ReadWriter[InterfacePath] =
    upickle.default
      .readwriter[ujson.Value]
      .bimap[InterfacePath](
        p =>
          ujson.Arr.from(p.segments.map {
            case Segment.Field(n) => ujson.Obj("field" -> ujson.Str(n))
            case Segment.Index(i) => ujson.Obj("index" -> ujson.Num(i))
          }),
        v =>
          InterfacePath(v.arr.toVector.map { o =>
            if o.obj.contains("field") then Segment.Field(o("field").str) else Segment.Index(o("index").num.toInt)
          })
      )
