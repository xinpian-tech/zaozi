package me.jiuyang.syntheke

object Export:

  private def moduleId(id: ModuleId): ujson.Value = ujson.Arr.from(id.path.map(ujson.Str(_)))

  private def nodeId(id: ModuleNodeId): ujson.Value =
    ujson.Obj("module" -> moduleId(id.module), "name" -> ujson.Str(id.name))

  private def domainId(id: DomainId): ujson.Value =
    ujson.Obj("module" -> moduleId(id.module), "name" -> ujson.Str(id.name))

  private def bindId(id: BindId): ujson.Value = ujson.Obj("source" -> nodeId(id.source), "target" -> nodeId(id.target))

  private def loc(l: SourceLoc): ujson.Value =
    ujson.Obj("file" -> ujson.Str(l._1.value.replace('\\', '/')), "line" -> ujson.Num(l._2.value))

  private def interface(tpe: ProtocolInterface): ujson.Value = upickle.default.writeJs(tpe)

  private def membership(domain: Domain[?]): ujson.Value =
    ujson.Obj("kind" -> ujson.Str(domain.kind.name), "domain" -> domainId(domain.id))

  private def declared(domain: Domain[?]): ujson.Value =
    ujson.Obj(
      "id"         -> domainId(domain.id),
      "kind"       -> ujson.Str(domain.kind.name),
      "references" -> ujson.Arr.from(domain.references.map(d => domainId(d.id))),
      "loc"        -> loc(domain.loc)
    )

  def topology(spec: DesignSpec): ujson.Value =
    ujson.Obj(
      "modules" -> ujson.Arr.from(spec.moduleOrder.map { id =>
        spec.modules(id) match
          case w: WrapperModuleSpec   =>
            ujson.Obj(
              "id"         -> moduleId(id),
              "kind"       -> ujson.Str("wrapper"),
              "moduleName" -> ujson.Str(w.moduleName),
              "children"   -> ujson.Arr.from(w.children.map(ujson.Str(_))),
              "loc"        -> loc(w.loc)
            )
          case b: BoundaryModuleSpec  =>
            ujson.Obj("id" -> moduleId(b.id), "kind" -> ujson.Str("boundary"), "target" -> moduleId(b.target))
          case g: GeneratorModuleSpec =>
            ujson.Obj(
              "id"        -> moduleId(id),
              "kind"      -> ujson.Str("generator"),
              "generator" -> ujson.Str(g.definition.name),
              "nodes"     -> ujson.Arr.from(g.nodes.map { n =>
                ujson.Obj(
                  "id"          -> nodeId(ModuleNodeId(id, n.name)),
                  "direction"   -> ujson.Str(n.direction.toString.toLowerCase),
                  "memberships" -> ujson.Arr.from(n.domains.map(membership)),
                  "reads"       -> ujson.Arr.from(n.computation.reads.map(nodeId)),
                  "loc"         -> loc(n.loc)
                )
              }),
              "loc"       -> loc(g.loc)
            )
      }),
      "binds"   -> ujson.Arr.from(spec.binds.map { b =>
        ujson.Obj("id" -> bindId(b.id), "declaredIn" -> moduleId(b.declaredIn), "loc" -> loc(b.loc))
      }),
      "domains" -> ujson.Arr.from(spec.domains.map(declared)),
      "root"    -> moduleId(spec.root)
    )

  /** Declared domains with their properties, every node's membership, and what every bind crosses. */
  def domains(resolved: ResolvedDesign): ujson.Value =
    val spec = resolved.spec
    def properties(domain: Domain[?]):     ujson.Value =
      val kind         = domain.kind
      ujson.Obj(
        "id"         -> domainId(domain.id),
        "kind"       -> ujson.Str(kind.name),
        "properties" -> kind.describe(domain.asInstanceOf),
        "references" -> ujson.Arr.from(domain.references.map(d => domainId(d.id)))
      )
    val crossings = DomainChecks.crossings(spec).collect { case DomainChecks.Crossing(bind, kind, Some(a), Some(b)) =>
      ujson.Obj(
        "bind"   -> bindId(bind.id),
        "kind"   -> ujson.Str(kind.name),
        "source" -> domainId(a.id),
        "target" -> domainId(b.id)
      )
    }
    ujson.Obj(
      "domains"     -> ujson.Arr.from(spec.domains.map(properties)),
      "memberships" -> ujson.Arr.from(
        spec.nodeModules
          .flatMap(m => m.nodes.flatMap(n => n.domains.map(d => ModuleNodeId(m.id, n.name) -> d)))
          .sortBy((node, domain) => (node.show, domain.kind.name))
          .map((node, domain) =>
            ujson.Obj("node" -> nodeId(node), "kind" -> ujson.Str(domain.kind.name), "domain" -> domainId(domain.id))
          )
      ),
      "crossings"   -> ujson.Arr.from(crossings)
    )

  def edges(resolved: ResolvedDesign): ujson.Value =
    ujson.Obj(
      "designEdges" -> ujson.Arr.from(resolved.edges.map { e =>
        val p        = e.protocol
        ujson.Obj(
          "id"        -> bindId(e.bind),
          "down"      -> serialize(p.downWriter, e.down),
          "up"        -> serialize(p.upWriter, e.up),
          "edge"      -> serialize(p.edgeWriter, e.edge),
          "interface" -> interface(e.interface)
        )
      })
    )

  def plan(resolved: ResolvedDesign): ujson.Value =
    def endpoint(e: LocalEndpoint):    ujson.Value = e match
      case LocalEndpoint.ThisPort(name)        => ujson.Obj("port" -> ujson.Str(name.encoded))
      case LocalEndpoint.ChildPort(inst, port) =>
        ujson.Obj("instance" -> ujson.Str(inst), "port" -> ujson.Str(port.encoded))
    ujson.Obj(
      "probeNodes"    -> ujson.Arr.from(resolved.probes.nodes.map(ResolvedProbe.encode)),
      "probes"        -> ujson.Arr.from(resolved.probes.ports.map { probe =>
        ujson.Obj("source" -> nodeId(probe.id), "interface" -> interface(probe.reference))
      }),
      "probeBindings" -> ujson.Obj.from(
        resolved.observations.toVector
          .sortBy(_._1.show)
          .map((module, bindings) => module.show -> upickle.default.writeJs(bindings))
      ),
      "ports"         -> ujson.Arr.from(resolved.portPlans.map { p =>
        ujson.Obj(
          "module"    -> moduleId(p.module),
          "direction" -> ujson.Str(p.direction.toString.toLowerCase),
          "name"      -> ujson.Str(p.name.encoded),
          "interface" -> interface(p.interface),
          "loc"       -> loc(p.loc)
        )
      }),
      "wires"         -> ujson.Arr.from(resolved.wirePlans.map { w =>
        ujson.Obj(
          "module" -> moduleId(w.module),
          "from"   -> endpoint(w.from),
          "to"     -> endpoint(w.to),
          "kind"   -> ujson.Str(w.kind.toString),
          "loc"    -> loc(w.loc)
        )
      }),
      "layers"        -> ujson.Obj.from(
        resolved.layerDecls.toVector
          .filterNot(_._2.isEmpty)
          .sortBy(_._1.path.mkString("/"))
          .map { (m, tree) =>
            m.show -> ujson.Arr.from(tree.paths().map(p => ujson.Arr.from(p.map(ujson.Str(_)))))
          }
      )
    )

  def params(resolved: ResolvedDesign): ujson.Value =
    ujson.Arr.from(resolved.generatorModules.map { g =>
      ujson.Obj(
        "module"    -> moduleId(g.module),
        "generator" -> ujson.Str(resolved.spec.generatorModule(g.module).get.definition.name),
        "fullParam" -> serialize(resolved.spec.generatorModule(g.module).get.definition.fullParamWriter, g.fullParam)
      )
    })
