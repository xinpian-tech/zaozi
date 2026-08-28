// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.syntheke.circt.tests

import scala.collection.mutable

import me.jiuyang.syntheke.*
import me.jiuyang.syntheke.circt.*
import me.jiuyang.syntheke.tests.{Trace, Wid}
import upickle.default.ReadWriter
import utest.*

import org.llvm.circt.scalalib.capi.dialect.firrtl.{
  given_FirrtlDirectionApi,
  given_FirrtlNameKindApi,
  given_TypeApi,
  given_ValueApi
}
import org.llvm.circt.scalalib.capi.dialect.firrtl.{
  FirrtlConvention,
  FirrtlLayerConvention,
  FirrtlNameKind,
  ValueApi as FirrtlValueApi
}
import org.llvm.mlir.scalalib.capi.support.{*, given}
import org.llvm.circt.scalalib.dialect.firrtl.operation.given
import org.llvm.circt.scalalib.dialect.firrtl.operation.{
  Circuit,
  CircuitApi,
  InstanceApi,
  Layer as CirctLayer,
  LayerApi,
  LayerBlockApi,
  ModuleApi,
  OpenSubfieldApi,
  RefCastApi,
  RefDefineApi,
  RefSendApi,
  WireApi
}
import org.llvm.mlir.scalalib.capi.ir.{
  given_AttributeApi,
  given_BlockApi,
  given_ContextApi,
  given_IdentifierApi,
  given_LocationApi,
  given_ModuleApi,
  given_NamedAttributeApi,
  given_OperationApi,
  given_RegionApi,
  given_TypeApi,
  given_ValueApi,
  Block,
  Context,
  LocationApi,
  Module as MlirModule,
  ModuleApi as MlirModuleApi,
  Operation
}

import java.lang.foreign.Arena
import java.nio.file.StandardOpenOption.*

/** Verification enactment end-to-end: probe sources in a deep cluster forward through framework-planned dangles and
  * per-leaf `ref.define` to top-level probe ports, confined to FIRRTL layers.
  *
  * The generators here use a [[StubBackend]] that builds its module through the CIRCT C-API directly: outputs
  * invalidated, probe sources defined from dummy wires. The framework side — planning, dangles, defines, layers,
  * linking, firtool — is exactly the production path.
  */

/** Serializable stub description: every port of the generator with its settled interface. */
final case class StubPort(name: String, isInput: Boolean, interface: ProtocolInterface) derives ReadWriter
final case class StubFull(kind: String, ports: Vector[StubPort]) derives ReadWriter

/** A [[GeneratorBackend]] whose module is the [[StubFull]] port list `describe` derives from the full parameter —
  * interface from parameter, like any generator — built with circtlib operations and dumped as a per-module `.mlirbc`
  * circuit exactly like the zaozi flow.
  */
final class StubBackend[FP](
  val entry: GeneratorEntry[FP],
  outDir:    os.Path,
  describe:  FP => StubFull)
    extends GeneratorBackend:
  private val dumped = mutable.Set.empty[String]

  def moduleName(fullParam: Any): String =
    GeneratorBackend.canonicalModuleName(entry, fullParam.asInstanceOf[FP])

  def instantiate(
    fullParam:    Any,
    instanceName: String,
    loc:          (sourcecode.File, sourcecode.Line)
  )(
    using Arena,
    Context,
    Block
  ): Operation =
    val p          = describe(fullParam.asInstanceOf[FP])
    val name       = moduleName(fullParam)
    val unknownLoc = summon[LocationApi].locationUnknownGet
    val fields     = p.ports.map(sp => Translate.portField(sp.name, sp.isInput, sp.interface))
    val layers     = p.ports.flatMap(sp => Translate.probeLayers(sp.interface)).distinct

    if dumped.add(name) then
      given MlirModule = summon[MlirModuleApi].moduleCreateEmpty(unknownLoc)
      given Circuit    = summon[CircuitApi].op(name)
      summon[Circuit].appendToModule()
      // The per-module circuit must define every layer the module's colored probes mention.
      def emitLayerDefs(tree: LayerTree, parent: Option[CirctLayer]): Unit =
        tree.children.toVector.sortBy(_._1).foreach { (n, sub) =>
          val op = summon[LayerApi].op(n, unknownLoc, FirrtlLayerConvention.Bind)
          parent match
            case None    => summon[Circuit].block.appendOwnedOperation(op.operation)
            case Some(p) => p.block.appendOwnedOperation(op.operation)
          emitLayerDefs(sub, Some(op))
        }
      emitLayerDefs(
        layers.foldLeft(LayerTree.empty)((t, p) => t.add(LayerPath(p))),
        None
      )
      // An internal layer unrelated to any routed probe, carried by output-only stubs (probe sources): the linker
      // must carry its definition into the design circuit, or verification fails on the layerblock below.
      val internalLayer = !p.ports.exists(_.isInput)
      if internalLayer then
        summon[Circuit].block.appendOwnedOperation(
          summon[LayerApi].op("stubinternal", unknownLoc, FirrtlLayerConvention.Bind).operation
        )
      val module = summon[ModuleApi].op(
        name,
        unknownLoc,
        FirrtlConvention.Scalarized,
        fields.map(f => (f, unknownLoc)),
        layers.map(_.toSeq)
      )
      locally {
        given Block = module.block
        if internalLayer then summon[LayerBlockApi].op(Seq("stubinternal"), unknownLoc).operation.appendToBlock()
        p.ports.zipWithIndex.foreach { (sp, idx) =>
          val port = module.getIO(idx)
          sp.interface match
            case ProtocolInterface.Probe(inner, _) if !sp.isInput =>
              // A pure-probe leaf port: an invalidated dummy wire sent into the colored port.
              val wire = summon[WireApi].op(
                s"stub_${sp.name}",
                unknownLoc,
                FirrtlNameKind.Droppable,
                Translate.tpe(inner)
              )
              wire.operation.appendToBlock()
              wire.result.emitInvalidate(summon[Block], unknownLoc)
              val send = summon[RefSendApi].op(wire.result, unknownLoc)
              send.operation.appendToBlock()
              // Color the uncolored probe to the port's layer-colored probe type.
              val cast = summon[RefCastApi].op(send.result, port.getType, unknownLoc)
              cast.operation.appendToBlock()
              summon[RefDefineApi].op(port, cast.result, unknownLoc).operation.appendToBlock()
            case _                                                =>
              // Data ports: invalidate every writable leaf in one shot.
              port.emitInvalidate(summon[Block], unknownLoc)
        }
      }
      module.appendToCircuit()
      val file   = outDir / s"$name.mlirbc"
      val out    = os.write.outputStream(file, openOptions = Seq(WRITE, CREATE, TRUNCATE_EXISTING))
      try summon[MlirModule].getOperation.writeBytecode(bc => out.write(bc))
      finally out.close()

    val instOp =
      summon[InstanceApi].op(name, instanceName, FirrtlNameKind.Interesting, unknownLoc, fields, layers.map(_.toSeq))
    instOp.operation.appendToBlock()
    instOp.operation

object DvVerilogSpec extends TestSuite:

  val outDir = os.Path(sys.env.getOrElse("ZAOZI_OUTDIR", os.pwd.toString), os.pwd)

  def entry(name: String): GeneratorEntry[StubFull] =
    given sourcecode.Name = sourcecode.Name(name)
    new GeneratorEntry[StubFull]

  val srcEntry  = entry("Src")
  val memEntry  = entry("Mem")
  val vsrcEntry = entry("VSrc")
  val psrcEntry = entry("PSrc")
  val tbEntry   = entry("Tb")
  val ptbEntry  = entry("PTb")
  val backends: Seq[GeneratorBackend] =
    Seq(srcEntry, memEntry, vsrcEntry, psrcEntry, tbEntry, ptbEntry).map(StubBackend(_, outDir, identity))

  val layerCosim = LayerPath(Vector("verification", "cosim"))

  /** Like [[Trace]], but each source publishes a Vec of probes: `pc: Vec(2, Probe(UInt(w)))`. */
  object VecTrace extends DVProtocol:
    type Down = Int
    def interfaceOf(down: Int, layer: LayerPath): ProtocolBundle                  =
      ProtocolBundle(
        ProtocolInterface
          .Field("pc", ProtocolInterface.Vec(2, ProtocolInterface.Probe(ProtocolInterface.UInt(down), layer)))
      )
    val downRW:                                   upickle.default.ReadWriter[Int] = summon

  /** An aggregate probe: the whole record behind one reference — one interface leaf, one port. */
  object PackTrace extends DVProtocol:
    type Down = Int
    def interfaceOf(down: Int, layer: LayerPath): ProtocolBundle                  =
      ProtocolBundle(
        ProtocolInterface.Field(
          "pack",
          ProtocolInterface.Probe(
            ProtocolInterface.Bundle(
              Vector(
                ProtocolInterface.Field("pc", ProtocolInterface.UInt(down)),
                ProtocolInterface.Field("valid", ProtocolInterface.Bool)
              )
            ),
            layer
          )
        )
      )
    val downRW:                                   upickle.default.ReadWriter[Int] = summon

  /** Design ports of a generator module reconstructed from its EdgeView, plus its probe ports: one pure-probe port per
    * signal leaf, named source name + leaf path — the same information the module declared with `dvSource`.
    */
  def stubParams(kind: String, probes: Vector[StubPort] = Vector.empty)(view: EdgeView): Either[Violation, StubFull] =
    Right(
      StubFull(
        kind,
        view.nodes.map(nv => StubPort(nv.node.name, nv.direction == NodeDirection.Inward, nv.edge.interface))
          ++ probes
      )
    )

  def probePorts(p: DVProtocol)(name: String, down: p.Down, layer: LayerPath): Vector[StubPort] =
    ProtocolBundle.leaves(p.interfaceOf(down, layer)).map { (path, leaf) =>
      StubPort(PortName(name +: path.nameSegments).encoded, false, leaf)
    }

  /** The shared source cluster, spliced into a wrapper body (no `sourcecode.Name` here — a Name-carrying def may
    * contain only a single wrapper/generator call, or it would capture the inner declarations too).
    */
  private def srcCluster(
    using WrapperScope
  ) =
    val src = generator(srcEntry) {
      val mem = outward(Wid).dFn(_ => Right(32))
      val rob = dvSource(Trace)(8, layerCosim)
      val lsu = dvSource(Trace)(4, layerCosim)
      parameters(
        stubParams(
          "Src",
          probePorts(Trace)("rob", 8, layerCosim) ++ probePorts(Trace)("lsu", 4, layerCosim)
        )
      )
      mem
    }
    src

  /** Without a testbench: an in-graph model terminates the design, probes surface as top-level probe ports. */
  def buildDesign(): DesignSpec =
    Design {
      val cluster = wrapper(srcCluster)
      val mem     = generator(memEntry) {
        parameters(stubParams("Mem"))
        val in = inward(Wid).uFn(_ => Right(64))
        in
      }
      mem <-- cluster
    }

  /** With a testbench: it terminates the design through a standard bind and takes every probe leaf as a data input,
    * shaped from the manifest its view carries.
    */
  def buildTbDesign(): DesignSpec =
    Design {
      val cluster = wrapper(srcCluster)
      val tb      = testbench(tbEntry) {
        val mem = inward(Wid).uFn(_ => Right(64))
        parameters(view =>
          stubParams("Tb", view.probes.flatMap(_.leaves).map(l => StubPort(l.portName, true, l.tpe)))(view)
        )
        mem
      }
      tb <-- cluster
    }

  val tests = Tests {

    test("probe forwarding elaborates: dangles, per-leaf defines, top-level probe ports, Verilog") {
      val resolved = Negotiator.negotiate(buildDesign())
      val design   = Elaborator.elaborate(resolved, backends)

      // The layer tree is declared once at circuit level; the cluster carries one pure-probe dangle per leaf and the
      // root exposes the same probes as top-level ports.
      assert(design.firrtl.contains("layer verification"))
      assert(design.firrtl.contains("inst_src_dv$msource_rob_sig_out"))
      assert(design.firrtl.contains("inst_cluster_inst_src_dv$msource_rob_sig_out"))
      assert(design.firrtl.contains("define"))
      // Verilog exists for the root and both stub modules; probes stay out of the release netlist (bind layers).
      assert(design.verilog.contains("module Top"))
      assert(design.verilog.contains("module Src_"))
      // The stub's internal layer, unrelated to any routed probe, was carried by the linker into the design circuit.
      assert(design.firrtl.contains("layer stubinternal"))
    }

    test("the testbench terminates the design and takes the probes as data inputs") {
      val resolved = Negotiator.negotiate(buildTbDesign())
      val design   = Elaborator.elaborate(resolved, backends)

      // The testbench is an ordinary generator instance at the root; its design edge settled like any edge and every
      // probe leaf is resolved into its matching data input. Nothing surfaces as a root port.
      assert(design.firrtl.contains("inst tb of Tb_"))
      assert(!design.firrtl.contains("output inst_cluster"))
      assert(!design.firrtl.contains("define inst_cluster"))
      assert(design.verilog.contains("module Top"))
    }

    test("an aggregate probe is one interface leaf: a single reference port end to end") {
      val inner    = ProtocolInterface.Bundle(
        Vector(
          ProtocolInterface.Field("pc", ProtocolInterface.UInt(16)),
          ProtocolInterface.Field("valid", ProtocolInterface.Bool)
        )
      )
      val spec     = Design {
        val box = wrapper {
          val psrc = generator(psrcEntry) {
            parameters(stubParams("PSrc", probePorts(PackTrace)("tr", 16, layerCosim)))
            val tr = dvSource(PackTrace)(16, layerCosim)
          }
        }
        val tb  = testbench(ptbEntry) {
          parameters(view =>
            stubParams("PTb", view.probes.flatMap(_.leaves).map(l => StubPort(l.portName, true, l.tpe)))(view)
          )
        }
      }
      val resolved = Negotiator.negotiate(spec)

      // One leaf whose data type is the whole aggregate; one pure-probe dangle carrying a single reference.
      assert(
        resolved.probes.flatMap(_.leaves).map(l => (l.portName, l.tpe)) ==
          Vector("inst_box_inst_psrc_dv$msource_tr_pack_out" -> inner)
      )
      val box = ModuleId.root / "box"
      assert(
        resolved.portPlans.filter(_.module == box).map(_.name.encoded) ==
          Vector("inst_psrc_dv$msource_tr_pack_out")
      )
      assert(
        resolved.portPlans.filter(_.module == box).forall(_.interface == ProtocolInterface.Probe(inner, layerCosim))
      )

      // Through the C-API and firtool: the source defines a reference to a bundle, the testbench takes the bundle.
      val design = Elaborator.elaborate(resolved, backends)
      assert(design.verilog.contains("module Top"))
    }

    test("Vec probe leaves forward as individual pure-probe ports") {
      val spec     = Design {
        val vc = wrapper {
          val vsrc = generator(vsrcEntry) {
            parameters(stubParams("VSrc", probePorts(VecTrace)("pcs", 32, layerCosim)))
            val pcs = dvSource(VecTrace)(32, layerCosim)
          }
        }
      }
      val resolved = Negotiator.negotiate(spec)
      val vc       = ModuleId.root / "vc"
      // One dangle per Vec element on the intermediate wrapper and again on the root, pure probe type.
      assert(
        resolved.portPlans.filter(_.module == vc).map(_.name.encoded) ==
          Vector("inst_vsrc_dv$msource_pcs_pc_0_out", "inst_vsrc_dv$msource_pcs_pc_1_out")
      )
      assert(
        resolved.portPlans.filter(_.module == ModuleId.root).map(_.name.encoded) ==
          Vector("inst_vc_inst_vsrc_dv$msource_pcs_pc_0_out", "inst_vc_inst_vsrc_dv$msource_pcs_pc_1_out")
      )
      assert(resolved.portPlans.forall(_.interface.isInstanceOf[ProtocolInterface.Probe]))
      val design   = Elaborator.elaborate(resolved, backends)
      assert(design.firrtl.contains("pcs_pc_0"))
      assert(design.verilog.contains("module Top"))
    }
  }
