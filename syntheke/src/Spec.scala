// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.syntheke

/** The frozen design specification produced by the Build phase (doc @sec-build).
  *
  * Parameter values and port parameter functions are stored untyped ([[Any]]) at this layer; the typed façade lives in
  * `Dsl.scala` and the protocol objects guard every crossing.
  */

enum NodeDirection derives CanEqual:
  case Inward, Outward

/** A cross-protocol reference: a node names another node of the same module (its clock or power node); settlement
  * yields that node's edge parameter (doc @sec-settle-pp).
  */
final case class CrossProtocolRefSpec(
  refName: String,
  target:  ModuleNodeId,
  loc:     (sourcecode.File, sourcecode.Line))

/** One named inward or outward node of a generator module (doc @sec-node-conn-proto).
  *
  * For an outward node `fn` is the dFn: it reads the `Down` of the declared inward dependencies (in declaration order)
  * and returns this node's `Down`. For an inward node `fn` is the uFn over the dependent outward nodes' `Up`. Boundary
  * nodes receive an empty input vector and produce the initial value from the user parameter captured in the closure.
  */
final case class NodeSpec(
  name:      String,
  direction: NodeDirection,
  protocol:  Protocol,
  fn:        Map[ModuleNodeId, Any] => Either[Violation, Any],
  refs:      Vector[CrossProtocolRefSpec],
  order:     Int,
  loc:       (sourcecode.File, sourcecode.Line))

/** A module-internal parameter dependency from an inward node to an outward node of the same module. */
final case class ParamDependencySpec(
  from:  String, // inward node name
  to:    String, // outward node name
  order: Int,
  loc:   (sourcecode.File, sourcecode.Line))

/** A probe source declaration: the verification `Down`, the FIRRTL layer path, and the probe interface derived from
  * them at the declaration (checked there — see `GeneratorScope.dvSource`).
  */
final case class DVSourceSpec(
  name:      String,
  protocol:  DVProtocol,
  down:      Any,
  layer:     LayerPath,
  interface: ProtocolBundle,
  order:     Int,
  loc:       (sourcecode.File, sourcecode.Line))

sealed trait ModuleSpec:
  def id:  ModuleId
  def loc: (sourcecode.File, sourcecode.Line)

/** A structural module: composes children and declares binds; its circuit is emitted by the framework. */
final case class WrapperModuleSpec(
  id:       ModuleId,
  children: Vector[String], // instance names in declaration order
  loc:      (sourcecode.File, sourcecode.Line))
    extends ModuleSpec

/** A generator module: leaf of the hierarchy tree, bound to exactly one generator. */
final case class GeneratorModuleSpec(
  id:               ModuleId,
  entry:            GeneratorEntry[?],
  nodes:            Vector[NodeSpec],
  dependencies:     Vector[ParamDependencySpec],
  dvSources:        Vector[DVSourceSpec],
  computeFullParam: EdgeView => Either[Violation, Any], // user params captured in the closure
  loc:              (sourcecode.File, sourcecode.Line))
    extends ModuleSpec:
  def node(name: String): Option[NodeSpec] = nodes.find(_.name == name)

/** A design bind declaration `target <-- source`, recorded in the wrapper module that declares it. */
final case class BindDecl(
  order:      Int,
  source:     ModuleNodeId,
  target:     ModuleNodeId,
  declaredIn: ModuleId,
  loc: (sourcecode.File, sourcecode.Line)):
  def bindId: BindId = BindId(order, source, target)

/** The immutable output of the Build phase. */
final case class DesignSpec(
  modules:     Map[ModuleId, ModuleSpec],
  moduleOrder: Vector[ModuleId], // hierarchy-tree preorder
  binds:       Vector[BindDecl],
  ioNodes:     Vector[NodeSpec], // top-level IO nodes, declaration order (doc @sec-io-nodes)
  generators: Vector[GeneratorEntry[?]]): // registration order; the name-conflict check runs over this

  def wrapper(id: ModuleId):         Option[WrapperModuleSpec]   = modules.get(id).collect { case w: WrapperModuleSpec => w }
  def generatorModule(id: ModuleId): Option[GeneratorModuleSpec] =
    modules.get(id).collect { case g: GeneratorModuleSpec => g }
  def generatorModules:              Vector[GeneratorModuleSpec] =
    moduleOrder.flatMap(generatorModule)
  def nodeSpec(id: ModuleNodeId):    Option[NodeSpec]            =
    if id.module == ModuleId.root then ioNodes.find(_.name == id.name)
    else generatorModule(id.module).flatMap(_.node(id.name))
