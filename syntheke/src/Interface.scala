package me.jiuyang.syntheke


final case class LayerPath(segments: Vector[String]):
  require(segments.nonEmpty, "LayerPath must be non-empty")
  segments.foreach(DeclaredName.require(_, "LayerPath segment"))

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
    require(fields.map(_.name).distinct.sizeIs == fields.size, "Bundle field names must be unique")
  final case class Vec(size: Int, element: ProtocolInterface) extends ProtocolInterface:
    require(size >= 0, "Vec size must be nonnegative")
    require(!element.isInstanceOf[Flipped], "Vec elements cannot be Flipped")

  final case class Bits(width: Int)   extends ProtocolInterface:
    require(width >= 0, "Bits width must be nonnegative")
  final case class UInt(width: Int)   extends ProtocolInterface:
    require(width >= 0, "UInt width must be nonnegative")
  final case class SInt(width: Int)   extends ProtocolInterface:
    require(width >= 0, "SInt width must be nonnegative")
  final case class Analog(width: Int) extends ProtocolInterface:
    require(width >= 0, "Analog width must be nonnegative")
  case object Bool                    extends ProtocolInterface
  case object Clock                   extends ProtocolInterface
  case object Reset                   extends ProtocolInterface
  case object AsyncReset              extends ProtocolInterface

  final case class Probe(inner: ProtocolInterface, layer: Option[LayerPath])
      extends ProtocolInterface:
    require(!containsFlipped(inner), "a Probe's referenced data cannot contain Flipped fields")
    require(!containsProbe(inner), "a probe references data: no Probe inside a Probe")

  object Probe:
    given upickle.default.ReadWriter[Probe] =
      upickle.default
        .readwriter[ProtocolInterface]
        .bimap[Probe](
          identity,
          {
            case probe: Probe => probe
            case other => throw new IllegalArgumentException(s"expected a Probe reference, got $other")
          }
        )

  final case class Flipped(inner: ProtocolInterface) extends ProtocolInterface:
    require(!inner.isInstanceOf[Flipped], "Flipped(Flipped(_)) is meaningless")

  final case class Field(name: String, tpe: ProtocolInterface):
    DeclaredName.require(name, "interface field name")

  given upickle.default.ReadWriter[ProtocolInterface] =
    upickle.default.readwriter[ujson.Value].bimap[ProtocolInterface](encode, decode)

  private def encode(t: ProtocolInterface): ujson.Value = t match
    case Bundle(fields)         =>
      ujson.Obj(
        "type"   -> ujson.Str("bundle"),
        "fields" -> ujson.Arr.from(fields.map { f =>
          ujson.Obj("name" -> ujson.Str(f.name), "tpe" -> encode(f.tpe))
        })
      )
    case Vec(n, e)              => ujson.Obj("type" -> ujson.Str("vec"), "size" -> ujson.Num(n), "element" -> encode(e))
    case Flipped(i)             => ujson.Obj("type" -> ujson.Str("flipped"), "inner" -> encode(i))
    case Bits(w)                => ujson.Obj("type" -> ujson.Str("bits"), "width" -> ujson.Num(w))
    case UInt(w)                => ujson.Obj("type" -> ujson.Str("uint"), "width" -> ujson.Num(w))
    case SInt(w)                => ujson.Obj("type" -> ujson.Str("sint"), "width" -> ujson.Num(w))
    case Analog(w)              => ujson.Obj("type" -> ujson.Str("analog"), "width" -> ujson.Num(w))
    case Bool                   => ujson.Obj("type" -> ujson.Str("bool"))
    case Clock                  => ujson.Obj("type" -> ujson.Str("clock"))
    case Reset                  => ujson.Obj("type" -> ujson.Str("reset"))
    case AsyncReset             => ujson.Obj("type" -> ujson.Str("asyncReset"))
    case Probe(i, l)            =>
      ujson.Obj(
        "type"  -> ujson.Str("probe"),
        "inner" -> encode(i),
        "layer" -> l.fold[ujson.Value](ujson.Null)(upickle.default.writeJs(_))
      )

  private def decode(v: ujson.Value): ProtocolInterface = v("type").str match
    case "bundle"     => Bundle(v("fields").arr.toVector.map(f => Field(f("name").str, decode(f("tpe")))))
    case "vec"        => Vec(v("size").num.toInt, decode(v("element")))
    case "flipped"    => Flipped(decode(v("inner")))
    case "bits"       => Bits(v("width").num.toInt)
    case "uint"       => UInt(v("width").num.toInt)
    case "sint"       => SInt(v("width").num.toInt)
    case "analog"     => Analog(v("width").num.toInt)
    case "bool"       => Bool
    case "clock"      => Clock
    case "reset"      => Reset
    case "asyncReset" => AsyncReset
    case "probe"      =>
      Probe(
        decode(v("inner")),
        if v("layer") == ujson.Null then None else Some(upickle.default.read[LayerPath](v("layer")))
      )

  private def containsFlipped(t: ProtocolInterface): Boolean = t match
    case Bundle(fields) => fields.exists(f => containsFlipped(f.tpe))
    case Vec(_, e)      => containsFlipped(e)
    case Probe(i, _)    => containsFlipped(i)
    case _: Flipped => true
    case _ => false

  private def containsProbe(t: ProtocolInterface): Boolean = t match
    case Bundle(fields) => fields.exists(f => containsProbe(f.tpe))
    case Vec(_, e)      => containsProbe(e)
    case Flipped(i)     => containsProbe(i)
    case _: Probe => true
    case _ => false

  private[syntheke] def leaves(tpe: ProtocolInterface, prefix: InterfacePath = InterfacePath.root)
    : Vector[(InterfacePath, ProtocolInterface)] =
    tpe match
      case Bundle(fields) =>
        fields.flatMap(f => leaves(f.tpe, prefix.field(f.name)))
      case Vec(n, elem)   =>
        (0 until n).toVector.flatMap(i => leaves(elem, prefix.index(i)))
      case Flipped(t)     => leaves(t, prefix)
      case leaf           => Vector(prefix -> leaf)

private[syntheke] final case class InterfacePath(segments: Vector[InterfacePath.Segment]):
  def field(name: String): InterfacePath = InterfacePath(segments :+ InterfacePath.Segment.Field(name))
  def index(i:    Int):    InterfacePath = InterfacePath(segments :+ InterfacePath.Segment.Index(i))
  def show:                String        = segments.map {
    case InterfacePath.Segment.Field(n) => s".$n"
    case InterfacePath.Segment.Index(i) => s"[$i]"
  }.mkString

private[syntheke] object InterfacePath:
  val root: InterfacePath = InterfacePath(Vector.empty)
  enum Segment derives CanEqual:
    case Field(name: String)
    case Index(i: Int)
