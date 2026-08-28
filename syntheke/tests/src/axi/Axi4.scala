// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.syntheke.tests.axi

import me.jiuyang.syntheke.*
import upickle.default.ReadWriter

/** An AXI4 negotiation protocol modeled on rocket-chip's `amba.axi4.Parameters`:
  *
  *   - `Down` is the master port: masters with disjoint id ranges (AXI4MasterPortParameters);
  *   - `Up` is the slave port: slaves with disjoint address sets, the bus `beatBytes`, and the id capacity the
  *     downstream can absorb (AXI4SlavePortParameters);
  *   - `Edge` settles to bundle parameters: `addrBits = log2Up(maxAddress + 1)`, `dataBits = beatBytes * 8`,
  *     `idBits = log2Up(endId)` (AXI4BundleParameters).
  *
  * [[AddressSet]] carries diplomacy's base/mask algebra. Deliberate omissions from rocket-chip: the BundleField user
  * bits (echo/request/response fields), device resources / DTS, and `nodePath` — syntheke's stable identifiers and
  * source locations serve diagnostics instead. Everything is plain serializable data; upickle codecs come from
  * `derives ReadWriter`.
  */

def log2Up(x: Long): Int =
  require(x >= 1, s"log2Up($x)")
  if x <= 1 then 0 else 64 - java.lang.Long.numberOfLeadingZeros(x - 1)

/** The addresses with `(addr ^ base) & ~mask == 0`: `base` fixes the bits outside `mask`, the `mask` bits vary freely —
  * diplomacy's AddressSet, mask not necessarily contiguous.
  */
final case class AddressSet(base: Long, mask: Long) derives ReadWriter:
  require(base >= 0 && mask >= 0, s"illegal AddressSet($base, $mask)")
  require((base & mask) == 0L, f"AddressSet base 0x$base%x must be aligned to its mask 0x$mask%x")

  def contains(x:    Long):          Boolean = ((x ^ base) & ~mask) == 0L
  def contains(that: AddressSet):    Boolean =
    ((that.base ^ base) & ~mask) == 0L && (that.mask & ~mask) == 0L
  def overlaps(that: AddressSet):    Boolean = ((that.base ^ base) & ~(mask | that.mask)) == 0L

  def intersect(that: AddressSet): Option[AddressSet] =
    if !overlaps(that) then None else Some(AddressSet(base | that.base, mask & that.mask))

  /** This set minus `that`, as disjoint sets: one per bit free here but fixed in the intersection. */
  def subtract(that: AddressSet): Vector[AddressSet] =
    intersect(that) match
      case None          => Vector(this)
      case Some(removed) =>
        AddressSet.bitsOf(mask & ~removed.mask).map { bit =>
          AddressSet(base | (bit & ~removed.base), mask & ~bit)
        }

  /** Frees the `imask` bits: the union over all their values. */
  def widen(imask: Long): AddressSet = AddressSet(base & ~imask, mask | imask)

  def alignment:  Long    = (mask + 1) & ~mask
  def contiguous: Boolean = alignment == mask + 1
  def max:        Long    = base | mask // inclusive
  def show:       String  = f"0x$base%x/0x$mask%x"

object AddressSet:
  private def bitsOf(x: Long): Vector[Long] =
    Vector.iterate(java.lang.Long.lowestOneBit(x), java.lang.Long.bitCount(x))(bit =>
      java.lang.Long.lowestOneBit(x & ~(bit | (bit - 1)))
    )

  /** `[base, base + size)` as disjoint aligned sets, largest chunks first-fit (diplomacy's misaligned). */
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

  /** The same address space with fewer sets: drops contained sets, merges same-mask sets differing in one base bit. */
  def unify(seq: Seq[AddressSet]): Vector[AddressSet] =
    val sets   = seq.toVector.distinct
    val kept   = sets.filterNot(s => sets.exists(o => (o ne s) && o.contains(s)))
    val merged = kept.indices.view
      .flatMap(i => (i + 1 until kept.size).view.map(j => (i, j)))
      .collectFirst {
        case (i, j)
            if kept(i).mask == kept(j).mask &&
              java.lang.Long.bitCount(kept(i).base ^ kept(j).base) == 1 =>
          val bit = kept(i).base ^ kept(j).base
          kept.patch(j, Nil, 1).patch(i, Vector(AddressSet(kept(i).base & kept(j).base, kept(i).mask | bit)), 1)
      }
    merged match
      case Some(next) => unify(next)
      case None       => kept.sortBy(s => (s.base, s.mask))

/** Half-open id range `[start, end)`, as rocket-chip's IdRange. */
final case class IdRange(start: Int, end: Int) derives ReadWriter:
  require(0 <= start && start < end, s"illegal IdRange($start, $end)")
  def overlaps(that: IdRange): Boolean = start < that.end && that.start < end
  def shift(offset:  Int):     IdRange = IdRange(start + offset, end + offset)

/** Supported transfer sizes in bytes, inclusive powers of two, `none` when zero (rocket-chip TransferSizes). */
final case class TransferSizes(min: Int, max: Int) derives ReadWriter:
  require(0 <= min && min <= max, s"illegal TransferSizes($min, $max)")
  require((min == 0) == (max == 0), s"illegal TransferSizes($min, $max)")
  require(min == 0 || Integer.bitCount(min) == 1, s"TransferSizes min $min is not a power of two")
  require(max == 0 || Integer.bitCount(max) == 1, s"TransferSizes max $max is not a power of two")
  def none:                           Boolean       = max == 0
  def contains(x: Int):               Boolean       = Integer.bitCount(x) == 1 && min <= x && x <= max
  def intersect(that: TransferSizes): TransferSizes =
    if max < that.min || that.max < min then TransferSizes.none
    else TransferSizes(math.max(min, that.min), math.min(max, that.max))

object TransferSizes:
  val none: TransferSizes = TransferSizes(0, 0)

final case class AxiMasterParams(
  name:      String,
  id:        IdRange,
  aligned:   Boolean = false,
  maxFlight: Option[Int] = None) // None: unlimited transactions in flight per id
    derives ReadWriter

/** The downward-flowing port parameters (AXI4MasterPortParameters). */
final case class AxiMasterPort(masters: Vector[AxiMasterParams]) derives ReadWriter:
  require(masters.nonEmpty, "AxiMasterPort needs at least one master")
  def endId:     Int                                        = masters.map(_.id.end).max
  def idBits:    Int                                        = math.max(1, log2Up(endId.toLong))
  def idOverlap: Option[(AxiMasterParams, AxiMasterParams)] =
    masters.combinations(2).collectFirst { case Seq(x, y) if x.id.overlaps(y.id) => (x, y) }

/** Memory region semantics, rocket-chip's RegionType ladder. */
enum RegionType derives CanEqual, ReadWriter:
  case Cached, Tracked, Uncached, Idempotent, Volatile, PutEffects, GetEffects

final case class AxiSlaveParams(
  name:          String,             // diagnostics; rocket-chip uses nodePath / device resources instead
  address:       Vector[AddressSet],
  regionType:    RegionType,
  executable:    Boolean,
  supportsWrite: TransferSizes,
  supportsRead:  TransferSizes,
  interleavedId: Option[Int] = None) // Some(id): read responses of this slave never interleave
    derives ReadWriter:
  require(address.nonEmpty, s"slave '$name' needs at least one address set")
  def maxTransfer:  Int  = math.max(supportsWrite.max, supportsRead.max)
  def maxAddress:   Long = address.map(_.max).max // inclusive
  def minAlignment: Long = address.map(_.alignment).min

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
  require(Integer.bitCount(beatBytes) == 1, s"beatBytes $beatBytes is not a power of two")
  def maxTransfer:    Int                                              = slaves.map(_.maxTransfer).max
  def maxAddress:     Long                                             = slaves.map(_.maxAddress).max // inclusive
  def addressOverlap: Option[(String, AddressSet, String, AddressSet)] =
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

  def negotiate(m: AxiMasterPort, s: AxiSlavePort): Either[Violation, AxiEdgeParams] =
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
    if s.maxTransfer < s.beatBytes then
      return fail(s"maxTransfer ${s.maxTransfer} smaller than bus width ${s.beatBytes}: link pointlessly wide")
    if s.maxTransfer > s.beatBytes * 256 then
      return fail(s"maxTransfer ${s.maxTransfer} unencodable in AxLEN on a ${s.beatBytes}B bus")
    Right(
      AxiEdgeParams(
        master = m,
        slave = s,
        addrBits = math.max(1, log2Up(s.maxAddress + 1)),
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
          Field("valid", Bool),
          Field("ready", Flipped(Bool)),
          Field("bits", Bundle(payload.toVector.map((n, t) => Field(n, t))))
        )
      )
    val addr = ("addr", UInt(e.addrBits))
    val id0  = ("id", UInt(e.idBits))
    ProtocolBundle(
      Field("aw", channel(id0, addr, "len" -> UInt(8), "size" -> UInt(3), "burst" -> UInt(2))),
      Field("w", channel("data" -> UInt(e.dataBits), "strb" -> UInt(e.dataBits / 8), "last" -> Bool)),
      Field("b", Flipped(channel(id0, "resp" -> UInt(2)))),
      Field("ar", channel(id0, addr, "len" -> UInt(8), "size" -> UInt(3), "burst" -> UInt(2))),
      Field("r", Flipped(channel(id0, "data" -> UInt(e.dataBits), "resp" -> UInt(2), "last" -> Bool)))
    )

  val downRW: upickle.default.ReadWriter[AxiMasterPort] = summon
  val upRW:   upickle.default.ReadWriter[AxiSlavePort]  = summon
  val edgeRW: upickle.default.ReadWriter[AxiEdgeParams] = summon

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
