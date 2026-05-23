// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package org.llvm.circt.scalalib.dialect.firrtl.operation

import org.llvm.mlir.scalalib.capi.support.HasOperation
import org.llvm.mlir.scalalib.capi.ir.Operation

class AddPrim(val _operation: Operation)
trait AddPrimApi extends HasOperation[AddPrim]
end AddPrimApi

class AggregateConstant(val _operation: Operation)
trait AggregateConstantApi extends HasOperation[AggregateConstant]
end AggregateConstantApi

class AndPrim(val _operation: Operation)
trait AndPrimApi extends HasOperation[AndPrim]
end AndPrimApi

class AndRPrim(val _operation: Operation)
trait AndRPrimApi extends HasOperation[AndRPrim]
end AndRPrimApi

class AsAsyncResetPrim(val _operation: Operation)
trait AsAsyncResetPrimApi extends HasOperation[AsAsyncResetPrim]
end AsAsyncResetPrimApi

class AsClockPrim(val _operation: Operation)
trait AsClockPrimApi extends HasOperation[AsClockPrim]
end AsClockPrimApi

class AsSIntPrim(val _operation: Operation)
trait AsSIntPrimApi extends HasOperation[AsSIntPrim]
end AsSIntPrimApi

class AsUIntPrim(val _operation: Operation)
trait AsUIntPrimApi extends HasOperation[AsUIntPrim]
end AsUIntPrimApi

class BitCast(val _operation: Operation)
trait BitCastApi extends HasOperation[BitCast]
end BitCastApi

class BitsPrim(val _operation: Operation)
trait BitsPrimApi extends HasOperation[BitsPrim]
end BitsPrimApi

class BoolConstant(val _operation: Operation)
trait BoolConstantApi extends HasOperation[BoolConstant]
end BoolConstantApi

class BundleCreate(val _operation: Operation)
trait BundleCreateApi extends HasOperation[BundleCreate]
end BundleCreateApi

class CatPrim(val _operation: Operation)
trait CatPrimApi extends HasOperation[CatPrim]
end CatPrimApi

class ConstCast(val _operation: Operation)
trait ConstCastApi extends HasOperation[ConstCast]
end ConstCastApi

class Constant(val _operation: Operation)
trait ConstantApi extends HasOperation[Constant]
end ConstantApi

class CvtPrim(val _operation: Operation)
trait CvtPrimApi extends HasOperation[CvtPrim]
end CvtPrimApi

class DShlPrim(val _operation: Operation)
trait DShlPrimApi extends HasOperation[DShlPrim]
end DShlPrimApi

class DShlwPrim(val _operation: Operation)
trait DShlwPrimApi extends HasOperation[DShlwPrim]
end DShlwPrimApi

class DShrPrim(val _operation: Operation)
trait DShrPrimApi extends HasOperation[DShrPrim]
end DShrPrimApi

class DivPrim(val _operation: Operation)
trait DivPrimApi extends HasOperation[DivPrim]
end DivPrimApi

class DoubleConstant(val _operation: Operation)
trait DoubleConstantApi extends HasOperation[DoubleConstant]
end DoubleConstantApi

class EQPrim(val _operation: Operation)
trait EQPrimApi extends HasOperation[EQPrim]
end EQPrimApi

class ElementwiseAndPrim(val _operation: Operation)
trait ElementwiseAndPrimApi extends HasOperation[ElementwiseAndPrim]
end ElementwiseAndPrimApi

class ElementwiseOrPrim(val _operation: Operation)
trait ElementwiseOrPrimApi extends HasOperation[ElementwiseOrPrim]
end ElementwiseOrPrimApi

class ElementwiseXorPrim(val _operation: Operation)
trait ElementwiseXorPrimApi extends HasOperation[ElementwiseXorPrim]
end ElementwiseXorPrimApi

class FEnumCreate(val _operation: Operation)
trait FEnumCreateApi extends HasOperation[FEnumCreate]
end FEnumCreateApi

class FIntegerConstant(val _operation: Operation)
trait FIntegerConstantApi extends HasOperation[FIntegerConstant]
end FIntegerConstantApi

class GEQPrim(val _operation: Operation)
trait GEQPrimApi extends HasOperation[GEQPrim]
end GEQPrimApi

class GTPrim(val _operation: Operation)
trait GTPrimApi extends HasOperation[GTPrim]
end GTPrimApi

class HWStructCast(val _operation: Operation)
trait HWStructCastApi extends HasOperation[HWStructCast]
end HWStructCastApi

class HeadPrim(val _operation: Operation)
trait HeadPrimApi extends HasOperation[HeadPrim]
end HeadPrimApi

class IntegerAdd(val _operation: Operation)
trait IntegerAddApi extends HasOperation[IntegerAdd]
end IntegerAddApi

class IntegerMul(val _operation: Operation)
trait IntegerMulApi extends HasOperation[IntegerMul]
end IntegerMulApi

class IntegerShl(val _operation: Operation)
trait IntegerShlApi extends HasOperation[IntegerShl]
end IntegerShlApi

class IntegerShr(val _operation: Operation)
trait IntegerShrApi extends HasOperation[IntegerShr]
end IntegerShrApi

class InvalidValue(val _operation: Operation)
trait InvalidValueApi extends HasOperation[InvalidValue]
end InvalidValueApi

class IsTag(val _operation: Operation)
trait IsTagApi extends HasOperation[IsTag]
end IsTagApi

class LEQPrim(val _operation: Operation)
trait LEQPrimApi extends HasOperation[LEQPrim]
end LEQPrimApi

class LTPrim(val _operation: Operation)
trait LTPrimApi extends HasOperation[LTPrim]
end LTPrimApi

class ListConcat(val _operation: Operation)
trait ListConcatApi extends HasOperation[ListConcat]
end ListConcatApi

class ListCreate(val _operation: Operation)
trait ListCreateApi extends HasOperation[ListCreate]
end ListCreateApi

class MulPrim(val _operation: Operation)
trait MulPrimApi extends HasOperation[MulPrim]
end MulPrimApi

class MultibitMux(val _operation: Operation)
trait MultibitMuxApi extends HasOperation[MultibitMux]
end MultibitMuxApi

class Mux2CellIntrinsic(val _operation: Operation)
trait Mux2CellIntrinsicApi extends HasOperation[Mux2CellIntrinsic]
end Mux2CellIntrinsicApi

class Mux4CellIntrinsic(val _operation: Operation)
trait Mux4CellIntrinsicApi extends HasOperation[Mux4CellIntrinsic]
end Mux4CellIntrinsicApi

class MuxPrim(val _operation: Operation)
trait MuxPrimApi extends HasOperation[MuxPrim]
end MuxPrimApi

class NEQPrim(val _operation: Operation)
trait NEQPrimApi extends HasOperation[NEQPrim]
end NEQPrimApi

class NegPrim(val _operation: Operation)
trait NegPrimApi extends HasOperation[NegPrim]
end NegPrimApi

class NotPrim(val _operation: Operation)
trait NotPrimApi extends HasOperation[NotPrim]
end NotPrimApi

class ObjectAnyRefCast(val _operation: Operation)
trait ObjectAnyRefCastApi extends HasOperation[ObjectAnyRefCast]
end ObjectAnyRefCastApi

class ObjectSubfield(val _operation: Operation)
trait ObjectSubfieldApi extends HasOperation[ObjectSubfield]
end ObjectSubfieldApi

class OpenSubfield(val _operation: Operation)
trait OpenSubfieldApi extends HasOperation[OpenSubfield]
end OpenSubfieldApi

class OpenSubindex(val _operation: Operation)
trait OpenSubindexApi extends HasOperation[OpenSubindex]
end OpenSubindexApi

class OrPrim(val _operation: Operation)
trait OrPrimApi extends HasOperation[OrPrim]
end OrPrimApi

class OrRPrim(val _operation: Operation)
trait OrRPrimApi extends HasOperation[OrRPrim]
end OrRPrimApi

class PadPrim(val _operation: Operation)
trait PadPrimApi extends HasOperation[PadPrim]
end PadPrimApi

class Path(val _operation: Operation)
trait PathApi extends HasOperation[Path]
end PathApi

class RWProbe(val _operation: Operation)
trait RWProbeApi extends HasOperation[RWProbe]
end RWProbeApi

class RefCast(val _operation: Operation)
trait RefCastApi extends HasOperation[RefCast]
end RefCastApi

class RefResolve(val _operation: Operation)
trait RefResolveApi extends HasOperation[RefResolve]
end RefResolveApi

class RefSend(val _operation: Operation)
trait RefSendApi extends HasOperation[RefSend]
end RefSendApi

class RefSub(val _operation: Operation)
trait RefSubApi extends HasOperation[RefSub]
end RefSubApi

class RemPrim(val _operation: Operation)
trait RemPrimApi extends HasOperation[RemPrim]
end RemPrimApi

class ShlPrim(val _operation: Operation)
trait ShlPrimApi extends HasOperation[ShlPrim]
end ShlPrimApi

class ShrPrim(val _operation: Operation)
trait ShrPrimApi extends HasOperation[ShrPrim]
end ShrPrimApi

class SizeOfIntrinsic(val _operation: Operation)
trait SizeOfIntrinsicApi extends HasOperation[SizeOfIntrinsic]
end SizeOfIntrinsicApi

class SpecialConstant(val _operation: Operation)
trait SpecialConstantApi extends HasOperation[SpecialConstant]
end SpecialConstantApi

class StringConstant(val _operation: Operation)
trait StringConstantApi extends HasOperation[StringConstant]
end StringConstantApi

class SubPrim(val _operation: Operation)
trait SubPrimApi extends HasOperation[SubPrim]
end SubPrimApi

class Subaccess(val _operation: Operation)
trait SubaccessApi extends HasOperation[Subaccess]
end SubaccessApi

class Subfield(val _operation: Operation)
trait SubfieldApi extends HasOperation[Subfield]
end SubfieldApi

class Subindex(val _operation: Operation)
trait SubindexApi extends HasOperation[Subindex]
end SubindexApi

class Subtag(val _operation: Operation)
trait SubtagApi extends HasOperation[Subtag]
end SubtagApi

class TagExtract(val _operation: Operation)
trait TagExtractApi extends HasOperation[TagExtract]
end TagExtractApi

class TailPrim(val _operation: Operation)
trait TailPrimApi extends HasOperation[TailPrim]
end TailPrimApi

class UninferredResetCast(val _operation: Operation)
trait UninferredResetCastApi extends HasOperation[UninferredResetCast]
end UninferredResetCastApi

class UnresolvedPath(val _operation: Operation)
trait UnresolvedPathApi extends HasOperation[UnresolvedPath]
end UnresolvedPathApi

class VectorCreate(val _operation: Operation)
trait VectorCreateApi extends HasOperation[VectorCreate]
end VectorCreateApi

class VerbatimExpr(val _operation: Operation)
trait VerbatimExprApi extends HasOperation[VerbatimExpr]
end VerbatimExprApi

class VerbatimWire(val _operation: Operation)
trait VerbatimWireApi extends HasOperation[VerbatimWire]
end VerbatimWireApi

class XMRDeref(val _operation: Operation)
trait XMRDerefApi extends HasOperation[XMRDeref]
end XMRDerefApi

class XMRRef(val _operation: Operation)
trait XMRRefApi extends HasOperation[XMRRef]
end XMRRefApi

class XorPrim(val _operation: Operation)
trait XorPrimApi extends HasOperation[XorPrim]
end XorPrimApi

class XorRPrim(val _operation: Operation)
trait XorRPrimApi extends HasOperation[XorRPrim]
end XorRPrimApi
