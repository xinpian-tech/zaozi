package me.jiuyang.syntheke

/** Where a declaration is in the user's source. */
type SourceLoc = (sourcecode.File, sourcecode.Line)

extension (loc: SourceLoc) private[syntheke] def show: String = s"${loc._1.value}:${loc._2.value}"

/** A user design error: what is wrong, then where. */
final class NegotiationException(message: String) extends RuntimeException(message)

private[syntheke] def fail(message: String, locs: SourceLoc*): Nothing =
  throw NegotiationException(if locs.isEmpty then message else s"$message, at ${locs.map(_.show).mkString(", ")}")

private[syntheke] def check(condition: Boolean, locs: SourceLoc*)(message: => String): Unit =
  if !condition then fail(message, locs*)

/** Fails with every message of a stage, if there is any. */
private[syntheke] def report(stage: String, failures: Seq[String]): Unit =
  if failures.nonEmpty then throw NegotiationException(s"$stage failed:" + failures.map("\n  - " + _).mkString)

private[syntheke] object DeclaredName:
  private val shape = "[A-Za-z_][A-Za-z0-9_]*".r
  def legal(name: String): Boolean = shape.matches(name)
  def check(name: String, role: String, loc: SourceLoc): Unit =
    me.jiuyang.syntheke.check(legal(name), loc)(s"$role '$name' is not a legal name ([A-Za-z_][A-Za-z0-9_]*)")

final case class ModuleId(path: Vector[String]) derives upickle.default.ReadWriter:
  def /(instanceName: String): ModuleId         = ModuleId(path :+ instanceName)
  def parent:                  Option[ModuleId] = if path.isEmpty then None else Some(ModuleId(path.init))
  def isAncestorOf(other: ModuleId): Boolean    = other.path.startsWith(path)
  def show:                    String           = if path.isEmpty then "<root>" else path.mkString(".")

object ModuleId:
  val root: ModuleId = ModuleId(Vector.empty)

  def lca(a: ModuleId, b: ModuleId): ModuleId =
    ModuleId(a.path.zip(b.path).takeWhile(_ == _).map(_._1))

final case class ModuleNodeId(module: ModuleId, name: String) derives upickle.default.ReadWriter:
  def show: String = s"${module.show}#$name"

final case class DomainId(module: ModuleId, name: String):
  def show: String = s"${module.show}@$name"

/** A bind; its outward source node is the source of no other bind. */
final case class BindId(source: ModuleNodeId, target: ModuleNodeId):
  def show: String = s"bind ${source.show} -> ${target.show}"
