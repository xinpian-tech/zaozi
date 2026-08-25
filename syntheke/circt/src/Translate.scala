// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.syntheke.circt

import me.jiuyang.syntheke.*

import org.llvm.circt.scalalib.capi.dialect.firrtl.{
  given_FirrtlBundleFieldApi,
  given_TypeApi,
  FirrtlBundleField,
  FirrtlBundleFieldApi,
  TypeApi as FirrtlTypeApi
}
import org.llvm.mlir.scalalib.capi.ir.{Context, Type as MlirType}

import java.lang.foreign.Arena

/** Serializable interface descriptions to MLIR FIRRTL types (doc @sec-protocol-interface: negotiation manipulates
  * the data, elaboration translates it). A bundle whose fields contain probes becomes an open bundle in CIRCT.
  */
object Translate:

  def tpe(t: ProtocolInterface)(using Arena, Context): MlirType = t match
    case ProtocolInterface.Bundle(fields) =>
      fields.map(f => summon[FirrtlBundleFieldApi].createFirrtlBundleField(f.name, f.flip, tpe(f.tpe))).getBundle
    case ProtocolInterface.Vec(n, e)      => tpe(e).getVector(n)
    case ProtocolInterface.UInt(w)        => w.getUInt
    case ProtocolInterface.SInt(w)        => w.getSInt
    case ProtocolInterface.Bool           => 1.getUInt
    case ProtocolInterface.Clock          => summon[FirrtlTypeApi].getClock
    case ProtocolInterface.Reset          => summon[FirrtlTypeApi].getReset
    case ProtocolInterface.Probe(i, l)    => tpe(i).getRef(false, l.segments)

  /** A module port field: `isInput` maps to a flipped top-level bundle field. */
  def portField(name: String, isInput: Boolean, t: ProtocolInterface)(using Arena, Context): FirrtlBundleField =
    summon[FirrtlBundleFieldApi].createFirrtlBundleField(name, isInput, tpe(t))

  /** The distinct probe layer paths appearing in an interface, name-sorted — a module's layer requirements. */
  def probeLayers(t: ProtocolInterface): Vector[Vector[String]] =
    def collect(t: ProtocolInterface): Vector[Vector[String]] = t match
      case ProtocolInterface.Bundle(fields) => fields.flatMap(f => collect(f.tpe))
      case ProtocolInterface.Vec(_, e)      => collect(e)
      case ProtocolInterface.Probe(i, l)    => l.segments +: collect(i)
      case _                                => Vector.empty
    collect(t).distinct.sorted(using Ordering.Implicits.seqOrdering[Vector, String])
