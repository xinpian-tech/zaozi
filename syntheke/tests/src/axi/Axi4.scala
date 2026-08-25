// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.syntheke.tests.axi

import me.jiuyang.syntheke.*
import upickle.default.ReadWriter

/** An AXI4 negotiation protocol modeled on rocket-chip's `amba.axi4.Parameters`:
  *
  *   - `Down` is the master port: masters with disjoint id ranges (AXI4MasterPortParameters);
  *   - `Up` is the slave port: slaves with disjoint address ranges, the bus `beatBytes`, and the id capacity the
  *     downstream can absorb (AXI4SlavePortParameters);
  *   - `Edge` settles to bundle parameters: `addrBits = log2Up(maxAddress + 1)`, `dataBits = beatBytes * 8`,
  *     `idBits = log2Up(endId)` (AXI4BundleParameters).
  *
  * Everything is plain serializable data; upickle codecs come from `derives ReadWriter`.
  */

def log2Up(x: Long): Int =
  require(x >= 1, s"log2Up($x)")
  if x <= 1 then 0 else 64 - java.lang.Long.numberOfLeadingZeros(x - 1)

/** Half-open byte range `[base, base + size)`; the demo's simplification of rocket-chip's base/mask AddressSet. */
final case class AddressRange(base: Long, size: Long) derives ReadWriter:
  require(base >= 0 && size > 0, s"illegal AddressRange($base, $size)")
  def end:                          Long    = base + size // exclusive
  def overlaps(that: AddressRange): Boolean = base < that.end && that.base < end
  def show:                         String  = f"0x$base%x+0x$size%x"

/** Half-open id range `[start, end)`, as rocket-chip's IdRange. */
final case class IdRange(start: Int, end: Int) derives ReadWriter:
  require(0 <= start && start < end, s"illegal IdRange($start, $end)")
  def size:                    Int     = end - start
  def overlaps(that: IdRange): Boolean = start < that.end && that.start < end
  def shift(offset:  Int):     IdRange = IdRange(start + offset, end + offset)

/** Supported transfer sizes in bytes, inclusive on both ends (rocket-chip TransferSizes). */
final case class TransferSizes(min: Int, max: Int) derives ReadWriter:
  require(0 < min && min <= max, s"illegal TransferSizes($min, $max)")

final case class AxiMasterParams(
  name:      String,
  id:        IdRange,
  maxFlight: Int)
    derives ReadWriter

/** The downward-flowing port parameters (AXI4MasterPortParameters). */
final case class AxiMasterPort(masters: Vector[AxiMasterParams]) derives ReadWriter:
  require(masters.nonEmpty, "AxiMasterPort needs at least one master")
  def endId:     Int                                        = masters.map(_.id.end).max
  def idBits:    Int                                        = math.max(1, log2Up(endId.toLong))
  def idOverlap: Option[(AxiMasterParams, AxiMasterParams)] =
    masters.combinations(2).collectFirst { case Seq(x, y) if x.id.overlaps(y.id) => (x, y) }

final case class AxiSlaveParams(
  name:          String,
  address:       Vector[AddressRange],
  regionType:    String,
  executable:    Boolean,
  supportsRead:  TransferSizes,
  supportsWrite: TransferSizes)
    derives ReadWriter:
  def maxTransfer: Int  = math.max(supportsRead.max, supportsWrite.max)
  def maxAddress:  Long = address.map(_.end).max

/** The upward-flowing port parameters (AXI4SlavePortParameters plus the id capacity of the doc's motivation example:
  * how many id bits the downstream implementation can absorb).
  */
final case class AxiSlavePort(
  slaves:         Vector[AxiSlaveParams],
  beatBytes:      Int,
  idCapacityBits: Int,
  minLatency:     Int)
    derives ReadWriter:
  require(slaves.nonEmpty, "AxiSlavePort needs at least one slave")
  def maxTransfer:    Int                                                  = slaves.map(_.maxTransfer).max
  def maxAddress:     Long                                                 = slaves.map(_.maxAddress).max
  def addressOverlap: Option[(String, AddressRange, String, AddressRange)] =
    val all = slaves.flatMap(s => s.address.map(a => (s.name, a)))
    all.combinations(2).collectFirst { case Seq((sn, sa), (tn, ta)) if sa.overlaps(ta) => (sn, sa, tn, ta) }

/** The settled edge (AXI4EdgeParameters + AXI4BundleParameters). */
final case class AxiEdgeParams(
  master:   AxiMasterPort,
  slave:    AxiSlavePort,
  addrBits: Int,
  dataBits: Int,
  idBits:   Int)
    derives ReadWriter

object Axi4 extends Protocol:
  type Down = AxiMasterPort
  type Up   = AxiSlavePort
  type Edge = AxiEdgeParams

  val id = ProtocolId(ProtocolKind.Design, "amba.axi4", "1.0")

  def negotiate(m: AxiMasterPort, s: AxiSlavePort): Either[TermViolation, AxiEdgeParams] =
    def fail(msg: String) = Left(TermViolation(msg))
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
    if !Integer.toBinaryString(s.beatBytes).matches("10*") then return fail(s"beatBytes ${s.beatBytes} is not pow2")
    if s.maxTransfer < s.beatBytes then
      return fail(s"maxTransfer ${s.maxTransfer} smaller than bus width ${s.beatBytes}: link pointlessly wide")
    if s.maxTransfer > s.beatBytes * 256 then
      return fail(s"maxTransfer ${s.maxTransfer} unencodable in AxLEN on a ${s.beatBytes}B bus")
    Right(
      AxiEdgeParams(
        master = m,
        slave = s,
        addrBits = math.max(1, log2Up(s.maxAddress)),
        dataBits = s.beatBytes * 8,
        idBits = idBits
      )
    )

  /** The five AXI4 channels. AW / W / AR flow master → slave; B / R are flipped. Each channel is valid / ready(flip) /
    * bits, with rocket-chip's global field widths (len 8, size 3, burst 2, resp 2).
    */
  def interfaceOf(e: AxiEdgeParams): ProtocolBundle =
    import ProtocolInterface.*
    def channel(payload: (String, ProtocolInterface)*): Bundle =
      Bundle(
        Vector(
          Field("valid", false, Bool),
          Field("ready", true, Bool),
          Field("bits", false, Bundle(payload.toVector.map((n, t) => Field(n, false, t))))
        )
      )
    val addr = ("addr", UInt(e.addrBits))
    val id0  = ("id", UInt(e.idBits))
    ProtocolBundle(
      Field("aw", false, channel(id0, addr, "len" -> UInt(8), "size" -> UInt(3), "burst" -> UInt(2))),
      Field("w", false, channel("data" -> UInt(e.dataBits), "strb" -> UInt(e.dataBits / 8), "last" -> Bool)),
      Field("b", true, channel(id0, "resp" -> UInt(2))),
      Field("ar", false, channel(id0, addr, "len" -> UInt(8), "size" -> UInt(3), "burst" -> UInt(2))),
      Field("r", true, channel(id0, "data" -> UInt(e.dataBits), "resp" -> UInt(2), "last" -> Bool))
    )

  def render(e: AxiEdgeParams): RenderedValue =
    RenderedValue(
      s"AXI4 ${e.dataBits}b",
      Map(
        "addrBits" -> e.addrBits.toString,
        "dataBits" -> e.dataBits.toString,
        "idBits"   -> e.idBits.toString,
        "masters"  -> e.master.masters.map(_.name).mkString("+"),
        "slaves"   -> e.slave.slaves.map(_.name).mkString("+")
      )
    )

  private def schema(name: String) = ujson.Str(s"amba.axi4/$name@1.0")
  val downCodec: Codec[AxiMasterPort] = Codec.fromReadWriter[AxiMasterPort](schema("MasterPort"))
  val upCodec:   Codec[AxiSlavePort]  = Codec.fromReadWriter[AxiSlavePort](schema("SlavePort"))
  val edgeCodec: Codec[AxiEdgeParams] = Codec.fromReadWriter[AxiEdgeParams](schema("EdgeParams"))

/** Port-parameter transforms shared by the demo's interconnect generators, mirroring `AXI4Xbar.masterFn` / `slaveFn`:
  * the doc's static id remap (@sec-three-params) prefixes each input's local ids with the input index.
  */
object Axi4Xbar:

  /** Bits every input's local id space is padded to before prefixing. */
  def localBits(ports: Vector[AxiMasterPort]): Int = ports.map(_.idBits).max

  /** Prefix bits consumed by an n-input xbar. */
  def prefixBits(n: Int): Int = log2Up(n.toLong)

  /** Downward: union of all input masters, input `i` shifted into `[i << localBits, ...)` (mapInputIds). */
  def mapInputs(ports: Vector[AxiMasterPort]): AxiMasterPort =
    val local = localBits(ports)
    AxiMasterPort(
      ports.zipWithIndex.flatMap { (p, i) =>
        p.masters.map(m => m.copy(id = m.id.shift(i << local)))
      }
    )

  /** Upward: concatenated slaves of every reachable output; equal beatBytes required; the id capacity passed upstream
    * shrinks by the prefix bits this xbar consumes.
    */
  def aggregate(ups: Vector[AxiSlavePort], nInputs: Int): Either[PropagationViolation, AxiSlavePort] =
    val widths = ups.map(_.beatBytes).distinct
    if widths.sizeIs > 1 then
      return Left(PropagationViolation(s"xbar data widths don't match: beatBytes ${widths.mkString(" vs ")}"))
    val slaves = ups.flatMap(_.slaves)
    val port   = AxiSlavePort(
      slaves = slaves,
      beatBytes = widths.head,
      idCapacityBits = ups.map(_.idCapacityBits).min - prefixBits(nInputs),
      minLatency = ups.map(_.minLatency).min
    )
    port.addressOverlap match
      case Some((xn, xa, yn, ya)) =>
        Left(PropagationViolation(s"slave addresses overlap: '$xn' ${xa.show} vs '$yn' ${ya.show}"))
      case None                   => Right(port)
