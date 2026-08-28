// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.syntheke

/** On-demand JSON exports of a [[ResolvedDesign]] (doc @ch-tooling).
  *
  * Four whole-design files: `topology.json`, `edges.json`, `plan.json`, `params.json`. Orderings follow the
  * normalization rules of the design document (@sec-determinism): modules in hierarchy preorder, design edges in bind
  * declaration order, probe sources in hierarchy preorder then declaration order, layer trees name-sorted.
  */
object Export:

  private def moduleId(id: ModuleId): ujson.Value = ujson.Arr.from(id.path.map(ujson.Str(_)))

  private def nodeId(id: ModuleNodeId): ujson.Value =
    ujson.Obj("module" -> moduleId(id.module), "name" -> ujson.Str(id.name))

  private def bindId(id: BindId): ujson.Value =
    ujson.Obj("order" -> ujson.Num(id.order), "source" -> nodeId(id.source), "target" -> nodeId(id.target))

  private def dvSourceId(id: DVSourceId): ujson.Value =
    ujson.Obj("module" -> moduleId(id.module), "name" -> ujson.Str(id.name))

  private def loc(l: (sourcecode.File, sourcecode.Line)): ujson.Value =
    ujson.Obj("file" -> ujson.Str(l._1.value.replace('\\', '/')), "line" -> ujson.Num(l._2.value))

  // The one canonical encoding lives with the types (Interface.scala); the exports just apply it.
  private def layerPath(l:   LayerPath):         ujson.Value = upickle.default.writeJs(l)
  private def interface(tpe: ProtocolInterface): ujson.Value = upickle.default.writeJs(tpe)

  /** `topology.json`: the module tree, nodes, binds, dependencies and probe sources with stable ids. */
  def topology(spec: DesignSpec): ujson.Value =
    ujson.Obj(
      "modules"   -> ujson.Arr.from(spec.moduleOrder.map { id =>
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
              "generator"    -> ujson.Str(g.entry.name),
              "nodes"        -> ujson.Arr.from(g.nodes.map { n =>
                ujson.Obj(
                  "id"        -> nodeId(ModuleNodeId(id, n.name)),
                  "direction" -> ujson.Str(n.direction.toString.toLowerCase),
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
                  "id"        -> dvSourceId(DVSourceId(id, s.name)),
                  "down"      -> write(s.protocol.downRW, s.down),
                  "layer"     -> layerPath(s.layer),
                  "interface" -> interface(s.interface),
                  "order"     -> ujson.Num(s.order),
                  "loc"       -> loc(s.loc)
                )
              }),
              "loc"          -> loc(g.loc)
            )
      }),
      "binds"     -> ujson.Arr.from(spec.binds.map { b =>
        ujson.Obj("id" -> bindId(b.bindId), "declaredIn" -> moduleId(b.declaredIn), "loc" -> loc(b.loc))
      }),
      "testbench" -> spec.testbench.fold[ujson.Value](ujson.Null)(moduleId)
    )

  private def write(rw: upickle.default.ReadWriter[?], value: Any): ujson.Value =
    upickle.default.writeJs(value)(
      using rw.asInstanceOf[upickle.default.ReadWriter[Any]]
    )

  /** `edges.json`: settled design edges with protocol-serialized parameters. */
  def edges(resolved: ResolvedDesign): ujson.Value =
    ujson.Obj(
      "designEdges" -> ujson.Arr.from(resolved.edges.map { e =>
        val p        = e.protocol
        ujson.Obj(
          "id"        -> bindId(e.bind),
          "down"      -> write(p.downRW, e.down),
          "up"        -> write(p.upRW, e.up),
          "edge"      -> write(p.edgeRW, e.edge),
          "interface" -> interface(e.interface)
        )
      })
    )

  /** `plan.json`: per-module port plans, wire plans and layer declarations, each tagged with its origin. */
  def plan(resolved: ResolvedDesign): ujson.Value =
    def origin(o: PlanOrigin):       ujson.Value = o match
      case PlanOrigin.Design(b)       => ujson.Obj("design" -> bindId(b))
      case PlanOrigin.Verification(s) => ujson.Obj("verification" -> dvSourceId(s))
    def endpoint(e: LocalEndpoint):  ujson.Value = e match
      case LocalEndpoint.ThisPort(name)        => ujson.Obj("port" -> ujson.Str(name.encoded))
      case LocalEndpoint.ChildPort(inst, port) =>
        ujson.Obj("instance" -> ujson.Str(inst), "port" -> ujson.Str(port.encoded))
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
        "generator" -> ujson.Str(g.entry.name),
        "fullParam" -> g.encodedFullParam
      )
    })
