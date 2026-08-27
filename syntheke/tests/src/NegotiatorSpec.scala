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

/** A protocol as a class, so tests can hold two distinct instances: their nodes must not bind — protocol identity is
  * the object, enforced at compile time by the singleton-typed builders and the invariant type parameter.
  */
final class WidLike extends Protocol:
  type Down = Int
  type Up = Int
  type Edge = Int
  def negotiate(down: Int, up: Int): Either[Violation, Int]          = Right(down min up)
  def interfaceOf(edge: Int):        ProtocolBundle                  =
    ProtocolBundle(ProtocolInterface.Field("data", ProtocolInterface.UInt(edge)))
  val downRW:                        upickle.default.ReadWriter[Int] = summon
  val upRW:                          upickle.default.ReadWriter[Int] = summon
  val edgeRW:                        upickle.default.ReadWriter[Int] = summon

/** A toy probe protocol: each source publishes one probed UInt of the declared width. */
object Trace extends DVProtocol:
  type Down = Int
  def interfaceOf(down: Int, layer: LayerPath): ProtocolBundle                  =
    ProtocolBundle(ProtocolInterface.Field("sig", ProtocolInterface.Probe(ProtocolInterface.UInt(down), layer)))
  val downRW:                                   upickle.default.ReadWriter[Int] = summon

object NegotiatorSpec extends TestSuite:

  def intEntry(name: String) = new GeneratorEntry[Int](s"test.$name")

  /** prod(32) and dma(24) feed a 2x2 xbar; c0 (capacity 64) lives one wrapper deep, c1's capacity is a knob. */
  def buildSoc(c1Capacity: Int): DesignSpec =
    Design {
      val prod                       = generator(intEntry("Prod")) {
        parameters(_ => Right(0))
        val out = outward(Wid).dFn(_ => Right(32))
        out
      }
      val dma                        = generator(intEntry("Dma")) {
        parameters(_ => Right(0))
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
          parameters(_ => Right(0))
          val in = inward(Wid).uFn(_ => Right(64))
          in
        }
        c0
      }
      val c1                         = generator(intEntry("C1")) {
        parameters(_ => Right(0))
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
          parameters(_ => Right(0))
          val out = outward(Wid).dFn(_ => Left(Violation("no width available")))
          out
        }
        val sink = generator(intEntry("Sink")) {
          parameters(_ => Right(0))
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

    test("a read outside the declared dependencies fails at evaluation, naming the node") {
      // Readers are plain values, so a closure can capture one granted to a sibling; the read context of the
      // evaluating function holds only its own declared dependencies and rejects the smuggled reader.
      val spec = Design {
        val g                 = generator(intEntry("Sneak")) {
          parameters(_ => Right(0))
          val in      = inward(Wid).uFn(_ => Right(64))
          val out0    = outward(Wid)
          val out1    = outward(Wid)
          val (d0, _) = depend(in, out0)
          out0.dFn(ctx => Right(ctx(d0)))
          out1.dFn(ctx => Right(ctx(d0))) // d0 was granted for out0; out1 declared no dependency on in
          (in, out0, out1)
        }
        val (gin, out0, out1) = g
        val src               = generator(intEntry("SneakSrc")) {
          parameters(_ => Right(0))
          val out = outward(Wid).dFn(_ => Right(8))
          out
        }
        val c0                = generator(intEntry("SneakC0")) {
          parameters(_ => Right(0))
          val in = inward(Wid).uFn(_ => Right(64))
          in
        }
        val c1                = generator(intEntry("SneakC1")) {
          parameters(_ => Right(0))
          val in = inward(Wid).uFn(_ => Right(64))
          in
        }
        gin <-- src
        c0 <-- out0
        c1 <-- out1
      }
      val e    = intercept[UndeclaredReadException](Negotiator.negotiate(spec))
      assert(e.getMessage.contains("g#in"))
      assert(e.getMessage.contains("not a declared dependency"))
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
        parameters(_ => Right(0))
      def loopback(
      )(
        using
        ws:   WrapperScope,
        name: sourcecode.Name,
        file: sourcecode.File,
        line: sourcecode.Line
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
          parameters(_ => Right(0))
          val out = outward(Wid).dFn(_ => Right(8))
          out
        }
        val c          = generator(intEntry("C")) {
          parameters(_ => Right(0))
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

    test("probe sources forward automatically to top-level probe ports") {
      val layerCosim = LayerPath(Vector("verification", "cosim"))
      val spec       = Design {
        val cluster = wrapper {
          val core = generator(intEntry("Core")) {
            parameters(_ => Right(0))
            val mem = outward(Wid).dFn(_ => Right(32))
            val rob = dvSource(Trace)(8, layerCosim)
            val lsu = dvSource(Trace)(4, layerCosim)
            mem
          }
          core
        }
        val mem     = generator(intEntry("Mem")) {
          parameters(_ => Right(0))
          val in = inward(Wid).uFn(_ => Right(64))
          in
        }
        mem <-- cluster
      }
      val resolved   = Negotiator.negotiate(spec)

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

      // The root carries the same probes one level up: its dangles are the design's top-level probe ports.
      val rootProbes = resolved.portPlans.filter(_.module == ModuleId.root)
      assert(
        rootProbes.map(_.name.encoded) == Vector(
          "inst_cluster_inst_core_dv$msource_rob_sig_out",
          "inst_cluster_inst_core_dv$msource_lsu_sig_out"
        )
      )
      assert(rootProbes.forall(_.direction == PortDirection.Output))

      // Root wires define the top-level ports from the cluster's dangles.
      val rootDvWires =
        resolved.wirePlans.filter(w => w.module == ModuleId.root && w.origin.isInstanceOf[PlanOrigin.Verification])
      assert(rootDvWires.map(_.to) == rootProbes.map(p => LocalEndpoint.ThisPort(p.name)))
      assert(
        rootDvWires.map(_.from) == probes.map(p => LocalEndpoint.ChildPort("cluster", p.name))
      )
    }

    test("the testbench is a generator module: standard binds, probes wired into it") {
      val spec     = Design {
        val cluster = wrapper {
          val core = generator(intEntry("TbCore")) {
            parameters(_ => Right(0))
            val mem = outward(Wid).dFn(_ => Right(32))
            val rob = dvSource(Trace)(8, LayerPath(Vector("verification")))
            mem
          }
          core
        }
        val tb      = testbench(intEntry("Tb")) {
          // The manifest arrives through the view after the spec froze — its FullParam counts the probe leaves.
          parameters(view => Right(view.probes.flatMap(_.leaves).size))
          val mem  = inward(Wid).uFn(_ => Right(64))
          val late = inward(Wid).uFn(_ => Right(64))
          (mem, late)
        }
        tb._1 <-- cluster
        // A probe-bearing module declared after the testbench: order does not matter for the manifest.
        val tail    = generator(intEntry("Tail")) {
          parameters(_ => Right(0))
          val out = outward(Wid).dFn(_ => Right(8))
          val sig = dvSource(Trace)(4, LayerPath(Vector("verification")))
          out
        }
        tb._2 <-- tail
      }
      assert(spec.testbench == Some(ModuleId.root / "tb"))
      val resolved = Negotiator.negotiate(spec)

      // The design edges into the testbench settled like any edge; nothing surfaces as a root port.
      assert(resolved.edges.head.edgeAs(Wid) == 32)
      assert(resolved.portPlans.filter(_.module == ModuleId.root).isEmpty)

      // The testbench's view carried the complete manifest — including the source declared after it.
      assert(resolved.generatorModule(ModuleId.root / "tb").get.fullParam == 2)
      assert(
        resolved.probes.flatMap(_.leaves.map(_.portName)) == Vector(
          "inst_cluster_inst_core_dv$msource_rob_sig_out",
          "inst_tail_dv$msource_sig_sig_out"
        )
      )
      // Other modules receive no probes: their views are empty.
      assert(resolved.generatorModule(ModuleId.root / "tail").get.view.probes.isEmpty)

      // Each probe chain ends in a wire into the testbench's matching input.
      val dvWires =
        resolved.wirePlans.filter(w => w.module == ModuleId.root && w.origin.isInstanceOf[PlanOrigin.Verification])
      assert(
        dvWires.map(_.to) == Vector(
          LocalEndpoint.ChildPort("tb", PortName("inst", "cluster", "inst", "core", "dv-source", "rob", "sig", "out")),
          LocalEndpoint.ChildPort("tb", PortName("inst", "tail", "dv-source", "sig", "sig", "out"))
        )
      )

      // Top level only, at most one.
      val nested = intercept[IllegalArgumentException] {
        Design {
          val sub = wrapper {
            val t = testbench(intEntry("T2")) { parameters(_ => Right(0)) }
          }
        }
      }
      assert(nested.getMessage.contains("the testbench lives on the top level"))
      val twice  = intercept[IllegalArgumentException] {
        Design {
          val a = testbench(intEntry("T3")) { parameters(_ => Right(0)) }
          val b = testbench(intEntry("T4")) { parameters(_ => Right(0)) }
        }
      }
      assert(twice.getMessage.contains("already declared"))
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

    test("declaration-site contracts reject duplicates on the spot") {
      val dup = intercept[IllegalArgumentException] {
        Design {
          generator(intEntry("G")) {
            parameters(_ => Right(0))
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
            parameters(_ => Right(0))
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
            parameters(_ => Right(0))
            given sourcecode.Name = sourcecode.Name("sub")
            wrapper {}
          }
        }
      }
      assert(nested.getMessage.contains("declared inside generator body"))

      // The body's return value is the only escape channel and it is typed: only endpoint containers leave.
      compileError("""Design { val x = generator(intEntry("G")) { parameters(_ => Right(0)); 42 } }""")

      // Protocol identity is the object: nodes of two distinct instances of one protocol class must not bind.
      // This holds only while the builders stay singleton-typed and NodeBuilder stays invariant in its protocol.
      compileError("""Design {
        val p1 = new WidLike
        val p2 = new WidLike
        val g1 = generator(intEntry("W1")) { parameters(_ => Right(0)); val out = outward(p1).dFn(_ => Right(1)); out }
        val g2 = generator(intEntry("W2")) { parameters(_ => Right(0)); val in = inward(p2).uFn(_ => Right(1)); in }
        g2 <-- g1
      }""")

      // A duplicate reference name on one node is rejected at the declaration, like every other name.
      val dupRef = intercept[IllegalArgumentException] {
        Design {
          generator(intEntry("R2")) {
            parameters(_ => Right(0))
            val a   = inward(Wid).uFn(_ => Right(1))
            val b   = inward(Wid).uFn(_ => Right(1))
            val out = outward(Wid).dFn(_ => Right(1))
            val r1  = out.ref(a)
            val r2  = locally {
              given sourcecode.Name = sourcecode.Name("r1")
              out.ref(b)
            }
            out
          }
        }
      }
      assert(dupRef.getMessage.contains("duplicate cross-protocol reference 'r1'"))

      // The two body-completion mandates are enforced when the module closes.
      val noFn     = intercept[IllegalStateException] {
        Design {
          val g = generator(intEntry("NF")) {
            parameters(_ => Right(0))
            val in = inward(Wid)
          }
        }
      }
      assert(noFn.getMessage.contains("uFn is mandatory but was never set"))
      val noParams = intercept[IllegalStateException] {
        Design {
          val g = generator(intEntry("NP")) {
            val out = outward(Wid).dFn(_ => Right(1))
            out
          }
        }
      }
      assert(noParams.getMessage.contains("parameters(...) is mandatory but was never set"))

      // A closure capturing the scope (here: a dFn running at negotiation) cannot declare into a frozen module.
      val late = Design {
        val g = generator(intEntry("G")) {
          parameters(_ => Right(0))
          val out = outward(Wid).dFn { _ =>
            inward(Wid)
            Right(1)
          }
          out
        }
        val c = generator(intEntry("C")) {
          parameters(_ => Right(0))
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
          parameters(_ => Right(0))
          val o = outward(Wid).dFn(_ => Right(16))
          o
        }
        val snk          = generator(intEntry("Snk")) {
          parameters(_ => Right(0))
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
            parameters(_ => Right(0))
            val out = outward(Wid).dFn(_ => Right(32))
            val rob = dvSource(Trace)(8, LayerPath(Vector("verification")))
            out
          }
          prod
        }
        val cons    = generator(intEntry("C")) {
          parameters(_ => Right(0))
          val in = inward(Wid).uFn(_ => Right(64))
          in
        }
        cons <-- cluster
      }
      val prodId = ModuleId.root / "cluster" / "prod"
      assert(spec.generatorModule(prodId).get.node("out").nonEmpty)
      assert(spec.generatorModule(prodId).get.dvSources.map(_.name) == Vector("rob"))
      assert(spec.generatorModule(ModuleId.root / "cons").get.node("in").nonEmpty)
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
      val pp = intercept[IllegalArgumentException] {
        ProtocolInterface.Probe(
          ProtocolInterface.Probe(ProtocolInterface.Bool, LayerPath(Vector("verification"))),
          LayerPath(Vector("verification"))
        )
      }
      assert(pp.getMessage.contains("no Probe inside a Probe"))

      // Declaration fidelity: UInt(1) and Bool are the same hardware type but distinct declarations, told apart by
      // their type tags; the single canonical JSON encoding roundtrips.
      assert(ProtocolInterface.UInt(1) != ProtocolInterface.Bool)
      val sample: ProtocolInterface = ProtocolBundle(
        ProtocolInterface.Field("a", ProtocolInterface.Vec(2, ProtocolInterface.UInt(1))),
        ProtocolInterface.Field("b", ProtocolInterface.Flipped(ProtocolInterface.Bool)),
        ProtocolInterface.Field(
          "p",
          ProtocolInterface.Probe(ProtocolInterface.UInt(8), LayerPath(Vector("verification")))
        )
      )
      assert(upickle.default.read[ProtocolInterface](upickle.default.write(sample)) == sample)

      // A verification interface whose leaves are not probes is rejected at the dvSource declaration.
      object BadTrace extends DVProtocol:
        type Down = Int
        def interfaceOf(down: Int, layer: LayerPath): ProtocolBundle                  =
          ProtocolBundle(ProtocolInterface.Field("sig", ProtocolInterface.UInt(down)))
        val downRW:                                   upickle.default.ReadWriter[Int] = summon
      val bare = intercept[IllegalArgumentException] {
        Design {
          val b = generator(intEntry("B")) {
            parameters(_ => Right(0))
            val t = dvSource(BadTrace)(8, LayerPath(Vector("verification")))
          }
        }
      }
      assert(bare.getMessage.contains("must be a Probe"))

      // A foreign builder read through another module's view is rejected at negotiation.
      val spec = Design {
        val p = generator(intEntry("P")) {
          parameters(_ => Right(0))
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
