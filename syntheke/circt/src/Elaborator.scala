// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.syntheke.circt

import scala.collection.mutable

import me.jiuyang.syntheke.*
import me.jiuyang.zaozi.default.runOnOpOrThrow

import org.llvm.circt.scalalib.capi.dialect.emit.given_DialectApi
import org.llvm.circt.scalalib.capi.dialect.emit.DialectApi as EmitDialectApi
import org.llvm.circt.scalalib.capi.dialect.firrtl.given_DialectApi
import org.llvm.circt.scalalib.capi.dialect.firrtl.{
  given_FirrtlBundleFieldApi,
  given_FirrtlDirectionApi,
  given_FirrtlNameKindApi,
  given_TypeApi,
  DialectApi as FirrtlDialectApi,
  FirrtlBundleField,
  FirrtlBundleFieldApi,
  FirrtlConvention,
  FirrtlLayerConvention,
  FirrtlNameKind,
  TypeApi as FirrtlTypeApi
}
import org.llvm.mlir.scalalib.capi.support.{*, given}
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
  RefDefineApi,
  RefResolveApi
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

/** Thrown by [[Elaborator.elaborate]] at the first error found. The message carries the stable identifiers, the
  * relevant source locations, and for binding-check failures the first path where the structures diverge (doc @dec-binding-check).
  */
final class ElaborationException(message: String) extends RuntimeException(message)

/** The enacted design: FIRRTL and Verilog text plus the module-name assignment. */
final case class ElaboratedDesign(
  circuitName: String,
  firrtl:      String,
  verilog:     String,
  moduleNames: Map[ModuleId, String])

/** The Elaborate phase, CIRCT backend (doc @ch-hardware, @sec-wrapper-emission, @sec-elaboration-flow).
  *
  * Wrapper modules are emitted directly through the CIRCT C-API from the negotiated plans: dangle ports, one instance
  * per child, bundle-level connects, and layer declarations. Generator modules are enacted by their
  * [[GeneratorBackend]] (zaozi), which dumps per-module `.mlirbc` circuits; the elaborator links those into the design
  * circuit, verifies it, and runs the firtool pipeline to Verilog — no textual FIRRTL is ever constructed by hand. Fail
  * fast: the first error throws an [[ElaborationException]] on the spot.
  */
object Elaborator:

  private def fail(message: String): Nothing = throw ElaborationException(message)

  def elaborate(
    resolved:  ResolvedDesign,
    backends:  Seq[GeneratorBackend],
    mlirbcDir: os.Path = os.Path(sys.env.getOrElse("ZAOZI_OUTDIR", os.pwd.toString), os.pwd)
  ): ElaboratedDesign =
    val backendOf: Map[GeneratorEntry[?], GeneratorBackend] = backends.map(b => (b.entry: GeneratorEntry[?]) -> b).toMap
    val spec = resolved.spec

    // Module names: generator modules are named by their backend (the canonical linking key); wrapper modules by the
    // reversible encoding of their instance path, "Top" for the root. Structural merging of identical modules is
    // firtool's job (its Dedup pass), not syntheke's.
    val moduleNames: Map[ModuleId, String] = spec.moduleOrder.map { id =>
      id -> (spec.modules(id) match
        case g: GeneratorModuleSpec =>
          backendOf
            .get(g.entry)
            .fold(fail(s"missing backend for generator ${g.entry.name} at ${id.show}"))(b =>
              b.moduleName(resolved.generatorModule(id).get.fullParam)
            )
        case _: WrapperModuleSpec   => if id.path.isEmpty then "Top" else PortName(id.path).encoded)
    }.toMap

    val arena = Arena.ofConfined()
    try
      given Arena   = arena
      given Context = summon[ContextApi].contextCreate
      try
        summon[FirrtlDialectApi].loadDialect
        summon[LTLDialectApi].loadDialect
        summon[SvDialectApi].loadDialect
        summon[EmitDialectApi].loadDialect
        summon[VerifDialectApi].loadDialect
        summon[VerifDialectApi].registerPasses

        val unknownLoc = summon[LocationApi].locationUnknownGet

        def translate(t: ProtocolInterface): MlirType = Translate.tpe(t)

        def typeText(t: MlirType): String =
          val sb = new StringBuilder
          t.print(sb ++= _)
          sb.result()

        /** First path where the two types diverge, with the local shapes; None when equivalent. */
        def firstDiff(exp: MlirType, act: MlirType, path: String): Option[String] =
          if exp.isEquivalentTo(act, true) then None
          else if exp.isBundle && act.isBundle then
            val (ne, na) = (exp.getBundleNumFields.toInt, act.getBundleNumFields.toInt)
            if ne != na then Some(s"$path: $ne fields expected, generator has $na")
            else
              (0 until ne).view.flatMap { i =>
                val (fe, fa) = (exp.getBundleFieldByIndex(i), act.getBundleFieldByIndex(i))
                if fe.getName != fa.getName then Some(s"$path: field $i is '${fa.getName}', expected '${fe.getName}'")
                else if fe.getIsFlip != fa.getIsFlip then
                  Some(s"$path.${fe.getName}: flip is ${fa.getIsFlip}, expected ${fe.getIsFlip}")
                else firstDiff(fe.getType, fa.getType, s"$path.${fe.getName}")
              }.headOption
                .orElse(Some(s"$path: ${typeText(exp)} vs ${typeText(act)}"))
          else if exp.isVector && act.isVector then
            if exp.getVectorElementNum != act.getVectorElementNum then
              Some(
                s"$path: Vec[${exp.getVectorElementNum}] expected, generator has Vec[${act.getVectorElementNum}]"
              )
            else firstDiff(exp.getVectorElementType, act.getVectorElementType, s"$path[]")
          else Some(s"$path: expected ${typeText(exp)}, generator has ${typeText(act)}")

        /** The single binding checkpoint (@dec-binding-check), shared by generator children and the testbench harness.
          * Everything the settled design promises about an enacted instance's ports is verified here, and only here:
          * presence, root direction, and exact interface structure (with the first divergence path on mismatch); ports
          * the instance declares beyond the promise are rejected too. Returns the port values by name.
          */
        def checkedPorts(
          instOp:   Operation,
          expected: Vector[(String, Boolean, ProtocolInterface)], // name, is output, settled interface
          subject:  String,
          at:       String
        ): Map[String, Value] =
          val names  = instOp.getInherentAttributeByName("portNames")
          val byName = Seq
            .tabulate(names.arrayAttrGetNumElements)(i => names.arrayAttrGetElement(i).stringAttrGetValue -> i)
            .toMap
          val dirs   = instOp.getInherentAttributeByName("portDirections")
          expected.foreach { (name, expectOutput, interface) =>
            byName.get(name) match
              case None    =>
                fail(s"port mismatch at $subject#$name: declaration has no matching generator port, at $at")
              case Some(i) =>
                val actualOutput = dirs.denseBoolArrayGetElement(i)
                if actualOutput != expectOutput then
                  fail(
                    s"port mismatch at $subject#$name: port direction is ${
                        if actualOutput then "output" else "input"
                      }, expected ${if expectOutput then "output" else "input"}, at $at"
                  )
                firstDiff(translate(interface), instOp.getResult(i).getType, name).foreach { diff =>
                  fail(
                    s"port mismatch at $subject#$name: port type differs from the settled interface at $diff, at $at"
                  )
                }
          }
          (byName.keySet -- expected.map(_._1)).toVector.sorted.foreach { extra =>
            fail(s"port mismatch at $subject#$extra: generator port has no corresponding declaration, at $at")
          }
          byName.map((n, i) => n -> instOp.getResult(i))

        def leafPaths(t: LayerTree, prefix: Vector[String] = Vector.empty): Vector[Vector[String]] =
          if t.children.isEmpty then (if prefix.isEmpty then Vector.empty else Vector(prefix))
          else t.children.toVector.sortBy(_._1).flatMap((n, sub) => leafPaths(sub, prefix :+ n))

        given MlirModule = summon[MlirModuleApi].moduleCreateEmpty(unknownLoc)
        val circuitName  = moduleNames(ModuleId.root)
        given Circuit    = summon[CircuitApi].op(circuitName)
        summon[Circuit].appendToModule()

        // Layer symbol definitions are emitted once, after linking: the union of every wrapper's routing layers and
        // every layer defined inside the linked per-module circuits (a generator may carry internal layers unrelated
        // to any routed probe). MLIR symbol references are order-independent, so the definitions may follow their
        // uses.
        def emitLayers(tree: LayerTree, parent: Option[CirctLayer]): Unit =
          tree.children.toVector.sortBy(_._1).foreach { (name, sub) =>
            val op = summon[LayerApi].op(name, unknownLoc, FirrtlLayerConvention.Bind)
            parent match
              case None    => summon[Circuit].block.appendOwnedOperation(op.operation)
              case Some(p) => p.block.appendOwnedOperation(op.operation)
            emitLayers(sub, Some(op))
          }

        // With a testbench declared, every probe leaf of the design becomes one of its data inputs (doc
        // @sec-dv-testbench); the binding checkpoint expects those ports on the testbench instance.
        val tbLeaves: Vector[ProbeLeaf] =
          if spec.testbench.isEmpty then Vector.empty else resolved.probes.flatMap(_.leaves)

        // ============ wrapper modules: one firrtl.module per instance ============
        spec.moduleOrder.foreach { id =>
          spec.wrapper(id).foreach { w =>
            val name       = moduleNames(id)
            // Definition and instance emission both sort ports by encoded name, so parent and child agree.
            val ports      = resolved.portPlans.filter(_.module == id).sortBy(_.name.encoded)
            val portFields = ports.map { p =>
              summon[FirrtlBundleFieldApi].createFirrtlBundleField(
                p.name.encoded,
                p.direction == PortDirection.Input,
                translate(p.interface)
              )
            }
            val portIndex  = ports.zipWithIndex.map((p, i) => p.name.encoded -> i).toMap
            val module     = summon[ModuleApi].op(
              name,
              unknownLoc,
              FirrtlConvention.Scalarized,
              portFields.map(f => (f, unknownLoc)),
              leafPaths(resolved.layerDecls.getOrElse(id, LayerTree.empty))
            )
            given Block    = module.block

            /** Emit one child instance; returns its port values. */
            def emitChild(c: String): Vector[((String, String), Value)] =
              val childId = id / c
              spec.modules(childId) match
                case gm: GeneratorModuleSpec =>
                  val rgm      = resolved.generatorModule(childId).get
                  val instOp   = backendOf(gm.entry).instantiate(rgm.fullParam, c, gm.loc)
                  // Expected ports: one bundle per design node, and one pure-probe port per interface leaf of every
                  // probe source (a Probe node is one leaf; its inner may be an aggregate).
                  val expected = rgm.view.nodes.map { nv =>
                    (nv.node.name, nv.direction == NodeDirection.Outward, nv.edge.interface)
                  } ++ gm.dvSources.flatMap { s =>
                    ProtocolBundle.leaves(s.interface).map { (path, leaf) =>
                      (PortName(s.name +: path.nameSegments).encoded, true, leaf)
                    }
                  } ++ (
                    // The testbench additionally takes every probe leaf of the design as a data input.
                    if spec.testbench.contains(childId) then tbLeaves.map(l => (l.portName, false, l.tpe))
                    else Vector.empty
                  )
                  checkedPorts(instOp, expected, childId.show, gm.loc.show).toVector.map((n, v) => ((c, n), v))
                case _:  WrapperModuleSpec   =>
                  val childPorts = resolved.portPlans.filter(_.module == childId).sortBy(_.name.encoded)
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
                  childPorts.zipWithIndex.map((p, i) => ((c, p.name.encoded), instOp.operation.getResult(i)))

            val childValues = w.children.flatMap(emitChild).toMap

            def baseOf(e: LocalEndpoint): Value = e match
              case LocalEndpoint.ThisPort(port)        =>
                portIndex
                  .get(port.encoded)
                  .fold(fail(s"wrapper $name: missing port ${port.encoded}"))(i => module.getIO(i))
              case LocalEndpoint.ChildPort(inst, port) =>
                childValues.getOrElse(
                  (inst, port.encoded),
                  fail(s"wrapper $name: missing child port $inst.${port.encoded}")
                )

            resolved.wirePlans.filter(_.module == id).foreach { wp =>
              wp.origin match
                case PlanOrigin.Design(_)       =>
                  summon[ConnectApi].op(baseOf(wp.from), baseOf(wp.to), unknownLoc).operation.appendToBlock()
                case PlanOrigin.Verification(_) =>
                  // Each verification wire forwards one probe leaf across this boundary (doc @sec-dv-routing).
                  wp.to match
                    case LocalEndpoint.ChildPort(_, _) =>
                      // The testbench end at the root: resolve the probe and connect the data into its input.
                      val res = summon[RefResolveApi].op(baseOf(wp.from), unknownLoc)
                      res.operation.appendToBlock()
                      summon[ConnectApi].op(res.result, baseOf(wp.to), unknownLoc).operation.appendToBlock()
                    case LocalEndpoint.ThisPort(_)     =>
                      // Pass-through: define the dangle — at the root without a testbench, the top-level probe
                      // port — from the child's probe port.
                      summon[RefDefineApi].op(baseOf(wp.to), baseOf(wp.from), unknownLoc).operation.appendToBlock()
            }

            module.appendToCircuit()
          }
        }

        // ============ link the per-module circuits dumped by the backends ============
        // Demand-driven: parse exactly the `<moduleName>.mlirbc` files of modules that are referenced but not yet
        // defined, transitively — stale or unrelated files in the dump directory are never touched.
        def collectRefs(root: Operation): Set[String] =
          val out = mutable.Set.empty[String]
          root.walk(
            op =>
              if op.getName.str == "firrtl.instance" then
                out += op.getInherentAttributeByName("moduleName").flatSymbolRefAttrGetValue
              WalkResultEnum.Advance
            ,
            WalkEnum.PreOrder
          )
          out.toSet

        def collectDefined(root: Operation): Set[String] =
          val out = mutable.Set.empty[String]
          root.walk(
            op =>
              if op.getName.str == "firrtl.module" || op.getName.str == "firrtl.extmodule" then
                out += op.getInherentAttributeByName("sym_name").stringAttrGetValue
              WalkResultEnum.Advance
            ,
            WalkEnum.PreOrder
          )
          out.toSet

        // Manual block iteration: operations obtained through direct calls live in our arena, unlike the transient
        // wrappers a walk callback receives.
        def isNullOp(op: Operation): Boolean           =
          op._segment.get(java.lang.foreign.ValueLayout.ADDRESS, 0).address == 0
        def opsIn(first: Operation): Vector[Operation] =
          Iterator.iterate(first)(_.getNextInBlock).takeWhile(op => !isNullOp(op)).toVector

        /** The layer definitions of one parsed per-module circuit, as a tree. */
        def layerTreeOf(op: Operation): (String, LayerTree) =
          val name     = op.getInherentAttributeByName("sym_name").stringAttrGetValue
          val children = opsIn(op.getFirstRegion.getFirstBlock.getFirstOperation)
            .filter(_.getName.str == "firrtl.layer")
            .map(layerTreeOf)
          name -> LayerTree(children.toMap)

        @annotation.tailrec
        def link(needed: List[String], defined: Set[String], layers: LayerTree): LayerTree = needed match
          case Nil                         => layers
          case sym :: rest if defined(sym) => link(rest, defined, layers)
          case sym :: rest                 =>
            val file       = mlirbcDir / s"$sym.mlirbc"
            if !os.exists(file) then fail(s"instantiated module '$sym' has no definition ($file not found)")
            val parsed     = summon[MlirModuleApi].moduleCreateParse(os.read.bytes(file))
            if parsed._segment.get(java.lang.foreign.ValueLayout.ADDRESS, 0).address == 0 then
              fail(s"cannot parse $file")
            val circuitOps = opsIn(parsed.getOperation.getFirstRegion.getFirstBlock.getFirstOperation)
              .filter(_.getName.str == "firrtl.circuit")
              .flatMap(c => opsIn(c.getFirstRegion.getFirstBlock.getFirstOperation))
            val moved      = circuitOps.filter(op => Set("firrtl.module", "firrtl.extmodule")(op.getName.str)).filter { op =>
              val s2 = op.getInherentAttributeByName("sym_name").stringAttrGetValue
              if defined(s2) then false
              else
                op.removeFromParent()
                summon[Circuit].block.appendOwnedOperation(op)
                true
            }
            // The circuit may define layers the module uses internally; carry them into the design circuit.
            val layers2    = circuitOps
              .filter(_.getName.str == "firrtl.layer")
              .map(layerTreeOf)
              .foldLeft(layers)((t, kv) => t.merge(LayerTree(Map(kv))))
            val defined2   = defined ++ moved.map(_.getInherentAttributeByName("sym_name").stringAttrGetValue)
            if !defined2(sym) then fail(s"instantiated module '$sym' has no definition in $file")
            link(rest ++ moved.flatMap(op => collectRefs(op) -- defined2), defined2, layers2)

        val defined0     = collectDefined(summon[Circuit].operation)
        val linkedLayers = link((collectRefs(summon[Circuit].operation) -- defined0).toList, defined0, LayerTree.empty)
        emitLayers(resolved.layerDecls.values.foldLeft(linkedLayers)(_.merge(_)), None)

        if !summon[MlirModule].getOperation.verify then fail("MLIR verification of the linked circuit failed")

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

        ElaboratedDesign(circuitName, fir.toString, verilog.toString, moduleNames)
      finally summon[Context].destroy()
    finally arena.close()
