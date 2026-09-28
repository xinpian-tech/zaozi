package me.jiuyang.syntheke.demo

import me.jiuyang.syntheke.*
import upickle.default.Writer

def log2Up(x: Long): Int =
  require(x >= 1, s"log2Up($x)")
  if x <= 1 then 0 else 64 - java.lang.Long.numberOfLeadingZeros(x - 1)

final case class AddressSet(base: Long, mask: Long) derives Writer:
  require(base >= 0 && mask >= 0, s"illegal AddressSet($base, $mask)")
  require((base & mask) == 0L, f"AddressSet base 0x$base%x must be aligned to its mask 0x$mask%x")

  def overlaps(that: AddressSet): Boolean = ((that.base ^ base) & ~(mask | that.mask)) == 0L

  def max:  Long   = base | mask
  def show: String = f"0x$base%x/0x$mask%x"

object AddressSet:
  def misaligned(base: Long, size: Long): Vector[AddressSet] =
    require(base >= 0 && size > 0, s"illegal misaligned($base, $size)")
    @scala.annotation.tailrec
    def loop(base: Long, size: Long, acc: Vector[AddressSet]): Vector[AddressSet] =
      if size == 0 then acc
      else
        val baseAlign = base & -base
        val sizeAlign = 1L << (63 - java.lang.Long.numberOfLeadingZeros(size))
        val step      = if baseAlign == 0 || baseAlign > sizeAlign then sizeAlign else baseAlign
        loop(base + step, size - step, acc :+ AddressSet(base, step - 1))
    loop(base, size, Vector.empty)

final case class IdRange(start: Int, end: Int) derives Writer:
  require(0 <= start && start < end, s"illegal IdRange($start, $end)")
  def overlaps(that: IdRange): Boolean = start < that.end && that.start < end
  def shift(offset:  Int):     IdRange = IdRange(start + offset, end + offset)

final case class TransferSizes(min: Int, max: Int) derives Writer:
  require(0 <= min && min <= max, s"illegal TransferSizes($min, $max)")
  require((min == 0) == (max == 0), s"illegal TransferSizes($min, $max)")
  require(min == 0 || Integer.bitCount(min) == 1, s"TransferSizes min $min is not a power of two")
  require(max == 0 || Integer.bitCount(max) == 1, s"TransferSizes max $max is not a power of two")
final case class AxiMasterParams(
  name:      String,
  id:        IdRange,
  aligned:   Boolean = false,
  maxFlight: Option[Int] = None)
    derives Writer

final case class AxiMasterPort(masters: Vector[AxiMasterParams]) derives Writer:
  require(masters.nonEmpty, "AxiMasterPort needs at least one master")
  def endId:     Int                                        = masters.map(_.id.end).max
  def idBits:    Int                                        = math.max(1, log2Up(endId.toLong))
  def idOverlap: Option[(AxiMasterParams, AxiMasterParams)] =
    masters.combinations(2).collectFirst { case Seq(x, y) if x.id.overlaps(y.id) => (x, y) }

enum RegionType derives CanEqual, Writer:
  case Cached, Tracked, Uncached, Idempotent, Volatile, PutEffects, GetEffects

final case class AxiSlaveParams(
  name:          String,
  address:       Vector[AddressSet],
  regionType:    RegionType,
  executable:    Boolean,
  supportsWrite: TransferSizes,
  supportsRead:  TransferSizes,
  interleavedId: Option[Int] = None)
    derives Writer:
  require(address.nonEmpty, s"slave '$name' needs at least one address set")
  def maxTransfer: Int  = math.max(supportsWrite.max, supportsRead.max)
  def maxAddress:  Long = address.map(_.max).max

final case class AxiSlavePort(
  slaves:         Vector[AxiSlaveParams],
  beatBytes:      Int,
  idCapacityBits: Int,
  minLatency:     Int)
    derives Writer:
  require(slaves.nonEmpty, "AxiSlavePort needs at least one slave")
  require(Integer.bitCount(beatBytes) == 1, s"beatBytes $beatBytes is not a power of two")
  def maxTransfer:    Int                                              = slaves.map(_.maxTransfer).max
  def maxAddress:     Long                                             = slaves.map(_.maxAddress).max
  def addressOverlap: Option[(String, AddressSet, String, AddressSet)] =
    val all = slaves.flatMap(s => s.address.map(a => (s.name, a)))
    all.combinations(2).collectFirst { case Seq((sn, sa), (tn, ta)) if sa.overlaps(ta) => (sn, sa, tn, ta) }

final case class AxiEdgeParams(
  master:   AxiMasterPort,
  slave:    AxiSlavePort,
  addrBits: Int,
  dataBits: Int,
  idBits:   Int)
    derives Writer

object Axi4 extends Protocol:
  type Down = AxiMasterPort
  type Up   = AxiSlavePort
  type Edge = AxiEdgeParams

  val carries: Set[Domain] = Set.empty

  def negotiate(
    m:       AxiMasterPort,
    s:       AxiSlavePort,
    domains: EdgeDomains
  ): Either[Violation, (AxiEdgeParams, Vector[Constraint])] =
    def fail(msg: String) = Left(Violation(msg))
    m.idOverlap match
      case Some((x, y)) => return fail(s"master id ranges of '${x.name}' and '${y.name}' overlap")
      case None         => ()
    s.addressOverlap match
      case Some((xn, xa, yn, ya)) =>
        return fail(s"slave addresses overlap: '$xn' ${xa.show} vs '$yn' ${ya.show}")
      case None                   => ()
    val idBits            = m.idBits
    if idBits > s.idCapacityBits then
      return fail(
        s"masters need $idBits id bits (endId=${m.endId}) but the downstream absorbs at most ${s.idCapacityBits}"
      )
    if s.maxTransfer > s.beatBytes * 256 then
      return fail(s"maxTransfer ${s.maxTransfer} unencodable in AxLEN on a ${s.beatBytes}B bus")
    val domainChecks      = Vector(ClockDomain, ResetDomain).map { domain =>
      val out = domains.outward(domain)
      val in  = domains.inward(domain)
      Seq(out, in).check { view =>
        if view.sameIdentity(out, in) then Right(())
        else Left(Violation(s"${domain.key.show} differs between ${out.key.node.show} and ${in.key.node.show}"))
      }
    }
    Right(
      (
        AxiEdgeParams(
          master = m,
          slave = s,
          addrBits = math.max(1, log2Up(s.maxAddress + 1)),
          dataBits = s.beatBytes * 8,
          idBits = idBits
        ),
        domainChecks :+ PowerDomain.compatible(domains, allowModel = false)
      )
    )

  def interface(e: AxiEdgeParams): ProtocolInterface.Bundle =
    import ProtocolInterface.*
    def channel(payload: (String, ProtocolInterface)*): Bundle =
      Bundle(
        Vector(
          Field("valid", Bool),
          Field("ready", Flipped(Bool)),
          Field("bits", Bundle(payload.toVector.map((n, t) => Field(n, t))))
        )
      )
    val addr = ("addr", Bits(e.addrBits))
    val id0  = ("id", Bits(e.idBits))
    Bundle(
      Vector(
        Field("aw", channel(id0, addr, "len" -> Bits(8), "size" -> Bits(3), "burst" -> Bits(2))),
        Field("w", channel("data" -> Bits(e.dataBits), "strb" -> Bits(e.dataBits / 8), "last" -> Bool)),
        Field("b", Flipped(channel(id0, "resp" -> Bits(2)))),
        Field("ar", channel(id0, addr, "len" -> Bits(8), "size" -> Bits(3), "burst" -> Bits(2))),
        Field("r", Flipped(channel(id0, "data" -> Bits(e.dataBits), "resp" -> Bits(2), "last" -> Bool)))
      )
    )

  val downWriter: upickle.default.Writer[AxiMasterPort] = summon
  val upWriter:   upickle.default.Writer[AxiSlavePort]  = summon
  val edgeWriter: upickle.default.Writer[AxiEdgeParams] = summon

object Axi4Xbar:

  def localBits(ports: Vector[AxiMasterPort]): Int = ports.map(_.idBits).max

  def prefixBits(n: Int): Int = log2Up(n.toLong)

  def mapInputs(ports: Vector[AxiMasterPort]): AxiMasterPort =
    val local = localBits(ports)
    AxiMasterPort(
      ports.zipWithIndex.flatMap { (p, i) =>
        p.masters.map(m => m.copy(id = m.id.shift(i << local)))
      }
    )

  def aggregate(ups: Vector[AxiSlavePort], nInputs: Int): Either[Violation, AxiSlavePort] =
    val widths = ups.map(_.beatBytes).distinct
    if widths.sizeIs > 1 then
      return Left(Violation(s"xbar data widths don't match: beatBytes ${widths.mkString(" vs ")}"))
    val slaves = ups.flatMap(_.slaves)
    val port   = AxiSlavePort(
      slaves = slaves,
      beatBytes = widths.head,
      idCapacityBits = ups.map(_.idCapacityBits).min - prefixBits(nInputs),
      minLatency = ups.map(_.minLatency).min
    )
    port.addressOverlap match
      case Some((xn, xa, yn, ya)) =>
        Left(Violation(s"slave addresses overlap: '$xn' ${xa.show} vs '$yn' ${ya.show}"))
      case None                   => Right(port)
