// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.syntheke.circt.tests

import scala.collection.mutable

import me.jiuyang.syntheke.*
import me.jiuyang.syntheke.circt.*
import me.jiuyang.syntheke.tests.{Trace, Wid}
import upickle.default.ReadWriter
import utest.*

import org.llvm.circt.scalalib.capi.dialect.firrtl.given_ValueApi
import org.llvm.circt.scalalib.capi.dialect.firrtl.{FirrtlConvention, FirrtlNameKind, ValueApi as FirrtlValueApi}
import org.llvm.circt.scalalib.dialect.firrtl.operation.given
import org.llvm.circt.scalalib.dialect.firrtl.operation.{
  Circuit,
  CircuitApi,
  InstanceApi,
  ModuleApi,
  OpenSubfieldApi,
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

/** Verification enactment end-to-end: probe sources in a deep cluster route through framework-planned dangles and
  * per-leaf `ref.define` to an ancestor sink, confined to FIRRTL layers.
  *
  * zaozi's probe interfaces are output-only today, so both endpoint generators here use a [[StubBackend]] that
  * builds its module through the CIRCT C-API directly: outputs invalidated, probe sources defined from dummy wires,
  * probe sink inputs left unread. The framework side — planning, dangles, defines, layers, linking, firtool — is
  * exactly the production path.
  */

/** Serializable stub description: every port of the generator with its settled interface. */
final case class StubPort(name: String, isInput: Boolean, interface: ProtocolInterface) derives ReadWriter
final case class StubFull(kind: String, ports: Vector[StubPort]) derives ReadWriter

/** A [[GeneratorBackend]] that enacts a [[StubFull]] by building the module with circtlib operations, dumping it
  * as a per-module `.mlirbc` circuit exactly like the zaozi flow.
  */
final class StubBackend(val entry: GeneratorEntry[StubFull], outDir: os.Path) extends GeneratorBackend:
  def id: GeneratorId = entry.id
  private val dumped = mutable.Set.empty[String]

  def moduleName(fullParam: Any): String =
    val p = fullParam.asInstanceOf[StubFull]
    s"${p.kind}_${Integer.toHexString(p.hashCode)}"

  def instantiate(
    fullParam:    Any,
    instanceName: String,
    loc:          SourceLocation
  )(using Arena, Context, Block): Operation =
    val p          = fullParam.asInstanceOf[StubFull]
    val name       = moduleName(fullParam)
    val unknownLoc = summon[LocationApi].locationUnknownGet
    val fields     = p.ports.map(sp => Translate.portField(sp.name, sp.isInput, sp.interface))
    val layers     = p.ports.flatMap(sp => Translate.probeLayers(sp.interface)).distinct

    if dumped.add(name) then
      given MlirModule = summon[MlirModuleApi].moduleCreateEmpty(unknownLoc)
      given Circuit    = summon[CircuitApi].op(name)
      summon[Circuit].appendToModule()
      val module       = summon[ModuleApi].op(
        name,
        unknownLoc,
        FirrtlConvention.Scalarized,
        fields.map(f => (f, unknownLoc)),
        layers.map(_.toSeq)
      )
      locally {
        given Block = module.block
        p.ports.zipWithIndex.foreach { (sp, idx) =>
          val port = module.getIO(idx)
          ProtocolBundle.leaves(sp.interface).foreach { (path, leaf) =>
            leaf match
              case ProtocolInterface.Probe(inner, _) if !sp.isInput =>
                // A probe source: an invalidated dummy wire sent into the colored port leaf.
                val wire = summon[WireApi].op(
                  s"stub_${sp.name}_${path.segments.size}_${path.show.hashCode.toHexString}",
                  unknownLoc,
                  FirrtlNameKind.Droppable,
                  Translate.tpe(inner)
                )
                wire.operation.appendToBlock()
                wire.result.emitInvalidate(summon[Block], unknownLoc)
                val send = summon[RefSendApi].op(wire.result, unknownLoc)
                send.operation.appendToBlock()
                val dst  = path.segments.foldLeft(port) { (v, seg) =>
                  seg match
                    case InterfacePath.Segment.Field(n) =>
                      val sub = summon[OpenSubfieldApi].op(v, v.getType.getBundleFieldIndex(n), unknownLoc)
                      sub.operation.appendToBlock()
                      sub.result
                    case InterfacePath.Segment.Index(_) => throw new UnsupportedOperationException("vec probes")
                }
                summon[RefDefineApi].op(dst, send.result, unknownLoc).operation.appendToBlock()
              case _                                                => ()
          }
          // Non-probe ports: invalidate every writable leaf in one shot.
          if !hasProbe(sp) then port.emitInvalidate(summon[Block], unknownLoc)
        }
      }
      module.appendToCircuit()
      val file = outDir / s"$name.mlirbc"
      val out  = os.write.outputStream(file, openOptions = Seq(WRITE, CREATE, TRUNCATE_EXISTING))
      try summon[MlirModule].getOperation.writeBytecode(bc => out.write(bc))
      finally out.close()

    val instOp = summon[InstanceApi].op(name, instanceName, FirrtlNameKind.Interesting, unknownLoc, fields, layers.map(_.toSeq))
    instOp.operation.appendToBlock()
    instOp.operation

  private def hasProbe(sp: StubPort): Boolean =
    ProtocolBundle.leaves(sp.interface).exists(_._2.isInstanceOf[ProtocolInterface.Probe])

object DvVerilogSpec extends TestSuite:

  val outDir = os.Path(sys.env.getOrElse("ZAOZI_OUTDIR", os.pwd.toString), os.pwd)

  def entry(name: String) =
    new GeneratorEntry[StubFull](GeneratorId(s"demo.dv.$name", "1"), Codec.fromReadWriter[StubFull](ujson.Str(name)))

  val srcEntry  = entry("Src")
  val memEntry  = entry("Mem")
  val snkEntry  = entry("Cosim")
  val backends: Seq[GeneratorBackend] =
    Seq(StubBackend(srcEntry, outDir), StubBackend(memEntry, outDir), StubBackend(snkEntry, outDir))

  val layerCosim = LayerPath(Vector("verification", "cosim"))

  /** Ports of a generator module reconstructed from its EdgeView — the FullParam determines the interface. */
  def stubParams(kind: String)(view: EdgeView): Either[CapabilityViolation, StubFull] =
    Right(
      StubFull(
        kind,
        view.nodes.map(nv =>
          StubPort(nv.node.name, nv.direction == NodeDirection.Inward, nv.edge.interface)
        ) ++ view.verification.sources.map(s => StubPort(s.source.name, false, s.interface))
          ++ view.verification.sinks.map(s => StubPort(s.sink.name, true, s.interfaces.sink))
      )
    )

  def buildDesign(): DesignSpec =
    var srcOut: OutwardNodeBuilder[Wid.type] = null
    var memIn:  InwardNodeBuilder[Wid.type]  = null
    var rob:    DVSourceRef[Trace.type]      = null
    var lsu:    DVSourceRef[Trace.type]      = null
    var taps:   DVSinkRef[Trace.type]        = null
    Design {
      wrapper("cluster") {
        generator("src", srcEntry) {
          srcOut = outward(Wid)("mem").dFn(_ => Right(32))
          rob = dvSource(Trace)("rob", 8, layerCosim)
          lsu = dvSource(Trace)("lsu", 4, layerCosim)
          parameters(stubParams("Src"))(identity)
        }
      }
      generator("mem", memEntry) {
        memIn = inward(Wid)("in").uFn(_ => Right(64))
        parameters(stubParams("Mem"))(identity)
      }
      generator("cosim", snkEntry) {
        taps = dvSink(Trace)("taps")
        parameters(stubParams("Cosim"))(identity)
      }
      memIn <-- srcOut
      taps <-- rob
      taps <-- lsu
    }

  val tests = Tests {

    test("probe routing elaborates: dangles, per-leaf defines, layers, Verilog") {
      val resolved = Negotiator.negotiate(buildDesign()).toOption.get
      val design   = Elaborator.elaborate(resolved, backends) match
        case Right(d)   => d
        case Left(errs) => throw new AssertionError(errs.map(_.show).mkString("\n"))

      // The layer tree is declared once at circuit level; the cluster carries probe dangle ports.
      assert(design.firrtl.contains("layer verification"))
      assert(design.firrtl.contains("cosim"))
      assert(design.firrtl.contains("inst_src_dv$msource_rob_out"))
      assert(design.firrtl.contains("define"))
      // Verilog exists for the root and both stub endpoint modules; probes stay out of the release netlist
      // (bind layers), so the sink instance is not in module Top's body.
      assert(design.verilog.contains("module Top"))
      assert(design.verilog.contains("module Src_"))
    }
  }
