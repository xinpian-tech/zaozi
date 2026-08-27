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
  def negotiate(down: Int, up: Int): Either[Violation, Int]          =
    if down <= up then Right(down) else Left(Violation(s"requested width $down exceeds capacity $up"))
  def interfaceOf(edge: Int):        ProtocolBundle                  =
    ProtocolBundle(ProtocolInterface.Field("data", ProtocolInterface.UInt(edge)))
  val downRW:                        upickle.default.ReadWriter[Int] = summon
  val upRW:                          upickle.default.ReadWriter[Int] = summon
  val edgeRW:                        upickle.default.ReadWriter[Int] = summon

/** A toy probe protocol: each source contributes one probed UInt of the declared width. */
object Trace extends DVProtocol:
  type Down = Int
  type Edge = Vector[Int]
  def resolve(downs: Vector[Int]):                                Either[Violation, Vector[Int]]          =
    if downs.forall(_ > 0) then Right(downs) else Left(Violation("width must be positive"))
  def interfacesOf(edge: Vector[Int], layers: Vector[LayerPath]): Either[Violation, DVInterfaces]         =
    val sources = edge.zip(layers).map { (w, l) =>
      ProtocolBundle(ProtocolInterface.Field("sig", ProtocolInterface.Probe(ProtocolInterface.UInt(w), l)))
    }
    val sink    = ProtocolInterface.Bundle(
      edge.indices.toVector.map(i => ProtocolInterface.Field(s"src$i", sources(i)))
    )
    Right(DVInterfaces(sources, sink, edge.indices.toVector.map(i => InterfacePath.root.field(s"src$i"))))
  val downRW:                                                     upickle.default.ReadWriter[Int]         = summon
  val edgeRW:                                                     upickle.default.ReadWriter[Vector[Int]] = summon

object NegotiatorSpec extends TestSuite:

  def intEntry(name: String) = new GeneratorEntry[Int](s"test.$name")

  /** prod(32) and dma(24) feed a 2x2 xbar; c0 (capacity 64) lives one wrapper deep, c1's capacity is a knob. */
  def buildSoc(c1Capacity: Int): DesignSpec =
    Design {
      val prod                       = generator(intEntry("Prod")) {
        parametersConst(0)
        val out = outward(Wid).dFn(_ => Right(32))
        out
      }
      val dma                        = generator(intEntry("Dma")) {
        parametersConst(0)
        val out = outward(Wid).dFn(_ => Right(24))
        out
      }
      val xbar                       = generator(intEntry("Xbar")) {
        val in0        = inward(Wid)
        val in1        = inward(Wid)
        val out0       = outward(Wid)
        val out1       = outward(Wid)
        val (d00, u00) = depend(in0, out0)
        val (d01, u01) = depend(in0, out1)
        val (d10, u10) = depend(in1, out0)
        val (d11, u11) = depend(in1, out1)
        out0.dFn(ctx => Right(ctx(d00) max ctx(d10)))
        out1.dFn(ctx => Right(ctx(d01) max ctx(d11)))
        in0.uFn(ctx => Right(ctx(u00) min ctx(u01)))
        in1.uFn(ctx => Right(ctx(u10) min ctx(u11)))
        parameters(view => Right(view.nodes.map(_.edge.edgeAs(Wid)).sum))
        (in0, in1, out0, out1)
      }
      val (xIn0, xIn1, xOut0, xOut1) = xbar
      val sub                        = wrapper {
        val c0 = generator(intEntry("C0")) {
          parametersConst(0)
          val in = inward(Wid).uFn(_ => Right(64))
          in
        }
        c0
      }
      val c1                         = generator(intEntry("C1")) {
        parametersConst(0)
        val in = inward(Wid).uFn(_ => Right(c1Capacity))
        in
      }
      xIn0 <-- prod
      xIn1 <-- dma
      sub <-- xOut0
      c1 <-- xOut1
    }

  val tests = Tests {

    test("negotiation settles every edge and computes generator parameters") {
      val resolved = Negotiator.negotiate(buildSoc(c1Capacity = 64))

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
      val resolved  = Negotiator.negotiate(buildSoc(c1Capacity = 64))
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

    test("a capacity conflict fails fast at the first bind that cannot settle") {
      // The 16-wide c1 constrains, through the xbar's uFns, every edge that can reach it; settlement runs in bind
      // declaration order, so prod -> in0 (32 > 16) throws first.
      val e = intercept[NegotiationException](Negotiator.negotiate(buildSoc(c1Capacity = 16)))
      assert(e.getMessage.contains("settle failed"))
      assert(e.getMessage.contains("prod#out"))
      assert(e.getMessage.contains("32"))
    }

    test("a propagation conflict reports the node, the violation and the input snapshot") {
      val spec = Design {
        val bad  = generator(intEntry("Bad")) {
          parametersConst(0)
          val out = outward(Wid).dFn(_ => Left(Violation("no width available")))
          out
        }
        val sink = generator(intEntry("Sink")) {
          parametersConst(0)
          val in = inward(Wid).uFn(_ => Right(64))
          in
        }
        sink <-- bad
      }
      val e    = intercept[NegotiationException](Negotiator.negotiate(spec))
      assert(e.getMessage.contains("propagation failed"))
      assert(e.getMessage.contains("bad#out"))
      assert(e.getMessage.contains("no width available"))
    }

    test("a parameter dependency cycle reports exactly the cycle members") {
      // A reusable definition: the endpoint class declares nodes as fields; the def binds the entry and forwards
      // the build context, so the instance name comes from the call-site val.
      final class LoopbackPorts(
        using GeneratorScope[Int])
          extends Endpoints:
        val in             = inward(Wid)
        val out            = outward(Wid)
        private val (d, u) = depend(in, out)
        out.dFn(ctx => Right(ctx(d)))
        in.uFn(ctx => Right(ctx(u)))
        parametersConst(0)
      def loopback(
      )(
        using
        ws:   WrapperScope,
        name: sourcecode.Name,
        loc:  SourceLocation
      ) =
        generator(intEntry(name.value.capitalize))(new LoopbackPorts)
      val spec = Design {
        val a = loopback()
        val b = loopback()
        b.in <-- a.out
        a.in <-- b.out
      }
      val e    = intercept[NegotiationException](Negotiator.negotiate(spec))
      assert(e.getMessage.contains("cycle"))
      Vector("a#in", "a#out", "b#in", "b#out").foreach(n => assert(e.getMessage.contains(n)))
    }

    test("binding one node twice is rejected") {
      val spec = Design {
        val p          = generator(intEntry("P")) {
          parametersConst(0)
          val out = outward(Wid).dFn(_ => Right(8))
          out
        }
        val c          = generator(intEntry("C")) {
          parametersConst(0)
          val in0 = inward(Wid).uFn(_ => Right(8))
          val in1 = inward(Wid).uFn(_ => Right(8))
          (in0, in1)
        }
        val (in0, in1) = c
        in0 <-- p
        in1 <-- p
      }
      val e    = intercept[NegotiationException](Negotiator.negotiate(spec))
      assert(e.getMessage.contains("source of 2 binds"))
    }

    test("probe sources route to an ancestor sink with layers and sink sub-paths") {
      val layerCosim = LayerPath(Vector("verification", "cosim"))
      val spec       = Design {
        val cluster            = wrapper {
          val core = generator(intEntry("Core")) {
            parametersConst(0)
            val mem = outward(Wid).dFn(_ => Right(32))
            val rob = dvSource(Trace)(8, layerCosim)
            val lsu = dvSource(Trace)(4, layerCosim)
            (mem, rob, lsu)
          }
          core
        }
        val (pOut, src0, src1) = cluster
        val mem                = generator(intEntry("Mem")) {
          parametersConst(0)
          val in = inward(Wid).uFn(_ => Right(64))
          in
        }
        val cosim              = generator(intEntry("Cosim")) {
          parametersConst(0)
          val taps = dvSink(Trace)
          taps
        }
        mem <-- pOut
        cosim <-- src0
        cosim <-- src1
      }
      val resolved   = Negotiator.negotiate(spec)
      val group      = resolved.dvGroups.head
      assert(group.edgeAs(Trace) == Vector(8, 4))
      assert(group.interfaces.sinkPaths == Vector(InterfacePath.root.field("src0"), InterfacePath.root.field("src1")))

      // The cluster wrapper carries one pure-probe Output dangle per signal leaf and declares the layer.
      val cluster = ModuleId.root / "cluster"
      val probes  = resolved.portPlans.filter(p => p.module == cluster && p.origin.isInstanceOf[PlanOrigin.Verification])
      assert(
        probes.map(_.name.encoded) == Vector("inst_core_dv$msource_rob_sig_out", "inst_core_dv$msource_lsu_sig_out")
      )
      assert(probes.forall(_.interface.isInstanceOf[ProtocolInterface.Probe]))
      assert(resolved.layerDecls(cluster).paths() == Vector(Vector("verification"), Vector("verification", "cosim")))
      assert(
        resolved.layerDecls(ModuleId.root).paths() == Vector(Vector("verification"), Vector("verification", "cosim"))
      )

      // Root wires end inside the sink generator's port at the per-source sub-path extended by the leaf path.
      val rootDvWires =
        resolved.wirePlans.filter(w => w.module == ModuleId.root && w.origin.isInstanceOf[PlanOrigin.Verification])
      assert(
        rootDvWires.map(_.to) == Vector(
          LocalEndpoint.ChildPort("cosim", PortName("taps"), InterfacePath.root.field("src0").field("sig")),
          LocalEndpoint.ChildPort("cosim", PortName("taps"), InterfacePath.root.field("src1").field("sig"))
        )
      )
    }

    test("the four tooling exports carry stable identifiers and canonical order") {
      val resolved = Negotiator.negotiate(buildSoc(c1Capacity = 64))
      val topology = Export.topology(resolved.spec)
      assert(topology("modules").arr.head("id") == ujson.Arr()) // root first: hierarchy preorder
      assert(topology("binds").arr.size == 4)
      val edges   = Export.edges(resolved)
      assert(edges("designEdges").arr.map(_("id")("order").num.toInt) == Seq(0, 1, 2, 3))
      assert(edges("designEdges").arr.head("edge") == ujson.Num(32))
      val plan    = Export.plan(resolved)
      assert(plan("ports").arr.exists(_("name") == ujson.Str("inst_c0_node_in_in")))
      val params  = Export.params(resolved)
      val xbarRec = params.arr.find(_("module") == ujson.Arr("xbar")).get
      assert(xbarRec("generator") == ujson.Str("test.Xbar"))
      assert(xbarRec("fullParam") == ujson.Num(120))
    }

    test("a sink whose parent is not a strict ancestor of the source is rejected") {
      val layer = LayerPath(Vector("verification", "assert"))
      val spec  = Design {
        val core        = generator(intEntry("Core")) {
          parametersConst(0)
          val mem = outward(Wid).dFn(_ => Right(32))
          val rob = dvSource(Trace)(8, layer)
          (mem, rob)
        }
        val (pOut, src) = core
        val island      = wrapper {
          val cosim = generator(intEntry("Cosim")) {
            parametersConst(0)
            val taps = dvSink(Trace)
            taps
          }
          cosim
        }
        val mem         = generator(intEntry("Mem")) {
          parametersConst(0)
          val in = inward(Wid).uFn(_ => Right(64))
          in
        }
        mem <-- pOut
        island <-- src
      }
      val e     = intercept[NegotiationException](Negotiator.negotiate(spec))
      assert(e.getMessage.contains("strict ancestor"))
    }

    test("declaration-site contracts reject duplicates on the spot") {
      val dup = intercept[IllegalArgumentException] {
        Design {
          generator(intEntry("G")) {
            parametersConst(0)
            val x = inward(Wid).uFn(_ => Right(1))
            locally {
              given sourcecode.Name = sourcecode.Name("x")
              outward(Wid).dFn(_ => Right(1))
            }
          }
        }
      }
      assert(dup.getMessage.contains("duplicate endpoint name 'x'"))

      // Names become FIRRTL symbols verbatim; the shape rule rejects them at the declaration.
      val badInstance = intercept[IllegalArgumentException] {
        Design {
          given sourcecode.Name = sourcecode.Name("a.b")
          wrapper {}
        }
      }
      assert(badInstance.getMessage.contains("'a.b' is not a legal name"))
      val badEndpoint = intercept[IllegalArgumentException] {
        Design {
          generator(intEntry("G")) {
            parametersConst(0)
            given sourcecode.Name = sourcecode.Name("dv-source")
            inward(Wid).uFn(_ => Right(1))
          }
        }
      }
      assert(badEndpoint.getMessage.contains("'dv-source' is not a legal name"))
      val badLayer    = intercept[IllegalArgumentException] { LayerPath(Vector("verification", "")) }
      assert(badLayer.getMessage.contains("not a legal name"))

      // Generator modules are leaves: the enclosing WrapperScope stays visible inside a generator body, but
      // declaring structure there is rejected on the spot instead of silently attaching to the outer wrapper.
      val nested = intercept[IllegalArgumentException] {
        Design {
          generator(intEntry("G")) {
            parametersConst(0)
            given sourcecode.Name = sourcecode.Name("sub")
            wrapper {}
          }
        }
      }
      assert(nested.getMessage.contains("declared inside generator body"))

      // The body's return value is the only escape channel and it is typed: only endpoint containers leave.
      compileError("""Design { val x = generator(intEntry("G")) { parametersConst(0); 42 } }""")

      // A closure capturing the scope (here: a dFn running at negotiation) cannot declare into a frozen module.
      val late = Design {
        val g = generator(intEntry("G")) {
          parametersConst(0)
          val out = outward(Wid).dFn { _ =>
            inward(Wid)
            Right(1)
          }
          out
        }
        val c = generator(intEntry("C")) {
          parametersConst(0)
          val in = inward(Wid).uFn(_ => Right(1))
          in
        }
        c <-- g
      }
      val e    = intercept[IllegalArgumentException](Negotiator.negotiate(late))
      assert(e.getMessage.contains("outside its builder scope"))
    }

    test("cross-protocol references settle to the target edge, typed through the view") {
      val spec     = Design {
        val g            = generator(intEntry("R")) {
          val clkIn  = inward(Wid).uFn(_ => Right(64))
          val out    = outward(Wid).dFn(_ => Right(32))
          val outClk = out.ref(clkIn)
          // The reference reads clkIn's settled edge — typed, no name string, no cast.
          parameters(view => Right(view.edgeOf(outClk)))
          (clkIn, out)
        }
        val (clkIn, out) = g
        val clkSrc       = generator(intEntry("ClkSrc")) {
          parametersConst(0)
          val o = outward(Wid).dFn(_ => Right(16))
          o
        }
        val snk          = generator(intEntry("Snk")) {
          parametersConst(0)
          val in = inward(Wid).uFn(_ => Right(64))
          in
        }
        clkIn <-- clkSrc
        snk <-- out
      }
      val resolved = Negotiator.negotiate(spec)
      // clkSrc requests 16 within capacity 64: the clock edge settles to 16 and the ref hands it to parameters.
      assert(resolved.generatorModule(ModuleId.root / "g").get.fullParam == 16)
      val refs     = resolved.generatorModule(ModuleId.root / "g").get.view.nodes.flatMap(_.refs)
      assert(refs.map(r => (r.refName, r.target.name)) == Vector(("outClk", "clkIn")))
    }

    test("declarations are named by their binding val via sourceinfo") {
      val spec   = Design {
        val cluster = wrapper {
          val prod = generator(intEntry("P")) {
            parametersConst(0)
            val out = outward(Wid).dFn(_ => Right(32))
            val rob = dvSource(Trace)(8, LayerPath(Vector("verification")))
            (out, rob)
          }
          prod
        }
        val cons    = generator(intEntry("C")) {
          parametersConst(0)
          val in = inward(Wid).uFn(_ => Right(64))
          in
        }
        val cosim   = generator(intEntry("S")) {
          parametersConst(0)
          val taps = dvSink(Trace)
          taps
        }
        cons <-- cluster._1
        cosim <-- cluster._2
      }
      val prodId = ModuleId.root / "cluster" / "prod"
      assert(spec.generatorModule(prodId).get.node("out").nonEmpty)
      assert(spec.generatorModule(prodId).get.dvSources.map(_.name) == Vector("rob"))
      assert(spec.generatorModule(ModuleId.root / "cons").get.node("in").nonEmpty)
      assert(spec.generatorModule(ModuleId.root / "cosim").get.dvSinks.map(_.name) == Vector("taps"))
      Negotiator.negotiate(spec)
    }

    test("interface and view contracts reject illegal shapes on the spot") {
      val ff = intercept[IllegalArgumentException] {
        ProtocolInterface.Flipped(ProtocolInterface.Flipped(ProtocolInterface.Bool))
      }
      assert(ff.getMessage.contains("Flipped(Flipped"))
      val fv = intercept[IllegalArgumentException] {
        ProtocolInterface.Vec(2, ProtocolInterface.Flipped(ProtocolInterface.Bool))
      }
      assert(fv.getMessage.contains("Vec elements cannot be Flipped"))
      val fp = intercept[IllegalArgumentException] {
        ProtocolInterface.Probe(
          ProtocolInterface.Bundle(
            Vector(ProtocolInterface.Field("x", ProtocolInterface.Flipped(ProtocolInterface.Bool)))
          ),
          LayerPath(Vector("verification"))
        )
      }
      assert(fp.getMessage.contains("one-directional"))

      // A foreign builder read through another module's view is rejected at negotiation.
      val spec = Design {
        val p = generator(intEntry("P")) {
          parametersConst(0)
          val out = outward(Wid).dFn(_ => Right(8))
          out
        }
        val c = generator(intEntry("C")) {
          parameters(view => Right(view.edgeOf(p))) // p is module P's node — foreign to C's view
          val in = inward(Wid).uFn(_ => Right(8))
          in
        }
        c <-- p
      }
      val e    = intercept[IllegalArgumentException](Negotiator.negotiate(spec))
      assert(e.getMessage.contains("is not a node of EdgeView"))
    }
  }
