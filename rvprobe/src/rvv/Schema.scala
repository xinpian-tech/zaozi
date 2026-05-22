// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.rvprobe.rvv

enum OperandRole:
  case Vd
  case Vs1
  case Vs2
  case Vs3
  case Vm
  case V0
  case Rs1
  case Rs1Mem
  case Rs2
  case Rd
  case Fs1
  case Fd
  case Imm
  case Uimm
  case VmGroup2
  case VmGroup3

enum SchemaCategory:
  case Integer
  case Fp
  case LoadStore
  case Vsetvl

enum Schema(
  val formatString: String,
  val category:     SchemaCategory,
  val operandRoles: List[OperandRole]):

  def indexedSlot: Option[OperandRole] = this match
    case Schema.VdRs1mVs2Vm | Schema.Vs3Rs1mVs2Vm => Some(OperandRole.Vs2)
    case _                                        => None


  case Vsetvl
    extends Schema("vsetvl", SchemaCategory.Vsetvl, List(OperandRole.Rd, OperandRole.Rs1, OperandRole.Rs2))
  case Vsetvli
    extends Schema("vsetvli", SchemaCategory.Vsetvl, List(OperandRole.Rd, OperandRole.Rs1, OperandRole.Uimm))
  case Vsetivli
    extends Schema("vsetivli", SchemaCategory.Vsetvl, List(OperandRole.Rd, OperandRole.Uimm, OperandRole.Uimm))

  case FdVs2
    extends Schema("fd,vs2", SchemaCategory.Fp, List(OperandRole.Fd, OperandRole.Vs2))
  case VdFs1
    extends Schema("vd,fs1", SchemaCategory.Fp, List(OperandRole.Vd, OperandRole.Fs1))
  case VdFs1Vs2Vm
    extends Schema(
      "vd,fs1,vs2,vm",
      SchemaCategory.Fp,
      List(OperandRole.Vd, OperandRole.Fs1, OperandRole.Vs2, OperandRole.Vm))
  case VdVs2Fs1V0
    extends Schema(
      "vd,vs2,fs1,v0",
      SchemaCategory.Fp,
      List(OperandRole.Vd, OperandRole.Vs2, OperandRole.Fs1, OperandRole.V0))
  case VdVs2Fs1Vm
    extends Schema(
      "vd,vs2,fs1,vm",
      SchemaCategory.Fp,
      List(OperandRole.Vd, OperandRole.Vs2, OperandRole.Fs1, OperandRole.Vm))

  case VdRs1m
    extends Schema("vd,(rs1)", SchemaCategory.LoadStore, List(OperandRole.Vd, OperandRole.Rs1Mem))
  case VdRs1mVm
    extends Schema(
      "vd,(rs1),vm",
      SchemaCategory.LoadStore,
      List(OperandRole.Vd, OperandRole.Rs1Mem, OperandRole.Vm))
  case VdRs1mRs2Vm
    extends Schema(
      "vd,(rs1),rs2,vm",
      SchemaCategory.LoadStore,
      List(OperandRole.Vd, OperandRole.Rs1Mem, OperandRole.Rs2, OperandRole.Vm))
  case VdRs1mVs2Vm
    extends Schema(
      "vd,(rs1),vs2,vm",
      SchemaCategory.LoadStore,
      List(OperandRole.Vd, OperandRole.Rs1Mem, OperandRole.Vs2, OperandRole.Vm))
  case Vs3Rs1m
    extends Schema("vs3,(rs1)", SchemaCategory.LoadStore, List(OperandRole.Vs3, OperandRole.Rs1Mem))
  case Vs3Rs1mVm
    extends Schema(
      "vs3,(rs1),vm",
      SchemaCategory.LoadStore,
      List(OperandRole.Vs3, OperandRole.Rs1Mem, OperandRole.Vm))
  case Vs3Rs1mRs2Vm
    extends Schema(
      "vs3,(rs1),rs2,vm",
      SchemaCategory.LoadStore,
      List(OperandRole.Vs3, OperandRole.Rs1Mem, OperandRole.Rs2, OperandRole.Vm))
  case Vs3Rs1mVs2Vm
    extends Schema(
      "vs3,(rs1),vs2,vm",
      SchemaCategory.LoadStore,
      List(OperandRole.Vs3, OperandRole.Rs1Mem, OperandRole.Vs2, OperandRole.Vm))

  case RdVs2
    extends Schema("rd,vs2", SchemaCategory.Integer, List(OperandRole.Rd, OperandRole.Vs2))
  case RdVs2Vm
    extends Schema("rd,vs2,vm", SchemaCategory.Integer, List(OperandRole.Rd, OperandRole.Vs2, OperandRole.Vm))
  case VdImm
    extends Schema("vd,imm", SchemaCategory.Integer, List(OperandRole.Vd, OperandRole.Imm))
  case VdRs1
    extends Schema("vd,rs1", SchemaCategory.Integer, List(OperandRole.Vd, OperandRole.Rs1))
  case VdVm
    extends Schema("vd,vm", SchemaCategory.Integer, List(OperandRole.Vd, OperandRole.Vm))
  case VdVs1
    extends Schema("vd,vs1", SchemaCategory.Integer, List(OperandRole.Vd, OperandRole.Vs1))
  case VdVs1Vs2Vm
    extends Schema(
      "vd,vs1,vs2,vm",
      SchemaCategory.Integer,
      List(OperandRole.Vd, OperandRole.Vs1, OperandRole.Vs2, OperandRole.Vm))
  case VdVs2
    extends Schema("vd,vs2", SchemaCategory.Integer, List(OperandRole.Vd, OperandRole.Vs2))
  case VdVs2Imm
    extends Schema("vd,vs2,imm", SchemaCategory.Integer, List(OperandRole.Vd, OperandRole.Vs2, OperandRole.Imm))
  case VdVs2ImmV0
    extends Schema(
      "vd,vs2,imm,v0",
      SchemaCategory.Integer,
      List(OperandRole.Vd, OperandRole.Vs2, OperandRole.Imm, OperandRole.V0))
  case VdVs2ImmVm
    extends Schema(
      "vd,vs2,imm,vm",
      SchemaCategory.Integer,
      List(OperandRole.Vd, OperandRole.Vs2, OperandRole.Imm, OperandRole.Vm))
  case VdVs2Rs1
    extends Schema(
      "vd,vs2,rs1",
      SchemaCategory.Integer,
      List(OperandRole.Vd, OperandRole.Vs2, OperandRole.Rs1))
  case VdVs2Rs1V0
    extends Schema(
      "vd,vs2,rs1,v0",
      SchemaCategory.Integer,
      List(OperandRole.Vd, OperandRole.Vs2, OperandRole.Rs1, OperandRole.V0))
  case VdVs2Rs1Vm
    extends Schema(
      "vd,vs2,rs1,vm",
      SchemaCategory.Integer,
      List(OperandRole.Vd, OperandRole.Vs2, OperandRole.Rs1, OperandRole.Vm))
  case VdVs2Uimm
    extends Schema(
      "vd,vs2,uimm",
      SchemaCategory.Integer,
      List(OperandRole.Vd, OperandRole.Vs2, OperandRole.Uimm))
  case VdVs2UimmVm
    extends Schema(
      "vd,vs2,uimm,vm",
      SchemaCategory.Integer,
      List(OperandRole.Vd, OperandRole.Vs2, OperandRole.Uimm, OperandRole.Vm))
  case VdVs2Vm
    extends Schema("vd,vs2,vm", SchemaCategory.Integer, List(OperandRole.Vd, OperandRole.Vs2, OperandRole.Vm))
  case VdVs2VmP2
    extends Schema(
      "vd,vs2,vm/2",
      SchemaCategory.Integer,
      List(OperandRole.Vd, OperandRole.Vs2, OperandRole.VmGroup2))
  case VdVs2VmP3
    extends Schema(
      "vd,vs2,vm/3",
      SchemaCategory.Integer,
      List(OperandRole.Vd, OperandRole.Vs2, OperandRole.VmGroup3))
  case VdVs2Vs1
    extends Schema(
      "vd,vs2,vs1",
      SchemaCategory.Integer,
      List(OperandRole.Vd, OperandRole.Vs2, OperandRole.Vs1))
  case VdVs2Vs1V0
    extends Schema(
      "vd,vs2,vs1,v0",
      SchemaCategory.Integer,
      List(OperandRole.Vd, OperandRole.Vs2, OperandRole.Vs1, OperandRole.V0))
  case VdVs2Vs1Vm
    extends Schema(
      "vd,vs2,vs1,vm",
      SchemaCategory.Integer,
      List(OperandRole.Vd, OperandRole.Vs2, OperandRole.Vs1, OperandRole.Vm))
  case VdRs1Vs2Vm
    extends Schema(
      "vd,rs1,vs2,vm",
      SchemaCategory.Integer,
      List(OperandRole.Vd, OperandRole.Rs1, OperandRole.Vs2, OperandRole.Vm))

object Schema:
  val all: List[Schema] = Schema.values.toList

  def byFormatString(s: String): Option[Schema] = all.find(_.formatString == s)

  def ofCategory(c: SchemaCategory): List[Schema] = all.filter(_.category == c)

  def lookup(formatString: String): Either[String, Schema] =
    byFormatString(formatString) match
      case Some(s) => Right(s)
      case None    => Left(s"unknown RVV schema format: $formatString")
