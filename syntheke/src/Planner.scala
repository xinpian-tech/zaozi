// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.syntheke

import scala.collection.mutable

/** Cross-hierarchy port and wire planning plus FIRRTL layer declarations (doc @ch-hierarchy).
  *
  * Every settled connection has two generator-module endpoints. The wiring scope `W` is their lowest common ancestor
  * (the shared parent when both endpoints live in one module); dangle ports are generated on every wrapper boundary
  * strictly between an endpoint's parent and `W`, and bundle-level wires are planned per wrapper.
  */
private[syntheke] object Planner:

  def plan(
    spec:    DesignSpec,
    settled: Negotiator.Settled
  ): (Vector[PortPlan], Vector[WirePlan], Map[ModuleId, LayerTree]) =
    val ports  = mutable.ArrayBuffer.empty[PortPlan]
    val wires  = mutable.ArrayBuffer.empty[WirePlan]
    val layers = mutable.Map.empty[ModuleId, LayerTree].withDefaultValue(LayerTree.empty)

    /** Wrapper modules strictly between `endpoint`'s parent and `w`, closest to the endpoint first. */
    def branchModules(endpoint: ModuleId, w: ModuleId): Vector[ModuleId] =
      Iterator
        .iterate(endpoint.parent)(_.flatMap(_.parent))
        .takeWhile(m => m.exists(_ != w))
        .flatten
        .toVector

    /** Dangle-port name on wrapper `m` for the connection ending at `endpoint`'s port with `base` segments. */
    def dangleName(m: ModuleId, endpoint: ModuleId, base: PortName): PortName =
      val rel = endpoint.path.drop(m.path.length)
      PortName(rel.flatMap(inst => Vector("inst", inst))) ++ base

    /** Plan one branch: dangle ports below `w` plus the wires inside those wrappers. Returns the branch's end reference
      * inside `w`.
      */
    def planBranch(
      endpoint:  ModuleId, // generator module owning the endpoint port
      portName:  PortName, // the generator's own port name
      base:      PortName, // dangle base segments for this endpoint
      w:         ModuleId,
      direction: PortDirection,
      interface: ProtocolBundle,
      origin:    PlanOrigin,
      loc:       SourceLocation
    ): LocalEndpoint =
      val ms = branchModules(endpoint, w)
      ms.zipWithIndex.foreach { (m, i) =>
        val name       = dangleName(m, endpoint, base)
        ports += PortPlan(m, direction, name, interface, origin, loc)
        val childRef   =
          if i == 0 then LocalEndpoint.ChildPort(endpoint.path.last, portName)
          else LocalEndpoint.ChildPort(ms(i - 1).path.last, dangleName(ms(i - 1), endpoint, base))
        val thisRef    = LocalEndpoint.ThisPort(name)
        val (from, to) = direction match
          case PortDirection.Output => (childRef, thisRef)
          case PortDirection.Input  => (thisRef, childRef)
        wires += WirePlan(m, from, to, origin, loc)
      }
      if ms.isEmpty then LocalEndpoint.ChildPort(endpoint.path.last, portName)
      else LocalEndpoint.ChildPort(ms.last.path.last, dangleName(ms.last, endpoint, base))

    // ============ design edges ============
    settled.edges.foreach { e =>
      val decl   = spec.binds(e.bind.order)
      val a      = e.bind.source.module
      val b      = e.bind.target.module
      val w      = if a == b then a.parent.get else ModuleId.lca(a, b)
      val origin = PlanOrigin.Design(e.bind)
      val srcEnd = planBranch(
        endpoint = a,
        portName = PortName(e.bind.source.name),
        base = PortName("node", e.bind.source.name, "out"),
        w = w,
        direction = PortDirection.Output,
        interface = e.interface,
        origin = origin,
        loc = decl.loc
      )
      val tgtEnd = planBranch(
        endpoint = b,
        portName = PortName(e.bind.target.name),
        base = PortName("node", e.bind.target.name, "in"),
        w = w,
        direction = PortDirection.Input,
        interface = e.interface,
        origin = origin,
        loc = decl.loc
      )
      wires += WirePlan(w, srcEnd, tgtEnd, origin, decl.loc)
    }

    // ============ verification binds ============
    settled.dvGroups.foreach { group =>
      val sinkModule = group.sink.module
      val w          = sinkModule.parent.get
      group.binds.zipWithIndex.foreach { (bindId, i) =>
        val decl      = spec.dvBinds.find(_.bindId == bindId).get
        val source    = bindId.source
        val origin    = PlanOrigin.Verification(bindId)
        val interface = group.interfaces.sources(i)
        val srcEnd    = planBranch(
          endpoint = source.module,
          portName = PortName(source.name),
          base = PortName("dv-source", source.name, "out"),
          w = w,
          direction = PortDirection.Output,
          interface = interface,
          origin = origin,
          loc = decl.loc
        )
        val sinkRef   =
          LocalEndpoint.ChildPort(sinkModule.path.last, PortName(group.sink.name), group.interfaces.sinkPaths(i))
        wires += WirePlan(w, srcEnd, sinkRef, origin, decl.loc)

        // Layer declarations on every wrapper the probe crosses, including the wiring scope.
        val layered = branchModules(source.module, w) :+ w
        layered.foreach(m => layers(m) = layers(m).add(group.layers(i)))
      }
    }

    (ports.toVector, wires.toVector, layers.toMap)
