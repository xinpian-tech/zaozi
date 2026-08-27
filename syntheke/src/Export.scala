// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.syntheke

/** On-demand JSON exports of a [[ResolvedDesign]] (doc @ch-tooling).
  *
  * Four whole-design files: `topology.json`, `edges.json`, `plan.json`, `params.json`. Orderings follow the
  * normalization rules of the design document (@sec-determinism): modules in hierarchy preorder, design edges in bind
  * declaration order, per-sink verification records in bind declaration order, layer trees name-sorted.
  */
object Export:

  private def moduleId(id: ModuleId): ujson.Value = ujson.Arr.from(id.path.map(ujson.Str(_)))

  private def nodeId(id: ModuleNodeId): ujson.Value =
    ujson.Obj("module" -> moduleId(id.module), "name" -> ujson.Str(id.name))

  private def bindId(id: BindId): ujson.Value =
    ujson.Obj("order" -> ujson.Num(id.order), "source" -> nodeId(id.source), "target" -> nodeId(id.target))

  private def dvSourceId(id: DVSourceId): ujson.Value =
    ujson.Obj("module" -> moduleId(id.module), "name" -> ujson.Str(id.name))
  private def dvSinkId(id: DVSinkId):     ujson.Value =
    ujson.Obj("module" -> moduleId(id.module), "name" -> ujson.Str(id.name))
  private def dvBindId(id: DVBindId):     ujson.Value =
    ujson.Obj("sink" -> dvSinkId(id.sink), "source" -> dvSourceId(id.source))

  private def loc(l: SourceLocation): ujson.Value =
    ujson.Obj("file" -> ujson.Str(l.file.replace('\\', '/')), "line" -> ujson.Num(l.line))

  private def protocolId(id: ProtocolId): ujson.Value =
    ujson.Obj("kind" -> ujson.Str(id.kind.toString), "name" -> ujson.Str(id.name), "version" -> ujson.Str(id.version))

  private def generatorId(id: GeneratorId): ujson.Value =
    ujson.Obj("qualifiedName" -> ujson.Str(id.qualifiedName), "version" -> ujson.Str(id.version))

  private def layerPath(l: LayerPath): ujson.Value = ujson.Arr.from(l.segments.map(ujson.Str(_)))

  private def interfacePath(p: InterfacePath): ujson.Value =
    ujson.Arr.from(p.segments.map {
      case InterfacePath.Segment.Field(n) => ujson.Obj("field" -> ujson.Str(n))
      case InterfacePath.Segment.Index(i) => ujson.Obj("index" -> ujson.Num(i))
    })

  private def interface(tpe: ProtocolInterface): ujson.Value = tpe match
    case ProtocolInterface.Bundle(fields) =>
      ujson.Obj(
        "type"   -> ujson.Str("bundle"),
        "fields" -> ujson.Arr.from(fields.map { f =>
          ujson.Obj("name" -> ujson.Str(f.name), "flip" -> ujson.Bool(f.flip), "tpe" -> interface(f.tpe))
        })
      )
    case ProtocolInterface.Vec(n, e)      =>
      ujson.Obj("type" -> ujson.Str("vec"), "size" -> ujson.Num(n), "element" -> interface(e))
    case ProtocolInterface.UInt(w)        => ujson.Obj("type" -> ujson.Str("uint"), "width" -> ujson.Num(w))
    case ProtocolInterface.SInt(w)        => ujson.Obj("type" -> ujson.Str("sint"), "width" -> ujson.Num(w))
    case ProtocolInterface.Bool           => ujson.Obj("type" -> ujson.Str("bool"))
    case ProtocolInterface.Clock          => ujson.Obj("type" -> ujson.Str("clock"))
    case ProtocolInterface.Reset          => ujson.Obj("type" -> ujson.Str("reset"))
    case ProtocolInterface.Probe(i, l)    =>
      ujson.Obj("type" -> ujson.Str("probe"), "inner" -> interface(i), "layer" -> layerPath(l))

  /** `topology.json`: the module tree, nodes, binds, dependencies and verification endpoints with stable ids. */
  def topology(spec: DesignSpec): ujson.Value =
    ujson.Obj(
      "modules" -> ujson.Arr.from(spec.moduleOrder.map { id =>
        spec.modules(id) match
          case w: WrapperModuleSpec   =>
            ujson.Obj(
              "id"       -> moduleId(id),
              "kind"     -> ujson.Str("wrapper"),
              "children" -> ujson.Arr.from(w.children.map(ujson.Str(_))),
              "loc"      -> loc(w.loc)
            )
          case g: GeneratorModuleSpec =>
            ujson.Obj(
              "id"           -> moduleId(id),
              "kind"         -> ujson.Str("generator"),
              "generator"    -> generatorId(g.entry.id),
              "nodes"        -> ujson.Arr.from(g.nodes.map { n =>
                ujson.Obj(
                  "id"        -> nodeId(ModuleNodeId(id, n.name)),
                  "direction" -> ujson.Str(n.direction.toString.toLowerCase),
                  "protocol"  -> protocolId(n.protocol.id),
                  "order"     -> ujson.Num(n.order),
                  "loc"       -> loc(n.loc)
                )
              }),
              "dependencies" -> ujson.Arr.from(g.dependencies.map { d =>
                ujson.Obj(
                  "inward"  -> nodeId(ModuleNodeId(id, d.from)),
                  "outward" -> nodeId(ModuleNodeId(id, d.to)),
                  "order"   -> ujson.Num(d.order),
                  "loc"     -> loc(d.loc)
                )
              }),
              "dvSources"    -> ujson.Arr.from(g.dvSources.map { s =>
                ujson.Obj(
                  "id"       -> dvSourceId(DVSourceId(id, s.name)),
                  "protocol" -> protocolId(s.protocol.id),
                  "layer"    -> layerPath(s.layer),
                  "order"    -> ujson.Num(s.order),
                  "loc"      -> loc(s.loc)
                )
              }),
              "dvSinks"      -> ujson.Arr.from(g.dvSinks.map { s =>
                ujson.Obj(
                  "id"       -> dvSinkId(DVSinkId(id, s.name)),
                  "protocol" -> protocolId(s.protocol.id),
                  "order"    -> ujson.Num(s.order),
                  "loc"      -> loc(s.loc)
                )
              }),
              "loc"          -> loc(g.loc)
            )
      }),
      "binds"   -> ujson.Arr.from(spec.binds.map { b =>
        ujson.Obj("id" -> bindId(b.bindId), "declaredIn" -> moduleId(b.declaredIn), "loc" -> loc(b.loc))
      }),
      "dvBinds" -> ujson.Arr.from(spec.dvBinds.map { b =>
        ujson.Obj(
          "id"         -> dvBindId(b.bindId),
          "order"      -> ujson.Num(b.order),
          "declaredIn" -> moduleId(b.declaredIn),
          "loc"        -> loc(b.loc)
        )
      })
    )

  private def write(rw: upickle.default.ReadWriter[?], value: Any): ujson.Value =
    upickle.default.writeJs(value)(
      using rw.asInstanceOf[upickle.default.ReadWriter[Any]]
    )

  /** `edges.json`: settled design edges and per-sink verification results, with protocol-serialized parameters. */
  def edges(resolved: ResolvedDesign): ujson.Value =
    ujson.Obj(
      "designEdges" -> ujson.Arr.from(resolved.edges.map { e =>
        val p        = e.protocol
        ujson.Obj(
          "id"        -> bindId(e.bind),
          "protocol"  -> protocolId(p.id),
          "down"      -> write(p.downRW, e.down),
          "up"        -> write(p.upRW, e.up),
          "edge"      -> write(p.edgeRW, e.edge),
          "interface" -> interface(e.interface)
        )
      }),
      "dvResults"   -> ujson.Arr.from(resolved.dvGroups.map { g =>
        val p       = g.protocol
        ujson.Obj(
          "sink"       -> dvSinkId(g.sink),
          "protocol"   -> protocolId(p.id),
          "binds"      -> ujson.Arr.from(g.binds.map(dvBindId)),
          "downs"      -> ujson.Arr.from(g.downs.map(write(p.downRW, _))),
          "layers"     -> ujson.Arr.from(g.layers.map(layerPath)),
          "edge"       -> write(p.edgeRW, g.edge),
          "interfaces" -> ujson.Obj(
            "sources"   -> ujson.Arr.from(g.interfaces.sources.map(interface)),
            "sink"      -> interface(g.interfaces.sink),
            "sinkPaths" -> ujson.Arr.from(g.interfaces.sinkPaths.map(interfacePath))
          )
        )
      })
    )

  /** `plan.json`: per-module port plans, wire plans and layer declarations, each tagged with its origin. */
  def plan(resolved: ResolvedDesign): ujson.Value =
    def origin(o: PlanOrigin):       ujson.Value = o match
      case PlanOrigin.Design(b)       => ujson.Obj("design" -> bindId(b))
      case PlanOrigin.Verification(b) => ujson.Obj("verification" -> dvBindId(b))
    def endpoint(e: LocalEndpoint):  ujson.Value = e match
      case LocalEndpoint.ThisPort(name)             => ujson.Obj("port" -> ujson.Str(name.encoded))
      case LocalEndpoint.ChildPort(inst, port, sub) =>
        ujson.Obj("instance" -> ujson.Str(inst), "port" -> ujson.Str(port.encoded), "sub" -> interfacePath(sub))
    ujson.Obj(
      "ports"  -> ujson.Arr.from(resolved.portPlans.map { p =>
        ujson.Obj(
          "module"    -> moduleId(p.module),
          "direction" -> ujson.Str(p.direction.toString.toLowerCase),
          "name"      -> ujson.Str(p.name.encoded),
          "interface" -> interface(p.interface),
          "origin"    -> origin(p.origin),
          "loc"       -> loc(p.loc)
        )
      }),
      "wires"  -> ujson.Arr.from(resolved.wirePlans.map { w =>
        ujson.Obj(
          "module" -> moduleId(w.module),
          "from"   -> endpoint(w.from),
          "to"     -> endpoint(w.to),
          "origin" -> origin(w.origin),
          "loc"    -> loc(w.loc)
        )
      }),
      "layers" -> ujson.Obj.from(
        resolved.layerDecls.toVector
          .filterNot(_._2.isEmpty)
          .sortBy(_._1.path.mkString("/"))
          .map { (m, tree) =>
            m.show -> ujson.Arr.from(tree.paths().map(p => ujson.Arr.from(p.map(ujson.Str(_)))))
          }
      )
    )

  /** `params.json`: one record per generator module — module id, generator id, and the FullParam value. */
  def params(resolved: ResolvedDesign): ujson.Value =
    ujson.Arr.from(resolved.generatorModules.map { g =>
      ujson.Obj(
        "module"    -> moduleId(g.module),
        "generator" -> generatorId(g.entry.id),
        "fullParam" -> g.encodedFullParam
      )
    })
