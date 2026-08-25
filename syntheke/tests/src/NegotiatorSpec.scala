// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.syntheke.tests

import me.jiuyang.syntheke.*
import utest.*

/** A toy width-negotiation protocol: `Down` requests a width, `Up` offers a capacity, the edge settles to the requested
  * width when it fits.
  */
object Wid extends Protocol:
  type Down = Int
  type Up   = Int
  type Edge = Int
  val id = ProtocolId(ProtocolKind.Design, "wid", "1.0")
  def negotiate(down: Int, up: Int): Either[TermViolation, Int] =
    if down <= up then Right(down) else Left(TermViolation(s"requested width $down exceeds capacity $up"))
  def interfaceOf(edge: Int):        ProtocolBundle             =
    ProtocolBundle(ProtocolInterface.Field("data", false, ProtocolInterface.UInt(edge)))
  def render(edge: Int):             RenderedValue              = RenderedValue(edge.toString, Map("width" -> edge.toString))
  val downCodec:                     Codec[Int]                 = Codec.fromReadWriter[Int](ujson.Str("int"))
  val upCodec:                       Codec[Int]                 = Codec.fromReadWriter[Int](ujson.Str("int"))
  val edgeCodec:                     Codec[Int]                 = Codec.fromReadWriter[Int](ujson.Str("int"))

/** A toy probe protocol: each source contributes one probed UInt of the declared width. */
object Trace extends DVProtocol:
  type Down = Int
  type Edge = Vector[Int]
  val id = ProtocolId(ProtocolKind.Verification, "trace", "1.0")
  def resolve(downs: Vector[Int]):                                Either[TermViolation, Vector[Int]]  =
    if downs.forall(_ > 0) then Right(downs) else Left(TermViolation("width must be positive"))
  def interfacesOf(edge: Vector[Int], layers: Vector[LayerPath]): Either[TermViolation, DVInterfaces] =
    val sources = edge.zip(layers).map { (w, l) =>
      ProtocolBundle(ProtocolInterface.Field("sig", false, ProtocolInterface.Probe(ProtocolInterface.UInt(w), l)))
    }
    val sink    = ProtocolInterface.Bundle(
      edge.indices.toVector.map(i => ProtocolInterface.Field(s"src$i", false, sources(i)))
    )
    Right(DVInterfaces(sources, sink, edge.indices.toVector.map(i => InterfacePath.root.field(s"src$i"))))
  def render(edge: Vector[Int]):                                  RenderedValue                       = RenderedValue(edge.mkString(","), Map.empty)
  val downCodec:                                                  Codec[Int]                          = Codec.fromReadWriter[Int](ujson.Str("int"))
  val edgeCodec:                                                  Codec[Vector[Int]]                  = Codec.fromReadWriter[Vector[Int]](ujson.Str("ints"))

object NegotiatorSpec extends TestSuite:

  def intEntry(name: String) =
    new GeneratorEntry[Int](GeneratorId(s"test.$name", "1"), Codec.fromReadWriter[Int](ujson.Str("int")))

  /** prod(32) ─┐ ┌─ c0 (deep in `sub`, capacity 64) ├─ xbar (2 in, 2 out) ─────┤ dma(24) ─┘ └─ c1 (capacity given)
    */
  def buildSoc(c1Capacity: Int): DesignSpec =
    var prodOut: OutwardNodeBuilder[Wid.type] = null
    var dmaOut:  OutwardNodeBuilder[Wid.type] = null
    var xIn0:    InwardNodeBuilder[Wid.type]  = null
    var xIn1:    InwardNodeBuilder[Wid.type]  = null
    var xOut0:   OutwardNodeBuilder[Wid.type] = null
    var xOut1:   OutwardNodeBuilder[Wid.type] = null
    var c0In:    InwardNodeBuilder[Wid.type]  = null
    var c1In:    InwardNodeBuilder[Wid.type]  = null

    Design {
      generator("prod", intEntry("Prod")) {
        prodOut = outward(Wid)("out").dFn(_ => Right(32))
        parametersConst(0)
      }
      generator("dma", intEntry("Dma")) {
        dmaOut = outward(Wid)("out").dFn(_ => Right(24))
        parametersConst(0)
      }
      generator("xbar", intEntry("Xbar")) {
        val in0        = inward(Wid)("in0")
        val in1        = inward(Wid)("in1")
        val out0       = outward(Wid)("out0")
        val out1       = outward(Wid)("out1")
        val (d00, u00) = depend(in0, out0)
        val (d01, u01) = depend(in0, out1)
        val (d10, u10) = depend(in1, out0)
        val (d11, u11) = depend(in1, out1)
        out0.dFn(ctx => Right(ctx(d00) max ctx(d10)))
        out1.dFn(ctx => Right(ctx(d01) max ctx(d11)))
        in0.uFn(ctx => Right(ctx(u00) min ctx(u01)))
        in1.uFn(ctx => Right(ctx(u10) min ctx(u11)))
        parameters(view => Right(view.nodes.map(_.edge.edgeAs(Wid))))(_.sum)
        xIn0 = in0; xIn1 = in1; xOut0 = out0; xOut1 = out1
      }
      wrapper("sub") {
        generator("c0", intEntry("C0")) {
          c0In = inward(Wid)("in").uFn(_ => Right(64))
          parametersConst(0)
        }
      }
      generator("c1", intEntry("C1")) {
        c1In = inward(Wid)("in").uFn(_ => Right(c1Capacity))
        parametersConst(0)
      }
      xIn0 <-- prodOut
      xIn1 <-- dmaOut
      c0In <-- xOut0
      c1In <-- xOut1
    }

  val tests = Tests {

    test("negotiation settles every edge and computes generator parameters") {
      val resolved = Negotiator.negotiate(buildSoc(c1Capacity = 64)).toOption.get

      // Downstream widths: max(32, 24) = 32 on both xbar outputs.
      val edgeValues = resolved.edges.map(e => e.bind.source.show -> e.edgeAs(Wid)).toMap
      assert(edgeValues(ModuleNodeId(ModuleId.root / "prod", "out").show) == 32)
      assert(edgeValues(ModuleNodeId(ModuleId.root / "dma", "out").show) == 24)
      assert(edgeValues(ModuleNodeId(ModuleId.root / "xbar", "out0").show) == 32)
      assert(edgeValues(ModuleNodeId(ModuleId.root / "xbar", "out1").show) == 32)

      // The xbar's protocol parameter is the vector of its four edge widths; FullParam is their sum.
      val xbar = resolved.generatorModule(ModuleId.root / "xbar").get
      assert(xbar.fullParam == 32 + 24 + 32 + 32)
      assert(xbar.encodedFullParam == ujson.Num(32 + 24 + 32 + 32))

      // Up values propagate back: prod sees min over reachable capacities on in0's reachable outputs.
      val prodEdge = resolved.edge(resolved.edges.head.bind).get
      assert(prodEdge.upAs(Wid) == 64)
    }

    test("cross-hierarchy edge plans an Input dangle port on the intermediate wrapper") {
      val resolved  = Negotiator.negotiate(buildSoc(c1Capacity = 64)).toOption.get
      val sub       = ModuleId.root / "sub"
      val dangles   = resolved.portPlans.filter(_.module == sub)
      assert(dangles.map(_.name.encoded) == Vector("inst_c0_node_in_in"))
      assert(dangles.head.direction == PortDirection.Input)
      // sub: wire from its own dangle port into c0's port; root: wire from xbar.out0 to sub's dangle.
      val subWires  = resolved.wirePlans.filter(_.module == sub)
      assert(
        subWires == Vector(
          WirePlan(
            sub,
            LocalEndpoint.ThisPort(PortName("inst", "c0", "node", "in", "in")),
            LocalEndpoint.ChildPort("c0", PortName("in")),
            subWires.head.origin,
            subWires.head.loc
          )
        )
      )
      val rootWires = resolved.wirePlans.filter(w => w.module == ModuleId.root && w.origin == subWires.head.origin)
      assert(
        rootWires.map(w => (w.from, w.to)) == Vector(
          LocalEndpoint.ChildPort("xbar", PortName("out0")) ->
            LocalEndpoint.ChildPort("sub", PortName("inst", "c0", "node", "in", "in"))
        )
      )
    }

    test("capacity conflicts are reported per edge as N3, in bulk") {
      // The 16-wide c1 constrains, through the xbar's uFns, every edge that can reach it:
      // prod -> in0 (32 > 16), dma -> in1 (24 > 16), out1 -> c1 (32 > 16). Only out0 -> c0 settles.
      val errors        = Negotiator.negotiate(buildSoc(c1Capacity = 16)).swap.toOption.get
      assert(errors.sizeIs == 3)
      val failedSources = errors.collect { case NegotiationError.SettleFailed(SettleSubject.Design(bind), _, _) =>
        bind.source.show
      }
      assert(
        failedSources.toSet == Set(
          ModuleNodeId(ModuleId.root / "prod", "out").show,
          ModuleNodeId(ModuleId.root / "dma", "out").show,
          ModuleNodeId(ModuleId.root / "xbar", "out1").show
        )
      )
    }

    test("propagation failure is reported as N2 and downstream is blocked without derived errors") {
      var out: OutwardNodeBuilder[Wid.type] = null
      var in:  InwardNodeBuilder[Wid.type]  = null
      val spec   = Design {
        generator("bad", intEntry("Bad")) {
          out = outward(Wid)("out").dFn(_ => Left(PropagationViolation("no width available")))
          parametersConst(0)
        }
        generator("sink", intEntry("Sink")) {
          in = inward(Wid)("in").uFn(_ => Right(64))
          parametersConst(0)
        }
        in <-- out
      }
      val errors = Negotiator.negotiate(spec).swap.toOption.get
      assert(errors.sizeIs == 1)
      errors.head match
        case NegotiationError.PropagationFailed(_, node, NodeDirection.Outward, _, _, violation, _) =>
          assert(node == ModuleNodeId(ModuleId.root / "bad", "out"))
          assert(violation.message == "no width available")
        case other                                                                                  => assert(false)
    }

    test("a parameter dependency cycle is reported as N9") {
      var aOut: OutwardNodeBuilder[Wid.type] = null
      var aIn:  InwardNodeBuilder[Wid.type]  = null
      var bOut: OutwardNodeBuilder[Wid.type] = null
      var bIn:  InwardNodeBuilder[Wid.type]  = null
      val spec   = Design {
        generator("a", intEntry("A")) {
          val i      = inward(Wid)("in")
          val o      = outward(Wid)("out")
          val (d, u) = depend(i, o)
          o.dFn(ctx => Right(ctx(d)))
          i.uFn(ctx => Right(ctx(u)))
          parametersConst(0)
          aIn = i; aOut = o
        }
        generator("b", intEntry("B")) {
          val i      = inward(Wid)("in")
          val o      = outward(Wid)("out")
          val (d, u) = depend(i, o)
          o.dFn(ctx => Right(ctx(d)))
          i.uFn(ctx => Right(ctx(u)))
          parametersConst(0)
          bIn = i; bOut = o
        }
        bIn <-- aOut
        aIn <-- bOut
      }
      val errors = Negotiator.negotiate(spec).swap.toOption.get
      assert(errors.exists {
        case NegotiationError.IllegalStructure(detail, _, _) => detail.contains("cycle")
        case _                                               => false
      })
    }

    test("binding one node twice is reported as N4") {
      var out: OutwardNodeBuilder[Wid.type] = null
      var in0: InwardNodeBuilder[Wid.type]  = null
      var in1: InwardNodeBuilder[Wid.type]  = null
      val spec   = Design {
        generator("p", intEntry("P")) {
          out = outward(Wid)("out").dFn(_ => Right(8))
          parametersConst(0)
        }
        generator("c", intEntry("C")) {
          in0 = inward(Wid)("in0").uFn(_ => Right(8))
          in1 = inward(Wid)("in1").uFn(_ => Right(8))
          parametersConst(0)
        }
        in0 <-- out
        in1 <-- out
      }
      val errors = Negotiator.negotiate(spec).swap.toOption.get
      assert(errors.exists {
        case NegotiationError.IllegalBind(detail, _, _, _) => detail.contains("source of 2 binds")
        case _                                             => false
      })
      // in0 / in1: one of them stays unbound is NOT the case here — both are bound once; only the source doubles.
      assert(!errors.exists {
        case NegotiationError.IllegalBind(detail, _, _, _) => detail.contains("target of")
        case _                                             => false
      })
    }

    test("probe sources route to an ancestor sink with layers and sink sub-paths") {
      val layerCosim = LayerPath(Vector("verification", "cosim"))
      var src0: DVSourceRef[Trace.type]      = null
      var src1: DVSourceRef[Trace.type]      = null
      var snk:  DVSinkRef[Trace.type]        = null
      var pOut: OutwardNodeBuilder[Wid.type] = null
      var cIn:  InwardNodeBuilder[Wid.type]  = null
      val spec     = Design {
        wrapper("cluster") {
          generator("core", intEntry("Core")) {
            pOut = outward(Wid)("mem").dFn(_ => Right(32))
            src0 = dvSource(Trace)("rob", 8, layerCosim)
            src1 = dvSource(Trace)("lsu", 4, layerCosim)
            parametersConst(0)
          }
        }
        generator("mem", intEntry("Mem")) {
          cIn = inward(Wid)("in").uFn(_ => Right(64))
          parametersConst(0)
        }
        generator("cosim", intEntry("Cosim")) {
          snk = dvSink(Trace)("taps")
          parametersConst(0)
        }
        cIn <-- pOut
        snk <-- src0
        snk <-- src1
      }
      val resolved = Negotiator.negotiate(spec).toOption.get
      val group    = resolved.dvGroups.head
      assert(group.edgeAs(Trace) == Vector(8, 4))
      assert(group.interfaces.sinkPaths == Vector(InterfacePath.root.field("src0"), InterfacePath.root.field("src1")))

      // The cluster wrapper carries Output dangles for both probes and declares the layer.
      val cluster = ModuleId.root / "cluster"
      val probes  = resolved.portPlans.filter(p => p.module == cluster && p.origin.isInstanceOf[PlanOrigin.Verification])
      assert(probes.map(_.name.encoded) == Vector("inst_core_dv$msource_rob_out", "inst_core_dv$msource_lsu_out"))
      assert(resolved.layerDecls(cluster).paths() == Vector(Vector("verification"), Vector("verification", "cosim")))
      assert(
        resolved.layerDecls(ModuleId.root).paths() == Vector(Vector("verification"), Vector("verification", "cosim"))
      )

      // Root wires end inside the sink generator's port at the per-source sub-path.
      val rootDvWires =
        resolved.wirePlans.filter(w => w.module == ModuleId.root && w.origin.isInstanceOf[PlanOrigin.Verification])
      assert(
        rootDvWires.map(_.to) == Vector(
          LocalEndpoint.ChildPort("cosim", PortName("taps"), InterfacePath.root.field("src0")),
          LocalEndpoint.ChildPort("cosim", PortName("taps"), InterfacePath.root.field("src1"))
        )
      )
    }

    test("the four tooling exports carry stable identifiers and canonical order") {
      val resolved = Negotiator.negotiate(buildSoc(c1Capacity = 64)).toOption.get
      val topology = Export.topology(resolved.spec)
      assert(topology("modules").arr.head("id") == ujson.Arr()) // root first: hierarchy preorder
      assert(topology("binds").arr.size == 4)
      val edges   = Export.edges(resolved)
      assert(edges("designEdges").arr.map(_("id")("order").num.toInt) == Seq(0, 1, 2, 3))
      assert(edges("designEdges").arr.head("render")("attributes")("width") == ujson.Str("32"))
      val plan    = Export.plan(resolved)
      assert(plan("ports").arr.exists(_("name") == ujson.Str("inst_c0_node_in_in")))
      val params  = Export.params(resolved)
      val xbarRec = params.arr.find(_("module") == ujson.Arr("xbar")).get
      assert(xbarRec("generator")("qualifiedName") == ujson.Str("test.Xbar"))
      assert(xbarRec("fullParam") == ujson.Num(120))
    }

    test("a sink whose parent is not a strict ancestor of the source is reported as N8") {
      val layer = LayerPath(Vector("verification", "assert"))
      var src:  DVSourceRef[Trace.type]      = null
      var snk:  DVSinkRef[Trace.type]        = null
      var pOut: OutwardNodeBuilder[Wid.type] = null
      var cIn:  InwardNodeBuilder[Wid.type]  = null
      val spec   = Design {
        generator("core", intEntry("Core")) {
          pOut = outward(Wid)("mem").dFn(_ => Right(32))
          src = dvSource(Trace)("rob", 8, layer)
          parametersConst(0)
        }
        wrapper("island") {
          generator("cosim", intEntry("Cosim")) {
            snk = dvSink(Trace)("taps")
            parametersConst(0)
          }
        }
        generator("mem", intEntry("Mem")) {
          cIn = inward(Wid)("in").uFn(_ => Right(64))
          parametersConst(0)
        }
        cIn <-- pOut
        snk <-- src
      }
      val errors = Negotiator.negotiate(spec).swap.toOption.get
      assert(errors.exists {
        case NegotiationError.IllegalVerification(detail, _, _, _) => detail.contains("strict ancestor")
        case _                                                     => false
      })
    }
  }
