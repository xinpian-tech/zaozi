package me.jiuyang.syntheke

sealed trait Constraint:
  private[syntheke] def reads: Vector[DomainReadable[?]]
  private[syntheke] def loc: (sourcecode.File, sourcecode.Line)

object Constraint:
  private[syntheke] final class Required[D <: Domain](val source: DomainReadable[D])(
    val value: source.domain.Requirement,
    val loc: (sourcecode.File, sourcecode.Line))
      extends Constraint:
    val reads: Vector[DomainReadable[?]] = Vector(source)

  private[syntheke] final class Check(
    val reads: Vector[DomainReadable[?]],
    val run: DomainView => Either[Violation, Unit],
    val loc: (sourcecode.File, sourcecode.Line))
      extends Constraint
