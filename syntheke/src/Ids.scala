package me.jiuyang.syntheke


private[syntheke] object DeclaredName:
  private val shape = "[A-Za-z_][A-Za-z0-9_]*".r
  def require(name: String, role: String): Unit =
    Predef.require(shape.matches(name), s"$role '$name' is not a legal name ([A-Za-z_][A-Za-z0-9_]*)")

final case class ModuleId(path: Vector[String]) derives upickle.default.ReadWriter:
  def /(instanceName: String): ModuleId         = ModuleId(path :+ instanceName)
  def parent:                  Option[ModuleId] = if path.isEmpty then None else Some(ModuleId(path.init))

  def isAncestorOf(other: ModuleId):       Boolean = other.path.startsWith(path)
  def show:                                String  = if path.isEmpty then "<root>" else path.mkString(".")

object ModuleId:
  val root: ModuleId = ModuleId(Vector.empty)

  def lca(a: ModuleId, b: ModuleId): ModuleId =
    ModuleId(a.path.zip(b.path).takeWhile(_ == _).map(_._1))

final case class ModuleNodeId(module: ModuleId, name: String) derives upickle.default.ReadWriter:
  def show: String = s"${module.show}#$name"

final case class DomainDeclId(module: ModuleId, name: String):
  def show: String = s"${module.show}@$name"

final case class NodeDomainKey(node: ModuleNodeId, domain: DomainKey):
  def show: String = s"${node.show}@${domain.show}"

final case class BindId(order: Int, source: ModuleNodeId, target: ModuleNodeId):
  def show: String = s"bind[$order] ${source.show} -> ${target.show}"

extension (loc: (sourcecode.File, sourcecode.Line))
  private[syntheke] def show: String = s"${loc._1.value}:${loc._2.value}"
