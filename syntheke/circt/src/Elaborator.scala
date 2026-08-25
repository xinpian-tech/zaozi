// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.syntheke.circt

import scala.collection.mutable

import me.jiuyang.syntheke.*

import org.llvm.circt.scalalib.capi.dialect.emit.given_DialectApi
import org.llvm.circt.scalalib.capi.dialect.emit.DialectApi as EmitDialectApi
import org.llvm.circt.scalalib.capi.dialect.firrtl.given_DialectApi
import org.llvm.circt.scalalib.capi.dialect.firrtl.{
  given_FirrtlBundleFieldApi,
  given_TypeApi,
  DialectApi as FirrtlDialectApi,
  FirrtlBundleField,
  FirrtlBundleFieldApi,
  FirrtlConvention,
  FirrtlLayerConvention,
  FirrtlNameKind,
  TypeApi as FirrtlTypeApi
}
import org.llvm.circt.scalalib.capi.dialect.ltl.given_DialectApi
import org.llvm.circt.scalalib.capi.dialect.ltl.DialectApi as LTLDialectApi
import org.llvm.circt.scalalib.capi.dialect.sv.given_DialectApi
import org.llvm.circt.scalalib.capi.dialect.sv.DialectApi as SvDialectApi
import org.llvm.circt.scalalib.capi.dialect.verif.given_DialectApi
import org.llvm.circt.scalalib.capi.dialect.verif.DialectApi as VerifDialectApi
import org.llvm.circt.scalalib.capi.exportfirrtl.given_ExportFirrtlApi
import org.llvm.circt.scalalib.capi.firtool.{given_FirtoolApi, given_FirtoolOptionsApi, FirtoolApi, FirtoolOptions}
import org.llvm.circt.scalalib.dialect.firrtl.operation.given
import org.llvm.circt.scalalib.dialect.firrtl.operation.{
  Circuit,
  CircuitApi,
  ConnectApi,
  InstanceApi,
  Layer as CirctLayer,
  LayerApi,
  ModuleApi,
  OpenSubfieldApi,
  RefDefineApi
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
  ContextApi,
  LocationApi,
  Module as MlirModule,
  ModuleApi as MlirModuleApi,
  Operation,
  Type as MlirType,
  Value,
  WalkEnum,
  WalkResultEnum
}
import org.llvm.mlir.scalalib.capi.pass.{given_OpPassManagerApi, given_PassManagerApi, PassManager, PassManagerApi}

import java.lang.foreign.Arena

/** Elaboration errors, reported separately from [[NegotiationError]] (doc @dec-binding-check). */
enum ElaborationError:
  /** No backend was registered for a generator entry. */
  case MissingBackend(module: ModuleId, generator: GeneratorId)

  /** A generator port is missing or structurally different from the settled ProtocolBundle. */
  case PortMismatch(module: ModuleId, endpoint: String, detail: String, loc: SourceLocation)

  /** An instantiated module has no definition after linking every dumped circuit. */
  case MissingModule(moduleName: String)

  /** The linked circuit failed MLIR verification or an internal consistency check. */
  case InvalidCircuit(detail: String)

  /** A feature the CIRCT elaborator does not support yet. */
  case Unsupported(detail: String)

  def show: String = this match
    case MissingBackend(m, g)        => s"missing backend for generator ${g.show} at ${m.show}"
    case PortMismatch(m, e, d, loc)  => s"port mismatch at ${m.show}#$e: $d (${loc.show})"
    case MissingModule(name)         => s"instantiated module '$name' has no definition"
    case InvalidCircuit(d)           => s"invalid circuit: $d"
    case Unsupported(d)              => s"unsupported: $d"

/** The enacted design: FIRRTL and Verilog text plus the module-name assignment. */
final case class ElaboratedDesign(
  circuitName: String,
  firrtl:      String,
  verilog:     String,
  moduleNames: Map[ModuleId, String])

/** The Elaborate phase, CIRCT backend (doc @ch-hardware, @sec-wrapper-emission, @sec-elaboration-flow).
  *
  * Wrapper modules are emitted directly through the CIRCT C-API from the negotiated plans: dangle ports, one
  * instance per child, bundle-level connects, and layer declarations. Generator modules are enacted by their
  * [[GeneratorBackend]] (zaozi), which dumps per-module `.mlirbc` circuits; the elaborator links those into the
  * design circuit, verifies it, and runs the firtool pipeline to Verilog — no textual FIRRTL is ever constructed
  * by hand.
  */
object Elaborator:

  def elaborate(
    resolved:  ResolvedDesign,
    backends:  Seq[GeneratorBackend],
    mlirbcDir: os.Path = os.Path(sys.env.getOrElse("ZAOZI_OUTDIR", os.pwd.toString), os.pwd)
  ): Either[Vector[ElaborationError], ElaboratedDesign] =
    val errors    = mutable.ArrayBuffer.empty[ElaborationError]
    val backendOf = backends.map(b => b.id -> b).toMap
    val spec      = resolved.spec
    val dd        = Dedup.dedup(resolved)

    // Module names: generator modules are named by their backend (stable per canonical FullParam, matching the
    // structural key); wrapper modules by the dedup naming rules.
    val moduleNames = mutable.Map.empty[ModuleId, String]
    spec.moduleOrder.foreach { id =>
      spec.modules(id) match
        case g: GeneratorModuleSpec =>
          backendOf.get(g.entry.id) match
            case Some(b) => moduleNames(id) = b.moduleName(resolved.generatorModule(id).get.fullParam)
            case None    => errors += ElaborationError.MissingBackend(id, g.entry.id)
        case _: WrapperModuleSpec   => moduleNames(id) = dd.nameOf(id)
    }
    if errors.nonEmpty then return Left(errors.toVector)

    val arena             = Arena.ofConfined()
    var context:  Context = null
    try
      given Arena   = arena
      given Context = summon[ContextApi].contextCreate
      context = summon[Context]
      summon[FirrtlDialectApi].loadDialect
      summon[LTLDialectApi].loadDialect
      summon[SvDialectApi].loadDialect
      summon[EmitDialectApi].loadDialect
      summon[VerifDialectApi].loadDialect
      summon[VerifDialectApi].registerPasses

      val unknownLoc = summon[LocationApi].locationUnknownGet

      def translate(t: ProtocolInterface): MlirType = t match
        case ProtocolInterface.Bundle(fields) =>
          fields
            .map(f => summon[FirrtlBundleFieldApi].createFirrtlBundleField(f.name, f.flip, translate(f.tpe)))
            .getBundle
        case ProtocolInterface.Vec(n, e)      => translate(e).getVector(n)
        case ProtocolInterface.UInt(w)        => w.getUInt
        case ProtocolInterface.SInt(w)        => w.getSInt
        case ProtocolInterface.Bool           => 1.getUInt
        case ProtocolInterface.Clock          => summon[FirrtlTypeApi].getClock
        case ProtocolInterface.Reset          => summon[FirrtlTypeApi].getReset
        case ProtocolInterface.Probe(i, l)    => translate(i).getRef(false, l.segments)

      def leafPaths(t: LayerTree, prefix: Vector[String] = Vector.empty): Vector[Vector[String]] =
        if t.children.isEmpty then (if prefix.isEmpty then Vector.empty else Vector(prefix))
        else t.children.toVector.sortBy(_._1).flatMap((n, sub) => leafPaths(sub, prefix :+ n))

      given MlirModule = summon[MlirModuleApi].moduleCreateEmpty(unknownLoc)
      val circuitName  = dd.nameOf(ModuleId.root)
      given Circuit    = summon[CircuitApi].op(circuitName)
      summon[Circuit].appendToModule()

      // Layer symbol definitions, once, from the union of every wrapper's layer tree.
      def emitLayers(tree: LayerTree, parent: Option[CirctLayer]): Unit =
        tree.children.toVector.sortBy(_._1).foreach { (name, sub) =>
          val op = summon[LayerApi].op(name, unknownLoc, FirrtlLayerConvention.Bind)
          parent match
            case None    => summon[Circuit].block.appendOwnedOperation(op.operation)
            case Some(p) => p.block.appendOwnedOperation(op.operation)
          emitLayers(sub, Some(op))
        }
      emitLayers(resolved.layerDecls.values.foldLeft(LayerTree.empty)(_ merge _), None)

      // ============ wrapper modules: one firrtl.module per structural key ============
      dd.definitions.foreach { d =>
        val rep = d.instances.head
        spec.wrapper(rep).foreach { w =>
          val ports      = resolved.portPlans.filter(_.module == rep)
          val portFields = ports.map { p =>
            summon[FirrtlBundleFieldApi].createFirrtlBundleField(
              p.name.encoded,
              p.direction == PortDirection.Input,
              translate(p.interface)
            )
          }
          val portIndex  = ports.zipWithIndex.map((p, i) => p.name.encoded -> i).toMap
          val module     = summon[ModuleApi].op(
            d.name,
            unknownLoc,
            FirrtlConvention.Scalarized,
            portFields.map(f => (f, unknownLoc)),
            leafPaths(resolved.layerDecls.getOrElse(rep, LayerTree.empty))
          )
          given Block = module.block

          val childValues = mutable.Map.empty[(String, String), Value]
          w.children.foreach { c =>
            val childId = rep / c
            spec.modules(childId) match
              case gm: GeneratorModuleSpec =>
                val rgm    = resolved.generatorModule(childId).get
                val instOp = backendOf(gm.entry.id).instantiate(rgm.fullParam, c, gm.loc)
                val names  = instOp.getInherentAttributeByName("portNames")
                val count  = names.arrayAttrGetNumElements
                val byName = Seq
                  .tabulate(count)(i => names.arrayAttrGetElement(i).stringAttrGetValue -> i)
                  .toMap
                byName.foreach((n, i) => childValues((c, n)) = instOp.getResult(i))
                // Binding check (@dec-binding-check): each settled node must be a generator port of the
                // exact settled interface type.
                rgm.view.nodes.foreach { nv =>
                  byName.get(nv.node.name) match
                    case None    =>
                      errors += ElaborationError.PortMismatch(
                        childId,
                        nv.node.name,
                        "declared node has no matching generator port",
                        gm.loc
                      )
                    case Some(i) =>
                      val expected = translate(nv.edge.interface)
                      val actual   = instOp.getResult(i).getType
                      if !expected.isEquivalentTo(actual, true) then
                        errors += ElaborationError.PortMismatch(
                          childId,
                          nv.node.name,
                          "generator port type differs from the settled ProtocolBundle",
                          gm.loc
                        )
                }
                // Verification endpoints are ports too: probe sources and sinks by declared name.
                (rgm.view.verification.sources.map(s => s.source.name -> s.interface) ++
                  rgm.view.verification.sinks.map(s => s.sink.name -> s.interfaces.sink)).foreach { (name, bundle) =>
                  byName.get(name) match
                    case None    =>
                      errors += ElaborationError.PortMismatch(
                        childId,
                        name,
                        "declared verification endpoint has no matching generator port",
                        gm.loc
                      )
                    case Some(i) =>
                      val expected = translate(bundle)
                      val actual   = instOp.getResult(i).getType
                      if !expected.isEquivalentTo(actual, true) then
                        errors += ElaborationError.PortMismatch(
                          childId,
                          name,
                          "verification port type differs from the settled interface",
                          gm.loc
                        )
                }
              case _:  WrapperModuleSpec   =>
                val childPorts = resolved.portPlans.filter(_.module == childId)
                val fields     = childPorts.map { p =>
                  summon[FirrtlBundleFieldApi].createFirrtlBundleField(
                    p.name.encoded,
                    p.direction == PortDirection.Input,
                    translate(p.interface)
                  )
                }
                val instOp     = summon[InstanceApi].op(
                  moduleNames(childId),
                  c,
                  FirrtlNameKind.Interesting,
                  unknownLoc,
                  fields,
                  leafPaths(resolved.layerDecls.getOrElse(childId, LayerTree.empty))
                )
                instOp.operation.appendToBlock()
                childPorts.zipWithIndex.foreach((p, i) => childValues((c, p.name.encoded)) = instOp.operation.getResult(i))
          }

          def baseOf(e: LocalEndpoint): Either[String, Value] = e match
            case LocalEndpoint.ThisPort(name)           =>
              portIndex.get(name.encoded).map(i => module.getIO(i)).toRight(s"missing port ${name.encoded}")
            case LocalEndpoint.ChildPort(inst, port, _) =>
              childValues.get((inst, port.encoded)).toRight(s"missing child port $inst.${port.encoded}")

          def subOf(e: LocalEndpoint): InterfacePath = e match
            case LocalEndpoint.ChildPort(_, _, sub) => sub
            case _                                  => InterfacePath.root

          /** Walk named fields with `firrtl.opensubfield`; verification bundles are open bundles. */
          def navigate(base: Value, path: InterfacePath): Either[String, Value] =
            path.segments.foldLeft(Right(base): Either[String, Value]) {
              case (Right(v), InterfacePath.Segment.Field(n)) =>
                val idx = v.getType.getBundleFieldIndex(n)
                if idx < 0 then Left(s"no field '$n' while navigating a verification path")
                else
                  val sub = summon[OpenSubfieldApi].op(v, idx, unknownLoc)
                  sub.operation.appendToBlock()
                  Right(sub.result)
              case (Right(_), InterfacePath.Segment.Index(i)) =>
                Left(s"Vec index [$i] in a verification path is not supported yet")
              case (l @ Left(_), _)                           => l
            }

          resolved.wirePlans.filter(_.module == rep).foreach { wp =>
            def fail(detail: String): Unit = errors += ElaborationError.InvalidCircuit(s"${d.name}: $detail")
            wp.origin match
              case PlanOrigin.Design(_)          =>
                (baseOf(wp.from), baseOf(wp.to)) match
                  case (Right(src), Right(dst)) =>
                    summon[ConnectApi].op(src, dst, unknownLoc).operation.appendToBlock()
                  case (l, r)                   => Seq(l, r).foreach(_.left.foreach(fail))
              case PlanOrigin.Verification(bind) =>
                // All-probe bundles connect with per-leaf ref.define (doc @sec-dv-routing).
                val group  = resolved.dvGroups.find(_.binds.contains(bind)).get
                val bundle = group.interfaces.sources(group.binds.indexOf(bind))
                (baseOf(wp.from), baseOf(wp.to)) match
                  case (Right(srcBase), Right(dstBase)) =>
                    ProtocolBundle.leaves(bundle).foreach { (leafPath, _) =>
                      val defined = for
                        src <- navigate(srcBase, InterfacePath(subOf(wp.from).segments ++ leafPath.segments))
                        dst <- navigate(dstBase, InterfacePath(subOf(wp.to).segments ++ leafPath.segments))
                      yield summon[RefDefineApi].op(dst, src, unknownLoc).operation.appendToBlock()
                      defined.left.foreach(fail)
                    }
                  case (l, r)                           => Seq(l, r).foreach(_.left.foreach(fail))
          }

          module.appendToCircuit()
        }
      }
      if errors.nonEmpty then return Left(NormalizedErrors(errors.toVector))

      // ============ link the per-module circuits dumped by the backends ============
      val defined = mutable.Set.empty[String]
      summon[Circuit].operation.walk(
        op =>
          if op.getName.str == "firrtl.module" || op.getName.str == "firrtl.extmodule" then
            defined += op.getInherentAttributeByName("sym_name").stringAttrGetValue
          WalkResultEnum.Advance
        ,
        WalkEnum.PreOrder
      )
      val pending = mutable.Map.empty[String, Operation]
      os.list(mlirbcDir).filter(_.ext == "mlirbc").sortBy(_.last).foreach { f =>
        val parsed = summon[MlirModuleApi].moduleCreateParse(os.read.bytes(f))
        parsed.getOperation.walk(
          op =>
            if op.getName.str == "firrtl.module" then
              val sym = op.getInherentAttributeByName("sym_name").stringAttrGetValue
              if !defined(sym) && !pending.contains(sym) then pending(sym) = op
              WalkResultEnum.Skip
            else WalkResultEnum.Advance
          ,
          WalkEnum.PreOrder
        )
      }
      pending.toVector.sortBy(_._1).foreach { (sym, op) =>
        op.removeFromParent()
        summon[Circuit].block.appendOwnedOperation(op)
        defined += sym
      }

      // Every instantiated module must now have a definition.
      val referenced = mutable.Set.empty[String]
      summon[Circuit].operation.walk(
        op =>
          if op.getName.str == "firrtl.instance" then
            referenced += op.getInherentAttributeByName("moduleName").flatSymbolRefAttrGetValue
          WalkResultEnum.Advance
        ,
        WalkEnum.PreOrder
      )
      (referenced -- defined).toVector.sorted.foreach(sym => errors += ElaborationError.MissingModule(sym))
      if errors.nonEmpty then return Left(NormalizedErrors(errors.toVector))

      if !summon[MlirModule].getOperation.verify then
        return Left(Vector(ElaborationError.InvalidCircuit("MLIR verification of the linked circuit failed")))

      // ============ artifacts: FIRRTL text, then the firtool pipeline to Verilog ============
      val fir = new StringBuilder
      summon[MlirModule].exportFIRRTL(fir ++= _)

      given FirtoolOptions = summon[FirtoolApi].firtoolOptionsCreateDefault
      given PassManager    = summon[PassManagerApi].passManagerCreate
      val verilog          = new StringBuilder
      val firtoolOptions   = summon[FirtoolOptions]
      summon[PassManager].preprocessTransforms(firtoolOptions)
      summon[PassManager].chirrtlToLowFIRRTL(firtoolOptions)
      summon[PassManager].lowFIRRTLToHW(firtoolOptions, "")
      summon[PassManager].hwToSV(firtoolOptions)
      summon[PassManager].exportVerilog(firtoolOptions, verilog ++= _)
      summon[PassManager].runOnOpOrThrow(
        summon[MlirModule].getOperation,
        s"firtool lowering pipeline for circuit '$circuitName'"
      )

      Right(ElaboratedDesign(circuitName, fir.toString, verilog.toString, moduleNames.toMap))
    finally
      if context != null then context.destroy()
      arena.close()

  private def NormalizedErrors(errors: Vector[ElaborationError]): Vector[ElaborationError] =
    errors.sortBy(_.show)
