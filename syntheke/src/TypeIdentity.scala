package me.jiuyang.syntheke

import scala.annotation.publicInBinary
import scala.quoted.*

final class TypeIdentity[T] private (private val key: TypeIdentity.Key):
  override def equals(other: Any): Boolean = other match
    case identity: TypeIdentity[?] => key == identity.key
    case _                        => false

  override def hashCode(): Int = key.hashCode()

object TypeIdentity:
  private enum Builtin:
    case Any, AnyVal, Object, Matchable, Singleton, Nothing, Null
    case Unit, Boolean, Byte, Short, Char, Int, Long, Float, Double

  private enum Key:
    case Atom(builtin: Builtin)
    case Nominal(declaration: Class[?], arguments: List[Key])
    case ArrayOf(element: Key)

  inline given derived[T]: TypeIdentity[T] = ${ derive[T] }

  @publicInBinary private[TypeIdentity] def atom[T](ordinal: Int): TypeIdentity[T] =
    new TypeIdentity(Key.Atom(Builtin.fromOrdinal(ordinal)))

  @publicInBinary private[TypeIdentity] def nominal[T](
    declaration: Class[?], arguments: List[TypeIdentity[?]]
  ): TypeIdentity[T] =
    new TypeIdentity(Key.Nominal(declaration, arguments.map(_.key)))

  @publicInBinary private[TypeIdentity] def array[T](element: TypeIdentity[?]): TypeIdentity[T] =
    new TypeIdentity(Key.ArrayOf(element.key))

  private def derive[T: Type](using Quotes): Expr[TypeIdentity[T]] =
    import quotes.reflect.*

    def unsupported(tpe: TypeRepr, reason: String): Nothing =
      report.errorAndAbort(s"Cannot derive TypeIdentity[${Type.show[T]}]: ${tpe.show}: $reason")

    def isPackage(symbol: Symbol): Boolean =
      symbol.isPackageDef || symbol.flags.is(Flags.Package)

    def staticOwner(symbol: Symbol): Boolean =
      isPackage(symbol) ||
        (symbol.flags.is(Flags.Module) && staticOwner(symbol.owner))

    def staticDeclaration(symbol: Symbol): Boolean =
      symbol.flags.is(Flags.JavaStatic) || staticOwner(symbol.owner)

    def staticPrefix(prefix: TypeRepr): Boolean = prefix match
      case _: NoPrefix => true
      case ref: TermRef =>
        (isPackage(ref.termSymbol) ||
          (ref.termSymbol.flags.is(Flags.Module) && staticDeclaration(ref.termSymbol))) && staticPrefix(ref.qualifier)
      case self: ThisType =>
        isPackage(self.tref.typeSymbol) ||
          (self.tref.typeSymbol.flags.is(Flags.Module) && staticDeclaration(self.tref.typeSymbol))
      case ref: TypeRef =>
        (isPackage(ref.typeSymbol) ||
          ((ref.typeSymbol.flags.is(Flags.Module) || ref.typeSymbol.flags.is(Flags.JavaDefined)) &&
            staticDeclaration(ref.typeSymbol))) && staticPrefix(ref.qualifier)
      case _ => false

    val atoms = Map(
      defn.AnyClass -> Builtin.Any,
      defn.AnyValClass -> Builtin.AnyVal,
      defn.ObjectClass -> Builtin.Object,
      defn.AnyRefClass -> Builtin.Object,
      defn.MatchableClass -> Builtin.Matchable,
      Symbol.requiredClass("scala.Singleton") -> Builtin.Singleton,
      defn.NothingClass -> Builtin.Nothing,
      defn.NullClass -> Builtin.Null,
      defn.UnitClass -> Builtin.Unit,
      defn.BooleanClass -> Builtin.Boolean,
      defn.ByteClass -> Builtin.Byte,
      defn.ShortClass -> Builtin.Short,
      defn.CharClass -> Builtin.Char,
      defn.IntClass -> Builtin.Int,
      defn.LongClass -> Builtin.Long,
      defn.FloatClass -> Builtin.Float,
      defn.DoubleClass -> Builtin.Double
    )
    val nonNominalClasses = Set(
      Symbol.requiredClass("scala.Tuple"),
      Symbol.requiredClass("scala.NonEmptyTuple"),
      Symbol.requiredClass("scala.*:"),
      Symbol.requiredClass("scala.caps.Pure")
    )

    def build(input: TypeRepr, root: Boolean): Expr[TypeIdentity[?]] =
      def opaque(ref: TypeRef): Boolean =
        ref.isOpaqueAlias || (!ref.typeSymbol.isClassDef && ref.typeSymbol.flags.is(Flags.Opaque))

      val expanded = input match
        case ref: TypeRef if ref.typeSymbol.isAliasType && !opaque(ref) =>
          if !staticPrefix(ref.qualifier) then unsupported(input, "path-dependent type aliases are not supported")
          Some(ref.translucentSuperType)
        case AppliedType(ref: TypeRef, arguments) if ref.typeSymbol.isAliasType && !opaque(ref) =>
          if !staticPrefix(ref.qualifier) then unsupported(input, "path-dependent type aliases are not supported")
          arguments.foreach(build(_, false))
          Some(ref.translucentSuperType.appliedTo(arguments))
        case _ => None

      input match
        case ref: TypeRef if opaque(ref) =>
          unsupported(input, "opaque contracts are not supported; use an ordinary nominal contract type")
        case AppliedType(ref: TypeRef, _) if opaque(ref) =>
          unsupported(input, "opaque contracts are not supported; use an ordinary nominal contract type")
        case _: AnnotatedType => unsupported(input, "annotated types are not supported")
        case _ => ()

      expanded match
        case Some(tpe) =>
          if tpe == input then unsupported(input, "unresolved type alias")
          if !(tpe =:= input) then unsupported(input, "type alias expansion is not an exact equivalence")
          build(tpe, root)
        case None => nominalType(input, root)

    def nominalType(tpe: TypeRepr, root: Boolean): Expr[TypeIdentity[?]] =
      if tpe.isFunctionType || tpe.isContextFunctionType || tpe.isErasedFunctionType || tpe.isDependentFunctionType then
        unsupported(tpe, "function types are not supported")

      def parameter(): Expr[TypeIdentity[?]] =
        if root then unsupported(tpe, "unresolved type parameter; pass a contextual TypeIdentity for it")
        tpe.asType match
          case '[a] =>
            Expr.summon[TypeIdentity[a]].getOrElse(
              unsupported(tpe, "missing contextual TypeIdentity for this type argument")
            )

      def declared(ref: TypeRef, arguments: List[TypeRepr]): Expr[TypeIdentity[?]] =
        val symbol = ref.typeSymbol
        if !symbol.isClassDef || symbol.flags.is(Flags.Module) || symbol.isAnonymousClass ||
          !staticDeclaration(symbol) || !staticPrefix(ref.qualifier)
        then unsupported(tpe, "expected a class or trait declared in a static package/object scope")
        if nonNominalClasses(symbol) || symbol.flags.is(Flags.Erased) then
          unsupported(tpe, "this compiler-special type has no supported nominal JVM declaration identity")
        val children = arguments.map(build(_, false))
        tpe.asType match
          case '[a] =>
            atoms.get(symbol) match
              case Some(builtin) => '{ atom[a](${ Expr(builtin.ordinal) }) }
              case None if symbol == defn.ArrayClass =>
                children match
                  case element :: Nil => '{ array[a]($element) }
                  case _ => unsupported(tpe, "Array requires one concrete element type")
              case None =>
                val declaration = Literal(ClassOfConstant(ref)).asExprOf[Class[?]]
                '{ nominal[a]($declaration, ${ Expr.ofList(children) }) }

      tpe match
        case ref: TypeRef if ref.typeSymbol.isTypeParam => parameter()
        case _: ParamRef => parameter()
        case ref: TypeRef => declared(ref, Nil)
        case AppliedType(ref: TypeRef, arguments) => declared(ref, arguments)
        case _ =>
          unsupported(tpe, "expected a static nominal type with recursively supported arguments; " +
            "singletons, wildcards, refinements, unions/intersections and unresolved type expressions are not supported")

    build(TypeRepr.of[T], true).asExprOf[TypeIdentity[T]]
