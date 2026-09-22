package me.jiuyang.syntheke.zaozi

import me.jiuyang.syntheke.*
import me.jiuyang.zaozi.{DVInterface, Generator, HWInterface, LayerInterface, Parameter}
import me.jiuyang.zaozi.syntheke.PublicProbes
import me.jiuyang.zaozi.valuetpe.*

private[zaozi] object ProbeBindingSupport:
  def selector[P, T <: Data & CanProbe, FP <: Parameter, L <: LayerInterface[FP], I <: HWInterface[FP], D <: DVInterface[FP, L]](
    association: ProbeBindingFor[P, T],
    generator: Generator[FP, L, I, D]
  )(
    select: (FP, D) => Option[(P, BundleField[RProbe[T]])]
  ): ProbeSelector[FP, P] =
    new ProbeSelector[FP, P]:
      def resolve(fp: FP, declaration: ProbeDeclaration): Either[Violation, Option[ProbeResolution[P]]] =
        declaration match
          case source: ZaoziProbeDeclaration if source.generator eq generator =>
            select(fp, source.declaration.asInstanceOf[D]) match
              case None => Right(None)
              case Some((parameters, field)) =>
                if !PublicProbes.owns(source.declaration, field) then
                  Left(Violation(s"'${field.name}' is not a field of this generator's public Probe declaration"))
                else
                  Right(Some(ProbeResolution(parameters, field.name,
                    new ZaoziProbeImplementation[T](association.dataType, field, source))))
          case _ => Left(Violation("Probe selector belongs to a different generator implementation"))

  def observe[P, T <: Data & CanProbe](association: ProbeBindingFor[P, T], resolved: ResolvedProbe[P]): Probe[T] =
    resolved.implementation match
      case implementation: ZaoziProbeImplementation[?] if implementation.dataTypeIdentity == association.dataType =>
        val typed = implementation.asInstanceOf[ZaoziProbeImplementation[T]]
        new Probe[T](typed.dataType, resolved.port, typed.source.generatorName, typed.source.parameter)
      case _ =>
        throw new IllegalArgumentException(s"${resolved.id.show}: Probe binding does not match this node's public data type")

private[zaozi] final class ZaoziProbeImplementation[T <: Data & CanProbe](
  val dataTypeIdentity: TypeIdentity[T],
  val field: BundleField[RProbe[T]],
  val source: ZaoziProbeDeclaration) extends ProbeImplementation:
  def dataType: T = PublicProbes.dataType(field.dataType)

  override def validate(declaration: ProbeDeclaration, portName: String): Either[Violation, Unit] =
    if (declaration eq source) && portName == field.name then Right(())
    else Left(Violation("typed Probe evidence belongs to a different public declaration or port"))
