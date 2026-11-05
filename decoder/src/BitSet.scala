// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.decoder

given upickle.default.ReadWriter[BitSet] = upickle.default
  .readwriter[String]
  .bimap[BitSet](
    bs => bs.toString,
    {
      case "EMPTY"                           => BitSet.empty
      case s"[0x${start},0x${end}):${width}" =>
        BitSet.range(BigInt(start, 16), BigInt(end, 16) - BigInt(start, 16), width.toInt)
      case str                               => BitSet.bitset(str)
    }
  )

given upickle.default.ReadWriter[BitPat] = upickle.default
  .readwriter[String]
  .bimap[BitPat](
    bs => bs.toString,
    str => BitSet.bitpat(str)
  )

object BitSet:
  given Ordering[BitPat] = (x: BitPat, y: BitPat) => (x.width, x.value, x.mask).compare(y.width, y.value, y.mask)
  def empty:                                                EmptyBitSet = new EmptyBitSet {}
  def bitpat(str: String):                                  BitPat      =
    str.foreach: i =>
      require(Seq('1', '0', '?').contains(i), s"BitPat parse failed: got $i not in '1,0,?'")
    bitpat(
      BigInt(str.replace('?', '0'), 2),
      BigInt(str.replace('0', '1').replace("?", "0"), 2),
      str.length
    )
  def bitset(str: String):                                  BitSet      =
    apply(str.split('|').map(BitSet.bitpat).sorted.toSet)
  def bitpat(_value: BigInt, _mask: BigInt, _width: Int):   BitPat      =
    require(_width > 0, "Use empty to create EmptyBitPat")
    new BitPat:
      val value:          BigInt = _value
      val mask:           BigInt = _mask
      override val width: Int    = _width
  def apply(bs: Set[BitSet]):                               BitSet      = new BitSet:
    val terms: Set[BitPat] = bs.filterNot(_.isEmpty).flatMap(_.terms)
  def range(
    _start:  BigInt,
    _length: BigInt
  ): RangeBitSet = range(_start, _length, (_start + _length - 1).bitLength)
  def range(
    _start:  BigInt,
    _length: BigInt,
    _width:  Int
  ): RangeBitSet = new RangeBitSet:
    override def start:  BigInt = _start
    override def length: BigInt = _length
    override def width:  Int    = _width
  def y(width:   Int = 1):                                  BitPat      = BitSet.bitpat("1" * width)
  def n(width:   Int = 1):                                  BitPat      = BitSet.bitpat("0" * width)
  def all(width: Int = 1):                                  BitPat      = BitSet.bitpat("?" * width)

/** Sum of Products */
trait BitSet:
  outer =>
  def terms: Set[BitPat]
  def width: Int =
    require(terms.map(_.width).size <= 1, s"All BitPats must be the same size! Got $this")
    // set width = 0 if terms is empty.
    terms.headOption.map(_.width).getOrElse(0)

  override def toString: String =
    terms.toSeq.sorted.mkString("|")

  def isEmpty: Boolean = terms.forall(_.isEmpty)

  /** Check whether this `BitSet` overlap with that `BitSet`, i.e. !(intersect.isEmpty)
    *
    * @param that
    *   `BitSet` to be checked.
    * @return
    *   true if this and that `BitSet` have overlap.
    */
  def overlap(that: BitSet): Boolean =
    !terms
      .flatMap(a => that.terms.map(b => (a, b)))
      .forall:
        case (a, b) => !a.overlap(b)

  /** Check whether this `BitSet` covers that (i.e. forall b matches that, b also matches this)
    *
    * @param that
    *   `BitSet` to b covered
    * @return
    *   true if this `BitSet` can cover that `BitSet`
    */
  def cover(that: BitSet): Boolean =
    that.subtract(this).isEmpty

  /** Intersect `this` and `that` `BitSet`.
    *
    * @param that
    *   `BitSet` to be intersected.
    * @return
    *   a `BitSet` containing all elements of `this` that also belong to `that`.
    */
  def intersect(that: BitSet): BitSet =
    terms
      .flatMap(a => that.terms.map(b => a.intersect(b)))
      .filterNot(_.isEmpty)
      .fold(BitSet.empty)(_.union(_))

  /** Subtract that from this `BitSet`.
    *
    * @param that
    *   subtrahend `BitSet`.
    * @return
    *   a `BitSet` contining elements of `this` which are not the elements of `that`.
    */
  def subtract(that: BitSet): BitSet =
    terms
      .map: a =>
        that.terms.map(b => a.subtract(b)).fold(a)(_.intersect(_))
      .filterNot(_.isEmpty)
      .fold(BitSet.empty)(_.union(_))

  /** Union this and that `BitSet`
    *
    * @param that
    *   `BitSet` to union.
    * @return
    *   a `BitSet` containing all elements of `this` and `that`.
    */
  def union(that: BitSet): BitSet = BitSet((outer.terms ++ that.terms).toSet)

  /** Test whether two `BitSet` matches the same set of value
    *
    * @note
    *   This method can be very expensive compared to ordinary == operator between two Objects
    *
    * @return
    *   true if two `BitSet` is same.
    */
  override def equals(obj: Any): Boolean =
    obj match
      case that: BitSet => this.width == that.width && this.cover(that) && that.cover(this)
      case _ => false

  override def hashCode: Int =
    if terms.isEmpty then width.hashCode
    else
      val all = (BigInt(1) << width) - 1
      (width, terms.map(t => t.value & t.mask).min, terms.map(t => t.value & t.mask | ~t.mask & all).max).hashCode

  /** Calculate the inverse of this pattern set.
    *
    * @return
    *   A BitSet matching all value (of the given with) iff it doesn't match this pattern.
    */
  def inverse: BitSet =
    val total = BitSet.bitpat("?" * this.width)
    total.subtract(this)

/** Products */
trait BitPat extends BitSet:
  val value: BigInt
  val mask:  BigInt
  override def toString: String =
    Seq
      .tabulate(width): i =>
        (value.testBit(width - i - 1), mask.testBit(width - i - 1)) match
          case (true, true)  => "1"
          case (false, true) => "0"
          case (_, false)    => "?"
      .mkString

  final val terms:            Set[BitPat] = Set(this)
  final override def isEmpty: Boolean     = false

  def ##(that: BitPat): BitPat =
    BitSet.bitpat((value << that.width) + that.value, (mask << that.width) + that.mask, this.width + that.width)

  def hasDontCares: Boolean = width > 0 && mask != ((BigInt(1) << width) - 1)

  def allZeros: Boolean = value == 0 && !hasDontCares

  def allOnes: Boolean = !hasDontCares && value == mask

  def allDontCares: Boolean = mask == 0

  override def equals(obj: Any):      Boolean =
    obj match
      case that: BitPat => this.value == that.value && this.mask == that.mask && this.width == that.width
      case that: BitSet => super.equals(obj)
      case _ => false
  override def overlap(that: BitSet): Boolean = that match
    case that: BitPat => ((mask & that.mask) & (value ^ that.value)) == 0
    case _ => super.overlap(that)
  override def cover(that: BitSet):   Boolean = that match
    case that: BitPat => (mask & (~that.mask | (value ^ that.value))) == 0
    case _ => super.cover(that)
  def intersect(that: BitPat):        BitSet  =
    if (!overlap(that))
      BitSet.empty
    else
      BitSet.bitpat(this.value | that.value, this.mask | that.mask, this.width.max(that.width))
  def subtract(that: BitPat):         BitSet  =
    require(width == that.width)
    def enumerateBits(mask: BigInt): Seq[BigInt] =
      if (mask == 0) Nil
      else
        // bits comes after the first '1' in a number are inverted in its two's complement.
        // therefore bit is always the first '1' in x (counting from least significant bit).
        val bit = mask & (-mask)
        bit +: enumerateBits(mask & ~bit)

    val intersection = intersect(that)
    val omask        = this.mask
    if (intersection.isEmpty)
      this
    else
      BitSet(
        intersection.terms.flatMap: remove =>
          enumerateBits(~omask & remove.mask).map: bit =>
            // Only care about higher than current bit in remove
            val nmask  = (omask | ~(bit - 1)) & remove.mask
            val nvalue = (remove.value ^ bit) & nmask
            val nwidth = remove.width
            BitSet.bitpat(nvalue, nmask, nwidth)
      )

trait EmptyBitSet extends BitSet:
  override def toString:      String      = "EMPTY"
  final val terms:            Set[BitPat] = Set()
  final override def isEmpty: Boolean     = true

trait RangeBitSet extends BitSet:
  out =>
  def start:                  BigInt
  def length:                 BigInt
  override def toString:      String      = s"[0x${start.toString(16)},0x${(start + length).toString(16)}):$width"
  // validation
  require(length > 0, "Cannot construct a empty BitSetRange")
  private val maxKnownLength: Int         = (start + length - 1).bitLength
  require(
    width >= maxKnownLength,
    s"Cannot construct a BitSetRange with width($width) smaller than its range end(b${(start + length - 1).toString(2)})"
  )
  final val terms:            Set[BitPat] =
    val collected = scala.collection.mutable.Set[BitPat]()
    var ptr       = start
    var left      = length
    while (left > 0)
      var curPow = left.bitLength - 1
      if (ptr != 0)
        val maxPow = ptr.lowestSetBit
        if (maxPow < curPow)
          curPow = maxPow
      val inc    = BigInt(1) << curPow
      require((ptr & inc - 1) == 0, "BitPatRange: Internal sanity check")
      collected.add(BitSet.bitpat(ptr, (BigInt(1) << out.width) - inc, out.width))
      ptr += inc
      left -= inc

    collected.toSet
