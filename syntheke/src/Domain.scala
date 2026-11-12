package me.jiuyang.syntheke

import upickle.default.Writer

/** One kind of domain: clock, reset, power, security, ... The kind defines what a domain is declared with. The
  * framework knows no kind by name.
  */
trait DomainKind:
  /** What a domain of this kind is declared with: its properties and the domains it derives from or depends on. */
  type Definition

  def name: String

  /** `definition` with every domain it names, of this kind or another, replaced by `f` of it: the clocks a clock
    * derives from, the clock a reset is released on, the power domain a reset follows.
    */
  def map(definition: Definition)(f: DomainMap): Definition

  /** The properties that make two domains interchangeable; a frozen boundary compares these. */
  def describe(domain: Domain[this.type]): ujson.Value

/** What takes the place of each domain a definition names. */
type DomainMap = [K <: DomainKind] => Domain[K] => Domain[K]

/** A domain: its kind and its definition, which names only domains of the same design. */
final class Domain[K <: DomainKind] private[syntheke] (
  val kind:    K,
  val id:      DomainId,
  definition0: Any,
  private[syntheke] val loc: SourceLoc):

  def definition: kind.Definition = definition0.asInstanceOf[kind.Definition]

  /** The domains its definition names. */
  private[syntheke] lazy val references: Vector[Domain[?]] =
    val named = Vector.newBuilder[Domain[?]]
    kind.map(definition)([K2 <: DomainKind] => (domain: Domain[K2]) => { named += domain; domain })
    named.result().distinct

  override def toString: String = id.show

object Domain:
  given [K <: DomainKind]: Writer[Domain[K]] = upickle.default.writer[String].comap(_.id.show)

/** A protocol's answer to what a bind of it may cross in one domain kind. */
final class Accept private (val kind: DomainKind, private[syntheke] val test: (Domain[?], Domain[?]) => Boolean)

object Accept:
  /** A bind of the protocol may cross from a domain of `kind` to another when `test` holds of the two. */
  def apply[K <: DomainKind](kind: K)(test: (Domain[K], Domain[K]) => Boolean): Accept =
    new Accept(kind, (a, b) => test(a.asInstanceOf[Domain[K]], b.asInstanceOf[Domain[K]]))
