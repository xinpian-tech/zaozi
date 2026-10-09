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

  private def source(value: DomainSource[?]): ujson.Value = value match
    case domain: Domain[?] => ujson.Obj("domain" -> domainId(domain.id))
    case ref: DomainRef[?] => ujson.Obj("node" -> nodeId(ref.node))

  private def membership(m: Membership): ujson.Value =
    ujson.Obj("kind" -> ujson.Str(m.kind.name), "source" -> m.source.fold(ujson.Str("received"))(source), "loc" -> loc(m.loc))

  private def declared(domain: Domain[?]): ujson.Value =
    val kind   = domain.kind
    val origin = domain.origin match
      case Domain.Origin.Root(_)             => ujson.Str("root")
      case Domain.Origin.Derived(sources, _) => ujson.Obj("derived" -> ujson.Arr.from(sources.map(source)))
      case Domain.Origin.Imported(of, _)     => ujson.Obj("imported" -> domainId(of.id))
    ujson.Obj("id" -> domainId(domain.id), "kind" -> ujson.Str(kind.name), "origin" -> origin, "loc" -> loc(domain.loc))

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
              "id"           -> moduleId(id),
              "kind"         -> ujson.Str("generator"),
              "generator"    -> ujson.Str(g.definition.name),
              "nodes"        -> ujson.Arr.from(g.nodes.map { n =>
                ujson.Obj(
                  "id"          -> nodeId(ModuleNodeId(id, n.name)),
                  "direction"   -> ujson.Str(n.direction.toString.toLowerCase),
                  "memberships" -> ujson.Arr.from(n.memberships.map(membership)),
                  "reads"       -> ujson.Arr.from(n.computation.reads.map(nodeId)),
                  "loc"         -> loc(n.loc)
                )
              }),
              "loc"          -> loc(g.loc)
            )
      }),
      "binds"   -> ujson.Arr.from(spec.binds.map { b =>
        ujson.Obj("id" -> bindId(b.id), "declaredIn" -> moduleId(b.declaredIn), "loc" -> loc(b.loc))
      }),
      "domains" -> ujson.Arr.from(spec.domains.map(declared)),
      "root"    -> moduleId(spec.root)
    )

  /** Settled domains with their properties, every node's membership, and what every bind crosses. */
  def domains(resolved: ResolvedDesign): ujson.Value =
    val graph = resolved.domains
    def settled(domain: Settled[?]): ujson.Value =
      val kind   = domain.kind
      val origin =
        domain.imported.map(of => ujson.Obj("imported" -> domainId(of.id)))
          .orElse(domain.root.map(_ => ujson.Str("root")))
          .getOrElse(ujson.Obj("derived" -> ujson.Arr.from(domain.sources.map(d => domainId(d.id)))))
      ujson.Obj(
        "id"         -> domainId(domain.id),
        "kind"       -> ujson.Str(kind.name),
        "properties" -> kind.describe(domain.asInstanceOf),
        "origin"     -> origin
      )
    val crossings = for
      bind <- resolved.spec.binds
      kind <- graph.membership.keys.collect { case (n, k) if n == bind.source => k }.toVector.distinct.sortBy(_.name)
      a    <- graph.member(bind.source, kind)
      b    <- graph.member(bind.target, kind)
    yield ujson.Obj(
      "bind"     -> bindId(bind.id),
      "kind"     -> ujson.Str(kind.name),
      "source"   -> domainId(a.id),
      "target"   -> domainId(b.id),
      "relation" -> ujson.Str(kind.relate(a.asInstanceOf, b.asInstanceOf).toString)
    )
    ujson.Obj(
      "domains"     -> ujson.Arr.from(graph.domains.map(settled)),
      "memberships" -> ujson.Arr.from(graph.membership.toVector.sortBy((key, _) => (key._1.show, key._2.name)).map {
        case ((node, kind), domain) =>
          ujson.Obj("node" -> nodeId(node), "kind" -> ujson.Str(kind.name), "domain" -> domainId(domain.id))
      }),
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
    def endpoint(e: LocalEndpoint):        ujson.Value = e match
      case LocalEndpoint.ThisPort(name)        => ujson.Obj("port" -> ujson.Str(name.encoded))
      case LocalEndpoint.ChildPort(inst, port) =>
        ujson.Obj("instance" -> ujson.Str(inst), "port" -> ujson.Str(port.encoded))
    ujson.Obj(
      "probeNodes" -> ujson.Arr.from(resolved.probes.nodes.map(ResolvedProbe.encode)),
      "probes"        -> ujson.Arr.from(resolved.probes.ports.map { probe =>
        ujson.Obj("source" -> nodeId(probe.id), "interface" -> interface(probe.reference))
      }),
      "probeBindings" -> ujson.Obj.from(resolved.observations.toVector.sortBy(_._1.show).map((module, bindings) => module.show -> upickle.default.writeJs(bindings))),
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
        "generator" -> ujson.Str(g.definition.name),
        "fullParam" -> serialize(g.definition.fullParamWriter, g.fullParam)
      )
    })
