package me.jiuyang.syntheke.zaozi

import me.jiuyang.syntheke.*
import me.jiuyang.zaozi.{DVInterface, Generator, HWInterface, LayerInterface, Parameter}
import me.jiuyang.zaozi.syntheke.PublicProbes
import me.jiuyang.zaozi.valuetpe.*

final class ProbeBindingFor[P, T <: Data & CanProbe] private ()(using dataType: TypeIdentity[T]):
  def from[FP <: Parameter, L <: LayerInterface[FP], I <: HWInterface[FP], D <: DVInterface[FP, L]](
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
                    new ZaoziProbeImplementation[T](dataType, field, source))))
          case _ => Left(Violation("Probe selector belongs to a different generator implementation"))

  private[zaozi] def implementation(resolved: ResolvedProbe[P]): ZaoziProbeImplementation[T] =
    resolved.implementation match
      case implementation: ZaoziProbeImplementation[?] if implementation.dataTypeIdentity == dataType =>
        implementation.asInstanceOf[ZaoziProbeImplementation[T]]
      case _ =>
        throw new IllegalArgumentException(s"${resolved.id.show}: Probe binding does not match this node's public data type")

object ProbeBindingFor:
  def apply[P, T <: Data & CanProbe: TypeIdentity](): ProbeBindingFor[P, T] = new ProbeBindingFor[P, T]()

private[zaozi] final class ZaoziProbeImplementation[T <: Data & CanProbe](
  val dataTypeIdentity: TypeIdentity[T],
  val field: BundleField[RProbe[T]],
  val source: ZaoziProbeDeclaration) extends ProbeImplementation:
  def dataType: T = PublicProbes.dataType(field.dataType)

  override def validate(declaration: ProbeDeclaration, portName: String): Either[Violation, Unit] =
    if (declaration eq source) && portName == field.name then Right(())
    else Left(Violation("typed Probe evidence belongs to a different public declaration or port"))

extension [P](resolved: ResolvedProbe[P])
  def observe[T <: Data & CanProbe](using association: ProbeBindingFor[P, T]): Probe[T] =
    val implementation = association.implementation(resolved)
    new Probe[T](implementation.dataType, resolved.port,
      implementation.source.generatorName, implementation.source.parameter)
