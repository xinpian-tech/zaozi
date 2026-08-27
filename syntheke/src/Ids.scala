// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.syntheke

/** Stable identifiers (doc @sec-identity).
  *
  * Entity identity derives from named structure: instance-name paths and declaration names. Source locations are
  * diagnostics only and never part of identity.
  */

/** Declaration-name shape, enforced at every declaration site (instance names, endpoint names, layer segments).
  *
  * Names become FIRRTL symbols verbatim — module, instance, port and layer names — so they are restricted to
  * `[A-Za-z_][A-Za-z0-9_]*`. Excluding `-` and `$` also makes the reversible dangle encoding's use of them a guaranteed
  * separator between framework-chosen segments (`dv-source`) and user names, not a coincidence.
  */
private[syntheke] object DeclaredName:
  private val shape = "[A-Za-z_][A-Za-z0-9_]*".r
  def require(name: String, role: String): Unit =
    Predef.require(shape.matches(name), s"$role '$name' is not a legal name ([A-Za-z_][A-Za-z0-9_]*)")

/** Instance-name path from the design root. The root module has an empty path. */
final case class ModuleId(path: Vector[String]):
  def /(instanceName: String): ModuleId         = ModuleId(path :+ instanceName)
  def parent:                  Option[ModuleId] = if path.isEmpty then None else Some(ModuleId(path.init))

  /** Strict ancestor test: this is an ancestor of `other` and not equal to it. */
  def isStrictAncestorOf(other: ModuleId): Boolean =
    path.length < other.path.length && other.path.startsWith(path)
  def isAncestorOf(other: ModuleId):       Boolean = other.path.startsWith(path)
  def show:                                String  = if path.isEmpty then "<root>" else path.mkString(".")

object ModuleId:
  val root: ModuleId = ModuleId(Vector.empty)

  /** Lowest common ancestor in the hierarchy tree. */
  def lca(a: ModuleId, b: ModuleId): ModuleId =
    ModuleId(a.path.zip(b.path).takeWhile(_ == _).map(_._1))

/** A named node on a generator module. */
final case class ModuleNodeId(module: ModuleId, name: String):
  def show: String = s"${module.show}#$name"

/** A design bind: declaration order plus the two endpoints. */
final case class BindId(order: Int, source: ModuleNodeId, target: ModuleNodeId):
  def show: String = s"bind[$order] ${source.show} -> ${target.show}"

/** A named probe source on a generator module (doc @sec-dv-declarations). */
final case class DVSourceId(module: ModuleId, name: String):
  def show: String = s"${module.show}#$name"

/** Declaration sites are captured with sourcecode's own `File` / `Line` givens and stored as the pair; this formats one
  * for a diagnostic message.
  */
extension (loc: (sourcecode.File, sourcecode.Line))
  private[syntheke] def show: String = s"${loc._1.value}:${loc._2.value}"
