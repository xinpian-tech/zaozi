package me.jiuyang.syntheke.circt

import me.jiuyang.syntheke.*

import org.llvm.circt.scalalib.capi.dialect.firrtl.{
  given_FirrtlBundleFieldApi,
  given_TypeApi,
  FirrtlBundleFieldApi,
  TypeApi as FirrtlTypeApi
}
import org.llvm.mlir.scalalib.capi.ir.{
  given_AttributeApi,
  given_IdentifierApi,
  given_TypeApi,
  Context,
  Type as MlirType
}
import org.llvm.mlir.scalalib.capi.support.{*, given}

import java.lang.foreign.Arena

private[circt] object Translate:

  def tpe(
    t: ProtocolInterface
  )(
    using Arena,
    Context
  ): MlirType = t match
    case ProtocolInterface.Bundle(fields)         =>
      fields.map { f =>
        val (flip, t) = f.tpe match
          case ProtocolInterface.Flipped(inner) => (true, inner)
          case t                                => (false, t)
        summon[FirrtlBundleFieldApi].createFirrtlBundleField(f.name, flip, tpe(t))
      }.getBundle
    case ProtocolInterface.Vec(n, e)              => tpe(e).getVector(n)
    case ProtocolInterface.Flipped(_)             =>
      throw new IllegalArgumentException("Flipped is legal only directly as a bundle field's type")
    case ProtocolInterface.Bits(w)                => w.getUInt
    case ProtocolInterface.UInt(w)                => w.getUInt
    case ProtocolInterface.SInt(w)                => w.getSInt
    case ProtocolInterface.Analog(w)              => w.getAnalog
    case ProtocolInterface.Bool                   => 1.getUInt
    case ProtocolInterface.Clock                  => summon[FirrtlTypeApi].getClock
    case ProtocolInterface.Reset                  => summon[FirrtlTypeApi].getReset
    case ProtocolInterface.AsyncReset             => summon[FirrtlTypeApi].getAsyncReset
    case ProtocolInterface.Probe(i, l) =>
      l.fold(tpe(i).getRef(false))(layer => tpe(i).getRef(false, layer.segments))
