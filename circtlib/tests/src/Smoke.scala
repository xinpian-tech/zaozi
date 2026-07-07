// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.zaozi.circtlib.tests

import org.llvm.circt.scalalib.capi.dialect.firrtl.{
  given_DialectApi,
  given_FirrtlBundleFieldApi,
  given_FirrtlDirectionApi,
  given_TypeApi,
  DialectApi as FirrtlDialect,
  FirrtlBundleFieldApi,
  FirrtlConvention,
  FirrtlNameKind,
  TypeApi as FirrtlTypeApi
}
import org.llvm.circt.scalalib.dialect.firrtl.operation.{*, given}
import org.llvm.mlir.scalalib.capi.ir.{given_ModuleApi, Module as MlirModule, ModuleApi as MlirModuleApi, *, given}
import utest.*

import java.lang.foreign.Arena

inline def blockCheck(
  checkLines: String*
)(
  using Block,
  Arena
): Unit =
  val out         = StringBuilder()
  summon[Block].print(out ++= _)
  val blockString = out.toString()
  if (checkLines.isEmpty)
    assert(blockString == "FAILED")
  else checkLines.foreach(l => assert(blockString.contains(l)))

object Smoke extends TestSuite:
  // Per-leaf arena + Context lifecycle via utest beforeEach/afterEach hooks.
  // utest's macro rejects `test()` nested in `try/finally`, so the hook
  // pattern is the only way to make the cleanup exception-safe without
  // restructuring every leaf body.
  private var currentArena:   Arena   = null
  private var currentContext: Context = null

  override def utestBeforeEach(path: Seq[String]): Unit =
    currentArena = Arena.ofConfined()
    currentContext = null

  override def utestAfterEach(path: Seq[String]): Unit =
    val c = currentContext
    val a = currentArena
    currentContext = null
    currentArena = null
    try if c != null then c.destroy()
    finally if a != null then a.close()

  val tests: Tests = Tests:
    test("Load Panama Context"):
      given Arena = currentArena
      test("Load Dialect"):
        val context         = summon[ContextApi].contextCreate
        currentContext = context
        summon[FirrtlDialect].loadDialect
        given Context       = context
        val unknownLocation = summon[LocationApi].locationUnknownGet
        test("Create MlirModule"):
          given MlirModule = summon[MlirModuleApi].moduleCreateEmpty(unknownLocation)
          test("Create Circuit"):
            val circuit: Circuit = summon[CircuitApi].op("Passthrough")
            circuit.appendToModule()
        test("Create CirctModule"):
          val api = summon[FirrtlBundleFieldApi]
          val module: Module = summon[ModuleApi].op(
            "DummyModule",
            unknownLocation,
            FirrtlConvention.Scalarized,
            Seq(
              (api.createFirrtlBundleField("i", true, 32.getUInt), unknownLocation),
              (api.createFirrtlBundleField("o", false, 32.getUInt), unknownLocation)
            ),
            Seq.empty
          )
          given Module = module
          given Block  = module.block
          test("Statement"):
            test("Connect"):
              val w0 = summon[WireApi].op(
                name = "w0",
                location = unknownLocation,
                nameKind = FirrtlNameKind.Droppable,
                tpe = 32.getSInt
              )
              w0.operation.appendToBlock()
              val w1 = summon[WireApi].op(
                name = "w1",
                location = unknownLocation,
                nameKind = FirrtlNameKind.Droppable,
                tpe = 32.getSInt
              )
              w1.operation.appendToBlock()
              summon[ConnectApi].op(w0.result, w1.result, unknownLocation).operation.appendToBlock()

          test("Structure"):
            test("Instance"):
              summon[InstanceApi]
                .op(
                  "SomeModule",
                  "inst0",
                  FirrtlNameKind.Interesting,
                  unknownLocation,
                  Seq(
                    api.createFirrtlBundleField("i", true, 32.getUInt),
                    api.createFirrtlBundleField("o", true, 32.getUInt)
                  ),
                  Seq.empty
                )
                .operation
                .appendToBlock()

            test("Wire"):
              summon[WireApi]
                .op(
                  name = "someWire",
                  location = unknownLocation,
                  nameKind = FirrtlNameKind.Droppable,
                  tpe = 32.getSInt
                )
                .operation
                .appendToBlock()

            test("Reg"):
              given org.llvm.mlir.scalalib.capi.ir.Block = module.block
              val clock                                  = summon[WireApi].op(
                name = "clock",
                location = unknownLocation,
                nameKind = FirrtlNameKind.Droppable,
                tpe = summon[FirrtlTypeApi].getClock
              )
              clock.operation.appendToBlock()
              summon[RegApi]
                .op(
                  name = "someReg",
                  location = unknownLocation,
                  nameKind = FirrtlNameKind.Droppable,
                  tpe = 32.getUInt,
                  clock = clock.result
                )
                .operation
                .appendToBlock()

            test("RegReset"):
              given org.llvm.mlir.scalalib.capi.ir.Block = module.block
              val clock                                  = summon[WireApi].op(
                name = "clock",
                location = unknownLocation,
                nameKind = FirrtlNameKind.Droppable,
                tpe = summon[FirrtlTypeApi].getClock
              )
              clock.operation.appendToBlock()
              val reset                                  = summon[WireApi].op(
                name = "reset",
                location = unknownLocation,
                nameKind = FirrtlNameKind.Droppable,
                tpe = 1.getUInt
              )
              reset.operation.appendToBlock()
              val resetValue                             = summon[ConstantApi].op(
                input = BigInt(19890604),
                width = 64,
                signed = false,
                location = unknownLocation
              )
              resetValue.operation.appendToBlock()
              summon[RegResetApi]
                .op(
                  name = "someReg",
                  location = unknownLocation,
                  nameKind = FirrtlNameKind.Droppable,
                  tpe = 32.getUInt,
                  clock = clock.result,
                  reset = reset.result,
                  resetValue = resetValue.result
                )
                .operation
                .appendToBlock()

            test("Node"):
              given org.llvm.mlir.scalalib.capi.ir.Block = module.block
              summon[NodeApi]
                .op(
                  name = "someReg",
                  location = unknownLocation,
                  nameKind = FirrtlNameKind.Droppable,
                  input = module.getIO(0)
                )
                .operation
                .appendToBlock()

          test("Expression"):
            val uint1   = summon[WireApi].op(
              name = "uint1",
              location = unknownLocation,
              nameKind = FirrtlNameKind.Droppable,
              tpe = 32.getUInt
            )
            uint1.operation.appendToBlock()
            val uint2   = summon[WireApi].op(
              name = "uint2",
              location = unknownLocation,
              nameKind = FirrtlNameKind.Droppable,
              tpe = 16.getUInt
            )
            uint2.operation.appendToBlock()
            val sint1   = summon[WireApi].op(
              name = "sint1",
              location = unknownLocation,
              nameKind = FirrtlNameKind.Droppable,
              tpe = 32.getSInt
            )
            sint1.operation.appendToBlock()
            val sint2   = summon[WireApi].op(
              name = "sint2",
              location = unknownLocation,
              nameKind = FirrtlNameKind.Droppable,
              tpe = 32.getSInt
            )
            sint2.operation.appendToBlock()
            val bool1   = summon[WireApi].op(
              name = "bool1",
              location = unknownLocation,
              nameKind = FirrtlNameKind.Droppable,
              tpe = 1.getUInt
            )
            bool1.operation.appendToBlock()
            val bundle1 = summon[WireApi].op(
              name = "bundle1",
              location = unknownLocation,
              nameKind = FirrtlNameKind.Droppable,
              tpe =
                val api = summon[FirrtlBundleFieldApi]
                Seq(
                  api.createFirrtlBundleField("a", true, 32.getUInt),
                  api.createFirrtlBundleField("b", false, 32.getUInt)
                ).getBundle
            )
            val vector1 = summon[WireApi].op(
              name = "vector1",
              location = unknownLocation,
              nameKind = FirrtlNameKind.Droppable,
              tpe = 32.getUInt.getVector(32)
            )
            test("Add"):
              test("UInt"):
                summon[AddPrimApi].op(uint1.result, uint2.result, unknownLocation).operation.appendToBlock()

              test("SInt"):
                summon[AddPrimApi].op(sint1.result, sint2.result, unknownLocation).operation.appendToBlock()

            test("And"):
              test("UInt"):
                summon[AndPrimApi].op(uint1.result, uint2.result, unknownLocation).operation.appendToBlock()

              test("SInt"):
                summon[AndPrimApi].op(sint1.result, sint2.result, unknownLocation).operation.appendToBlock()

            test("AndR"):
              summon[AndRPrimApi].op(uint1.result, unknownLocation).operation.appendToBlock()

            test("AsReset"):
              summon[AsResetPrimApi].op(bool1.result, unknownLocation).operation.appendToBlock()

            test("AsClock"):
              summon[AsClockPrimApi].op(bool1.result, unknownLocation).operation.appendToBlock()

            test("AsSInt"):
              test("UInt to SInt"):
                summon[AsSIntPrimApi].op(uint1.result, unknownLocation).operation.appendToBlock()

              test("SInt to SInt"):
                summon[AsSIntPrimApi].op(sint1.result, unknownLocation).operation.appendToBlock()

            test("AsUInt"):
              test("UInt to UInt"):
                summon[AsUIntPrimApi].op(uint1.result, unknownLocation).operation.appendToBlock()

              test("SInt to UInt"):
                summon[AsUIntPrimApi].op(sint1.result, unknownLocation).operation.appendToBlock()

            test("BitsPrim"):
              test("UInt"):
                summon[BitsPrimApi].op(uint1.result, 2, 1, unknownLocation).operation.appendToBlock()

              test("SInt"):
                summon[BitsPrimApi].op(sint1.result, 2, 1, unknownLocation).operation.appendToBlock()

            test("CatPrim"):
              test("UInt"):
                summon[CatPrimApi].op(uint1.result, uint1.result, unknownLocation).operation.appendToBlock()

              test("SInt"):
                summon[CatPrimApi].op(sint1.result, sint1.result, unknownLocation).operation.appendToBlock()

            test("Constant"):
              test("Signed"):
                summon[ConstantApi].op(-BigInt("deadbeafdead", 16), 64, true, unknownLocation).operation.appendToBlock()

              test("Unsigned"):
                summon[ConstantApi].op(BigInt("beafdeadbeaf", 16), 64, false, unknownLocation).operation.appendToBlock()

            test("CvtPrim"):
              test("UInt"):
                summon[CvtPrimApi].op(uint1.result, unknownLocation).operation.appendToBlock()

              test("SInt"):
                summon[CvtPrimApi].op(sint1.result, unknownLocation).operation.appendToBlock()

            test("DShlPrim"):
              test("UInt"):
                summon[DShlPrimApi].op(uint1.result, uint2.result, unknownLocation).operation.appendToBlock()

              test("SInt"):
                summon[DShlPrimApi].op(sint1.result, uint2.result, unknownLocation).operation.appendToBlock()

            test("DShrPrim"):
              test("UInt"):
                summon[DShrPrimApi].op(uint1.result, uint2.result, unknownLocation).operation.appendToBlock()

              test("SInt"):
                summon[DShrPrimApi].op(sint1.result, uint2.result, unknownLocation).operation.appendToBlock()

            test("DivPrim"):
              test("UInt"):
                summon[DivPrimApi].op(uint1.result, uint2.result, unknownLocation).operation.appendToBlock()

              test("SInt"):
                summon[DivPrimApi].op(sint1.result, sint2.result, unknownLocation).operation.appendToBlock()

            test("EQPrim"):
              test("UInt"):
                summon[EQPrimApi].op(uint1.result, uint2.result, unknownLocation).operation.appendToBlock()

              test("SInt"):
                summon[EQPrimApi].op(sint1.result, sint2.result, unknownLocation).operation.appendToBlock()

            test("GEQPrim"):
              test("UInt"):
                summon[GEQPrimApi].op(uint1.result, uint2.result, unknownLocation).operation.appendToBlock()

              test("SInt"):
                summon[GEQPrimApi].op(sint1.result, sint2.result, unknownLocation).operation.appendToBlock()

            test("GTPrim"):
              test("UInt"):
                summon[GTPrimApi].op(uint1.result, uint2.result, unknownLocation).operation.appendToBlock()

              test("SInt"):
                summon[GTPrimApi].op(sint1.result, sint2.result, unknownLocation).operation.appendToBlock()

            test("HeadPrim"):
              test("UInt"):
                summon[HeadPrimApi].op(uint1.result, 1, unknownLocation).operation.appendToBlock()

              test("SInt"):
                summon[HeadPrimApi].op(sint1.result, 2, unknownLocation).operation.appendToBlock()

            test("LEQPrim"):
              test("UInt"):
                summon[LEQPrimApi].op(uint1.result, uint2.result, unknownLocation).operation.appendToBlock()

              test("SInt"):
                summon[LEQPrimApi].op(sint1.result, sint2.result, unknownLocation).operation.appendToBlock()

            test("LTPrim"):
              test("UInt"):
                summon[LTPrimApi].op(uint1.result, uint2.result, unknownLocation).operation.appendToBlock()

              test("SInt"):
                summon[LTPrimApi].op(sint1.result, sint2.result, unknownLocation).operation.appendToBlock()

            test("MulPrim"):
              test("UInt"):
                summon[MulPrimApi].op(uint1.result, uint2.result, unknownLocation).operation.appendToBlock()

              test("SInt"):
                summon[MulPrimApi].op(sint1.result, sint2.result, unknownLocation).operation.appendToBlock()

            test("MuxPrim"):
              test("UInt"):
                summon[MuxPrimApi]
                  .op(bool1.result, uint1.result, uint2.result, unknownLocation)
                  .operation
                  .appendToBlock()

              test("SInt"):
                summon[MuxPrimApi]
                  .op(bool1.result, uint1.result, sint2.result, unknownLocation)
                  .operation
                  .appendToBlock()

            test("NegPrim"):
              test("UInt"):
                summon[NegPrimApi].op(uint1.result, unknownLocation).operation.appendToBlock()

              test("SInt"):
                summon[NegPrimApi].op(sint1.result, unknownLocation).operation.appendToBlock()

            test("NotPrim"):
              test("UInt"):
                summon[NotPrimApi].op(uint1.result, unknownLocation).operation.appendToBlock()

              test("SInt"):
                summon[NotPrimApi].op(sint1.result, unknownLocation).operation.appendToBlock()

            test("OrPrim"):
              test("UInt"):
                summon[OrPrimApi].op(uint1.result, uint2.result, unknownLocation).operation.appendToBlock()

              test("SInt"):
                summon[OrPrimApi].op(sint1.result, sint2.result, unknownLocation).operation.appendToBlock()

            test("OrRPrim"):
              test("UInt"):
                summon[OrRPrimApi].op(uint1.result, unknownLocation).operation.appendToBlock()

              test("SInt"):
                summon[OrRPrimApi].op(sint1.result, unknownLocation).operation.appendToBlock()

            test("PadPrim"):
              test("UInt"):
                summon[PadPrimApi].op(uint1.result, 16, unknownLocation).operation.appendToBlock()

              test("SInt"):
                summon[PadPrimApi].op(sint1.result, 64, unknownLocation).operation.appendToBlock()

            test("RemPrim"):
              test("UInt"):
                summon[RemPrimApi].op(uint1.result, uint2.result, unknownLocation).operation.appendToBlock()

              test("SInt"):
                summon[RemPrimApi].op(sint1.result, sint2.result, unknownLocation).operation.appendToBlock()

            test("ShlPrim"):
              test("UInt"):
                summon[ShlPrimApi].op(uint1.result, 16, unknownLocation).operation.appendToBlock()

              test("SInt"):
                summon[ShlPrimApi].op(sint1.result, 64, unknownLocation).operation.appendToBlock()

            test("ShrPrim"):
              test("UInt"):
                summon[ShrPrimApi].op(uint1.result, 16, unknownLocation).operation.appendToBlock()

              test("SInt"):
                summon[ShrPrimApi].op(sint1.result, 64, unknownLocation).operation.appendToBlock()

            test("SubPrim"):
              test("UInt"):
                summon[SubPrimApi].op(uint1.result, uint2.result, unknownLocation).operation.appendToBlock()

              test("SInt"):
                summon[SubPrimApi].op(sint1.result, sint2.result, unknownLocation).operation.appendToBlock()

            test("Subaccess"):
              summon[SubaccessApi].op(vector1.result, uint1.result, unknownLocation).operation.appendToBlock()

            test("Subfield"):
              summon[SubfieldApi].op(bundle1.result, 0, unknownLocation).operation.appendToBlock()

            test("Subindex"):
              summon[SubindexApi].op(vector1.result, 4, unknownLocation).operation.appendToBlock()

            test("TailPrim"):
              test("UInt"):
                summon[TailPrimApi].op(uint1.result, 2, unknownLocation).operation.appendToBlock()

              test("SInt"):
                summon[TailPrimApi].op(sint1.result, 0, unknownLocation).operation.appendToBlock()

            test("XorPrimApi"):
              test("UInt"):
                summon[XorPrimApi].op(uint1.result, uint2.result, unknownLocation).operation.appendToBlock()

              test("SInt"):
                summon[XorPrimApi].op(sint1.result, sint2.result, unknownLocation).operation.appendToBlock()

            test("XorRPrim"):
              test("UInt"):
                summon[XorRPrimApi].op(uint1.result, unknownLocation).operation.appendToBlock()

              test("SInt"):
                summon[XorRPrimApi].op(sint1.result, unknownLocation).operation.appendToBlock()
