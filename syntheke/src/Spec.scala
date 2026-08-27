// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.syntheke

/** The frozen design specification produced by the Build phase (doc @sec-build).
  *
  * Parameter values and port parameter functions are stored untyped ([[Any]]) at this layer; the typed façade lives in
  * [[DesignBuilder]] and the protocol objects guard every crossing.
  */

enum NodeDirection derives CanEqual:
  case Inward, Outward

/** Violation value returned by a port parameter function (doc @sec-propagation). */
final case class PropagationViolation(message: String)

/** Violation value returned by `computeProtocolParam` capability checking (doc @sec-settle-pp). */
final case class CapabilityViolation(message: String)

/** A cross-protocol reference: a node names another node of the same module (its clock or power node); settlement
  * yields that node's edge parameter (doc @sec-settle-pp).
  */
final case class CrossProtocolRefSpec(
  refName:          String,
  target:           ModuleNodeId,
  expectedProtocol: ProtocolId,
  loc:              SourceLocation)

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
  fn:        Map[ModuleNodeId, Any] => Either[PropagationViolation, Any],
  refs:      Vector[CrossProtocolRefSpec],
  order:     Int,
  loc:       SourceLocation)

/** A module-internal parameter dependency from an inward node to an outward node of the same module. */
final case class ParamDependencySpec(
  from:  String, // inward node name
  to:    String, // outward node name
  order: Int,
  loc:   SourceLocation)

/** A probe source declaration: provides the verification `Down` and a FIRRTL layer path. */
final case class DVSourceSpec(
  name:     String,
  protocol: DVProtocol,
  down:     Any,
  layer:    LayerPath,
  order:    Int,
  loc:      SourceLocation)

/** A probe sink declaration on a verification generator module. */
final case class DVSinkSpec(
  name:     String,
  protocol: DVProtocol,
  order:    Int,
  loc:      SourceLocation)

/** Registry entry: generator identity plus its FullParam serialization (doc @sec-generator-records).
  *
  * The zaozi generator implementation itself lives beyond the serialization boundary and is bound in the elaboration
  * module; within Build / Negotiate the entry is the identity and serialization carrier.
  */
final class GeneratorEntry[FP](
  val id:                GeneratorId
)(
  using val fullParamRW: upickle.default.ReadWriter[FP])

sealed trait ModuleSpec:
  def id:  ModuleId
  def loc: SourceLocation

/** A structural module: composes children and declares binds; its circuit is emitted by the framework. */
final case class WrapperModuleSpec(
  id:       ModuleId,
  children: Vector[String], // instance names in declaration order
  loc:      SourceLocation)
    extends ModuleSpec

/** A generator module: leaf of the hierarchy tree, bound to exactly one generator. */
final case class GeneratorModuleSpec(
  id:                   ModuleId,
  entry:                GeneratorEntry[?],
  nodes:                Vector[NodeSpec],
  dependencies:         Vector[ParamDependencySpec],
  dvSources:            Vector[DVSourceSpec],
  dvSinks:              Vector[DVSinkSpec],
  computeProtocolParam: EdgeView => Either[CapabilityViolation, Any],
  combine:              Any => Any, // protocol param => FullParam; user param captured in the closure
  loc:                  SourceLocation)
    extends ModuleSpec:
  def node(name: String): Option[NodeSpec] = nodes.find(_.name == name)

/** A design bind declaration `target <-- source`, recorded in the wrapper module that declares it. */
final case class BindDecl(
  order:      Int,
  source:     ModuleNodeId,
  target:     ModuleNodeId,
  declaredIn: ModuleId,
  loc: SourceLocation):
  def bindId: BindId = BindId(order, source, target)

/** A verification bind declaration `sink <-- source`. */
final case class DVBindDecl(
  order:      Int,
  source:     DVSourceId,
  sink:       DVSinkId,
  declaredIn: ModuleId,
  loc: SourceLocation):
  def bindId: DVBindId = DVBindId(sink, source)

/** The immutable output of the Build phase. */
final case class DesignSpec(
  modules:     Map[ModuleId, ModuleSpec],
  moduleOrder: Vector[ModuleId],             // hierarchy-tree preorder
  binds:       Vector[BindDecl],
  dvBinds:     Vector[DVBindDecl],
  protocols:   Vector[(ProtocolId, AnyRef)], // registration order; AnyRef is Protocol or DVProtocol
  generators: Vector[GeneratorEntry[?]]):

  def wrapper(id: ModuleId):         Option[WrapperModuleSpec]   = modules.get(id).collect { case w: WrapperModuleSpec => w }
  def generatorModule(id: ModuleId): Option[GeneratorModuleSpec] =
    modules.get(id).collect { case g: GeneratorModuleSpec => g }
  def generatorModules:              Vector[GeneratorModuleSpec] =
    moduleOrder.flatMap(generatorModule)
  def nodeSpec(id: ModuleNodeId):    Option[NodeSpec]            =
    generatorModule(id.module).flatMap(_.node(id.name))
