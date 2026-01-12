// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.rvprobe.tests

import me.jiuyang.smtlib.default.{*, given}
import me.jiuyang.rvprobe.*
import me.jiuyang.rvprobe.constraints.*

import utest.*
import scala.collection.View.Single

object RecipeTest extends TestSuite:
  val tests = Tests:
    test("SingleInstruction"):
      object SingleInstruction extends RVGenerator with HasRVProbeTest:
        val sets          = Seq(isRV64I())
        def constraints() =
          instruction(0, isAddw()) {
            rdRange(1, 32) & rs1Range(1, 32) & rs2Range(1, 32)
          }

      SingleInstruction.rvprobeTestOpcodeMLIR(
        """
          |"builtin.module"() ({
          |  "func.func"() ({
          |    "smt.solver"() ({
          |      "smt.set_logic"() <{logic = "QF_LIA"}> : () -> ()
          |      %0 = "smt.declare_fun"() <{namePrefix = "rv64_i"}> : () -> !smt.bool
          |      %1 = "smt.declare_fun"() <{namePrefix = "rv32_c"}> : () -> !smt.bool
          |      %2 = "smt.declare_fun"() <{namePrefix = "rv32_c_f"}> : () -> !smt.bool
          |      %3 = "smt.declare_fun"() <{namePrefix = "rv32_d_zfa"}> : () -> !smt.bool
          |      %4 = "smt.declare_fun"() <{namePrefix = "rv32_i"}> : () -> !smt.bool
          |      %5 = "smt.declare_fun"() <{namePrefix = "rv32_zbb"}> : () -> !smt.bool
          |      %6 = "smt.declare_fun"() <{namePrefix = "rv32_zbkb"}> : () -> !smt.bool
          |      %7 = "smt.declare_fun"() <{namePrefix = "rv32_zbs"}> : () -> !smt.bool
          |      %8 = "smt.declare_fun"() <{namePrefix = "rv32_zicntr"}> : () -> !smt.bool
          |      %9 = "smt.declare_fun"() <{namePrefix = "rv32_zk"}> : () -> !smt.bool
          |      %10 = "smt.declare_fun"() <{namePrefix = "rv32_zkn"}> : () -> !smt.bool
          |      %11 = "smt.declare_fun"() <{namePrefix = "rv32_zknd"}> : () -> !smt.bool
          |      %12 = "smt.declare_fun"() <{namePrefix = "rv32_zkne"}> : () -> !smt.bool
          |      %13 = "smt.declare_fun"() <{namePrefix = "rv32_zknh"}> : () -> !smt.bool
          |      %14 = "smt.declare_fun"() <{namePrefix = "rv32_zks"}> : () -> !smt.bool
          |      %15 = "smt.declare_fun"() <{namePrefix = "rv64_a"}> : () -> !smt.bool
          |      %16 = "smt.declare_fun"() <{namePrefix = "rv64_c"}> : () -> !smt.bool
          |      %17 = "smt.declare_fun"() <{namePrefix = "rv64_d"}> : () -> !smt.bool
          |      %18 = "smt.declare_fun"() <{namePrefix = "rv64_f"}> : () -> !smt.bool
          |      %19 = "smt.declare_fun"() <{namePrefix = "rv64_h"}> : () -> !smt.bool
          |      %20 = "smt.declare_fun"() <{namePrefix = "rv64_m"}> : () -> !smt.bool
          |      %21 = "smt.declare_fun"() <{namePrefix = "rv64_q"}> : () -> !smt.bool
          |      %22 = "smt.declare_fun"() <{namePrefix = "rv64_q_zfa"}> : () -> !smt.bool
          |      %23 = "smt.declare_fun"() <{namePrefix = "rv64_zacas"}> : () -> !smt.bool
          |      %24 = "smt.declare_fun"() <{namePrefix = "rv64_zba"}> : () -> !smt.bool
          |      %25 = "smt.declare_fun"() <{namePrefix = "rv64_zbb"}> : () -> !smt.bool
          |      %26 = "smt.declare_fun"() <{namePrefix = "rv64_zbkb"}> : () -> !smt.bool
          |      %27 = "smt.declare_fun"() <{namePrefix = "rv64_zbp"}> : () -> !smt.bool
          |      %28 = "smt.declare_fun"() <{namePrefix = "rv64_zbs"}> : () -> !smt.bool
          |      %29 = "smt.declare_fun"() <{namePrefix = "rv64_zcb"}> : () -> !smt.bool
          |      %30 = "smt.declare_fun"() <{namePrefix = "rv64_zfh"}> : () -> !smt.bool
          |      %31 = "smt.declare_fun"() <{namePrefix = "rv64_zk"}> : () -> !smt.bool
          |      %32 = "smt.declare_fun"() <{namePrefix = "rv64_zkn"}> : () -> !smt.bool
          |      %33 = "smt.declare_fun"() <{namePrefix = "rv64_zknd"}> : () -> !smt.bool
          |      %34 = "smt.declare_fun"() <{namePrefix = "rv64_zkne"}> : () -> !smt.bool
          |      %35 = "smt.declare_fun"() <{namePrefix = "rv64_zknh"}> : () -> !smt.bool
          |      %36 = "smt.declare_fun"() <{namePrefix = "rv64_zks"}> : () -> !smt.bool
          |      %37 = "smt.declare_fun"() <{namePrefix = "rv_a"}> : () -> !smt.bool
          |      %38 = "smt.declare_fun"() <{namePrefix = "rv_c"}> : () -> !smt.bool
          |      %39 = "smt.declare_fun"() <{namePrefix = "rv_c_d"}> : () -> !smt.bool
          |      %40 = "smt.declare_fun"() <{namePrefix = "rv_c_zicfiss"}> : () -> !smt.bool
          |      %41 = "smt.declare_fun"() <{namePrefix = "rv_c_zihintntl"}> : () -> !smt.bool
          |      %42 = "smt.declare_fun"() <{namePrefix = "rv_d"}> : () -> !smt.bool
          |      %43 = "smt.declare_fun"() <{namePrefix = "rv_d_zfa"}> : () -> !smt.bool
          |      %44 = "smt.declare_fun"() <{namePrefix = "rv_d_zfh"}> : () -> !smt.bool
          |      %45 = "smt.declare_fun"() <{namePrefix = "rv_f"}> : () -> !smt.bool
          |      %46 = "smt.declare_fun"() <{namePrefix = "rv_f_zfa"}> : () -> !smt.bool
          |      %47 = "smt.declare_fun"() <{namePrefix = "rv_h"}> : () -> !smt.bool
          |      %48 = "smt.declare_fun"() <{namePrefix = "rv_i"}> : () -> !smt.bool
          |      %49 = "smt.declare_fun"() <{namePrefix = "rv_m"}> : () -> !smt.bool
          |      %50 = "smt.declare_fun"() <{namePrefix = "rv_q"}> : () -> !smt.bool
          |      %51 = "smt.declare_fun"() <{namePrefix = "rv_q_zfa"}> : () -> !smt.bool
          |      %52 = "smt.declare_fun"() <{namePrefix = "rv_q_zfh"}> : () -> !smt.bool
          |      %53 = "smt.declare_fun"() <{namePrefix = "rv_s"}> : () -> !smt.bool
          |      %54 = "smt.declare_fun"() <{namePrefix = "rv_sdext"}> : () -> !smt.bool
          |      %55 = "smt.declare_fun"() <{namePrefix = "rv_smdbltrp"}> : () -> !smt.bool
          |      %56 = "smt.declare_fun"() <{namePrefix = "rv_smrnmi"}> : () -> !smt.bool
          |      %57 = "smt.declare_fun"() <{namePrefix = "rv_svinval"}> : () -> !smt.bool
          |      %58 = "smt.declare_fun"() <{namePrefix = "rv_system"}> : () -> !smt.bool
          |      %59 = "smt.declare_fun"() <{namePrefix = "rv_v"}> : () -> !smt.bool
          |      %60 = "smt.declare_fun"() <{namePrefix = "rv_v_aliases"}> : () -> !smt.bool
          |      %61 = "smt.declare_fun"() <{namePrefix = "rv_zabha"}> : () -> !smt.bool
          |      %62 = "smt.declare_fun"() <{namePrefix = "rv_zacas"}> : () -> !smt.bool
          |      %63 = "smt.declare_fun"() <{namePrefix = "rv_zalasr"}> : () -> !smt.bool
          |      %64 = "smt.declare_fun"() <{namePrefix = "rv_zawrs"}> : () -> !smt.bool
          |      %65 = "smt.declare_fun"() <{namePrefix = "rv_zba"}> : () -> !smt.bool
          |      %66 = "smt.declare_fun"() <{namePrefix = "rv_zbb"}> : () -> !smt.bool
          |      %67 = "smt.declare_fun"() <{namePrefix = "rv_zbc"}> : () -> !smt.bool
          |      %68 = "smt.declare_fun"() <{namePrefix = "rv_zbkb"}> : () -> !smt.bool
          |      %69 = "smt.declare_fun"() <{namePrefix = "rv_zbkc"}> : () -> !smt.bool
          |      %70 = "smt.declare_fun"() <{namePrefix = "rv_zbkx"}> : () -> !smt.bool
          |      %71 = "smt.declare_fun"() <{namePrefix = "rv_zbp"}> : () -> !smt.bool
          |      %72 = "smt.declare_fun"() <{namePrefix = "rv_zbs"}> : () -> !smt.bool
          |      %73 = "smt.declare_fun"() <{namePrefix = "rv_zcb"}> : () -> !smt.bool
          |      %74 = "smt.declare_fun"() <{namePrefix = "rv_zcmop"}> : () -> !smt.bool
          |      %75 = "smt.declare_fun"() <{namePrefix = "rv_zcmp"}> : () -> !smt.bool
          |      %76 = "smt.declare_fun"() <{namePrefix = "rv_zcmt"}> : () -> !smt.bool
          |      %77 = "smt.declare_fun"() <{namePrefix = "rv_zfbfmin"}> : () -> !smt.bool
          |      %78 = "smt.declare_fun"() <{namePrefix = "rv_zfh"}> : () -> !smt.bool
          |      %79 = "smt.declare_fun"() <{namePrefix = "rv_zfh_zfa"}> : () -> !smt.bool
          |      %80 = "smt.declare_fun"() <{namePrefix = "rv_zicbo"}> : () -> !smt.bool
          |      %81 = "smt.declare_fun"() <{namePrefix = "rv_zicfilp"}> : () -> !smt.bool
          |      %82 = "smt.declare_fun"() <{namePrefix = "rv_zicfiss"}> : () -> !smt.bool
          |      %83 = "smt.declare_fun"() <{namePrefix = "rv_zicntr"}> : () -> !smt.bool
          |      %84 = "smt.declare_fun"() <{namePrefix = "rv_zicond"}> : () -> !smt.bool
          |      %85 = "smt.declare_fun"() <{namePrefix = "rv_zicsr"}> : () -> !smt.bool
          |      %86 = "smt.declare_fun"() <{namePrefix = "rv_zifencei"}> : () -> !smt.bool
          |      %87 = "smt.declare_fun"() <{namePrefix = "rv_zihintntl"}> : () -> !smt.bool
          |      %88 = "smt.declare_fun"() <{namePrefix = "rv_zimop"}> : () -> !smt.bool
          |      %89 = "smt.declare_fun"() <{namePrefix = "rv_zk"}> : () -> !smt.bool
          |      %90 = "smt.declare_fun"() <{namePrefix = "rv_zkn"}> : () -> !smt.bool
          |      %91 = "smt.declare_fun"() <{namePrefix = "rv_zknh"}> : () -> !smt.bool
          |      %92 = "smt.declare_fun"() <{namePrefix = "rv_zks"}> : () -> !smt.bool
          |      %93 = "smt.declare_fun"() <{namePrefix = "rv_zksed"}> : () -> !smt.bool
          |      %94 = "smt.declare_fun"() <{namePrefix = "rv_zksh"}> : () -> !smt.bool
          |      %95 = "smt.declare_fun"() <{namePrefix = "rv_zvbb"}> : () -> !smt.bool
          |      %96 = "smt.declare_fun"() <{namePrefix = "rv_zvbc"}> : () -> !smt.bool
          |      %97 = "smt.declare_fun"() <{namePrefix = "rv_zvfbfmin"}> : () -> !smt.bool
          |      %98 = "smt.declare_fun"() <{namePrefix = "rv_zvfbfwma"}> : () -> !smt.bool
          |      %99 = "smt.declare_fun"() <{namePrefix = "rv_zvkg"}> : () -> !smt.bool
          |      %100 = "smt.declare_fun"() <{namePrefix = "rv_zvkn"}> : () -> !smt.bool
          |      %101 = "smt.declare_fun"() <{namePrefix = "rv_zvkned"}> : () -> !smt.bool
          |      %102 = "smt.declare_fun"() <{namePrefix = "rv_zvknha"}> : () -> !smt.bool
          |      %103 = "smt.declare_fun"() <{namePrefix = "rv_zvknhb"}> : () -> !smt.bool
          |      %104 = "smt.declare_fun"() <{namePrefix = "rv_zvks"}> : () -> !smt.bool
          |      %105 = "smt.declare_fun"() <{namePrefix = "rv_zvksed"}> : () -> !smt.bool
          |      %106 = "smt.declare_fun"() <{namePrefix = "rv_zvksh"}> : () -> !smt.bool
          |      %107 = "smt.and"(%0) : (!smt.bool) -> !smt.bool
          |      "smt.assert"(%107) : (!smt.bool) -> ()
          |      %108 = "smt.or"(%1, %2, %3, %4, %5, %6, %7, %8, %9, %10, %11, %12, %13, %14, %15, %16, %17, %18, %19, %20, %21, %22, %23, %24, %25, %26, %27, %28, %29, %30, %31, %32, %33, %34, %35, %36, %37, %38, %39, %40, %41, %42, %43, %44, %45, %46, %47, %48, %49, %50, %51, %52, %53, %54, %55, %56, %57, %58, %59, %60, %61, %62, %63, %64, %65, %66, %67, %68, %69, %70, %71, %72, %73, %74, %75, %76, %77, %78, %79, %80, %81, %82, %83, %84, %85, %86, %87, %88, %89, %90, %91, %92, %93, %94, %95, %96, %97, %98, %99, %100, %101, %102, %103, %104, %105, %106) : (!smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool) -> !smt.bool
          |      %109 = "smt.not"(%108) : (!smt.bool) -> !smt.bool
          |      "smt.assert"(%109) : (!smt.bool) -> ()
          |      %110 = "smt.declare_fun"() <{namePrefix = "nameId_0"}> : () -> !smt.int
          |      %111 = "smt.int.constant"() <{value = 55 : i32}> : () -> !smt.int
          |      %112 = "smt.eq"(%110, %111) : (!smt.int, !smt.int) -> !smt.bool
          |      %113 = "smt.and"(%112, %0) : (!smt.bool, !smt.bool) -> !smt.bool
          |      %114 = "smt.and"(%113) : (!smt.bool) -> !smt.bool
          |      "smt.assert"(%114) : (!smt.bool) -> ()
          |      "smt.check"() ({
          |        "smt.yield"() : () -> ()
          |      }, {
          |        "smt.yield"() : () -> ()
          |      }, {
          |        "smt.yield"() : () -> ()
          |      }) : () -> ()
          |    }) : () -> ()
          |  }) {symName = "func"} : () -> ()
          |}) : () -> ()
      """.stripMargin.split('\n').toSeq*
      )

      SingleInstruction.rvprobeTestOpcodeSMTLIB(
        """
          |(set-logic QF_LIA)
          |(declare-const rv64_i Bool)
          |(declare-const rv32_c Bool)
          |(declare-const rv32_c_f Bool)
          |(declare-const rv32_d_zfa Bool)
          |(declare-const rv32_i Bool)
          |(declare-const rv32_zbb Bool)
          |(declare-const rv32_zbkb Bool)
          |(declare-const rv32_zbs Bool)
          |(declare-const rv32_zicntr Bool)
          |(declare-const rv32_zk Bool)
          |(declare-const rv32_zkn Bool)
          |(declare-const rv32_zknd Bool)
          |(declare-const rv32_zkne Bool)
          |(declare-const rv32_zknh Bool)
          |(declare-const rv32_zks Bool)
          |(declare-const rv64_a Bool)
          |(declare-const rv64_c Bool)
          |(declare-const rv64_d Bool)
          |(declare-const rv64_f Bool)
          |(declare-const rv64_h Bool)
          |(declare-const rv64_m Bool)
          |(declare-const rv64_q Bool)
          |(declare-const rv64_q_zfa Bool)
          |(declare-const rv64_zacas Bool)
          |(declare-const rv64_zba Bool)
          |(declare-const rv64_zbb Bool)
          |(declare-const rv64_zbkb Bool)
          |(declare-const rv64_zbp Bool)
          |(declare-const rv64_zbs Bool)
          |(declare-const rv64_zcb Bool)
          |(declare-const rv64_zfh Bool)
          |(declare-const rv64_zk Bool)
          |(declare-const rv64_zkn Bool)
          |(declare-const rv64_zknd Bool)
          |(declare-const rv64_zkne Bool)
          |(declare-const rv64_zknh Bool)
          |(declare-const rv64_zks Bool)
          |(declare-const rv_a Bool)
          |(declare-const rv_c Bool)
          |(declare-const rv_c_d Bool)
          |(declare-const rv_c_zicfiss Bool)
          |(declare-const rv_c_zihintntl Bool)
          |(declare-const rv_d Bool)
          |(declare-const rv_d_zfa Bool)
          |(declare-const rv_d_zfh Bool)
          |(declare-const rv_f Bool)
          |(declare-const rv_f_zfa Bool)
          |(declare-const rv_h Bool)
          |(declare-const rv_i Bool)
          |(declare-const rv_m Bool)
          |(declare-const rv_q Bool)
          |(declare-const rv_q_zfa Bool)
          |(declare-const rv_q_zfh Bool)
          |(declare-const rv_s Bool)
          |(declare-const rv_sdext Bool)
          |(declare-const rv_smdbltrp Bool)
          |(declare-const rv_smrnmi Bool)
          |(declare-const rv_svinval Bool)
          |(declare-const rv_system Bool)
          |(declare-const rv_v Bool)
          |(declare-const rv_v_aliases Bool)
          |(declare-const rv_zabha Bool)
          |(declare-const rv_zacas Bool)
          |(declare-const rv_zalasr Bool)
          |(declare-const rv_zawrs Bool)
          |(declare-const rv_zba Bool)
          |(declare-const rv_zbb Bool)
          |(declare-const rv_zbc Bool)
          |(declare-const rv_zbkb Bool)
          |(declare-const rv_zbkc Bool)
          |(declare-const rv_zbkx Bool)
          |(declare-const rv_zbp Bool)
          |(declare-const rv_zbs Bool)
          |(declare-const rv_zcb Bool)
          |(declare-const rv_zcmop Bool)
          |(declare-const rv_zcmp Bool)
          |(declare-const rv_zcmt Bool)
          |(declare-const rv_zfbfmin Bool)
          |(declare-const rv_zfh Bool)
          |(declare-const rv_zfh_zfa Bool)
          |(declare-const rv_zicbo Bool)
          |(declare-const rv_zicfilp Bool)
          |(declare-const rv_zicfiss Bool)
          |(declare-const rv_zicntr Bool)
          |(declare-const rv_zicond Bool)
          |(declare-const rv_zicsr Bool)
          |(declare-const rv_zifencei Bool)
          |(declare-const rv_zihintntl Bool)
          |(declare-const rv_zimop Bool)
          |(declare-const rv_zk Bool)
          |(declare-const rv_zkn Bool)
          |(declare-const rv_zknh Bool)
          |(declare-const rv_zks Bool)
          |(declare-const rv_zksed Bool)
          |(declare-const rv_zksh Bool)
          |(declare-const rv_zvbb Bool)
          |(declare-const rv_zvbc Bool)
          |(declare-const rv_zvfbfmin Bool)
          |(declare-const rv_zvfbfwma Bool)
          |(declare-const rv_zvkg Bool)
          |(declare-const rv_zvkn Bool)
          |(declare-const rv_zvkned Bool)
          |(declare-const rv_zvknha Bool)
          |(declare-const rv_zvknhb Bool)
          |(declare-const rv_zvks Bool)
          |(declare-const rv_zvksed Bool)
          |(declare-const rv_zvksh Bool)
          |(assert (let ((tmp (and rv64_i)))
          |        tmp))
          |(assert (let ((tmp_0 (or rv32_c rv32_c_f rv32_d_zfa rv32_i rv32_zbb rv32_zbkb rv32_zbs rv32_zicntr rv32_zk rv32_zkn rv32_zknd rv32_zkne rv32_zknh rv32_zks rv64_a rv64_c rv64_d rv64_f rv64_h rv64_m rv64_q rv64_q_zfa rv64_zacas rv64_zba rv64_zbb rv64_zbkb rv64_zbp rv64_zbs rv64_zcb rv64_zfh rv64_zk rv64_zkn rv64_zknd rv64_zkne rv64_zknh rv64_zks rv_a rv_c rv_c_d rv_c_zicfiss rv_c_zihintntl rv_d rv_d_zfa rv_d_zfh rv_f rv_f_zfa rv_h rv_i rv_m rv_q rv_q_zfa rv_q_zfh rv_s rv_sdext rv_smdbltrp rv_smrnmi rv_svinval rv_system rv_v rv_v_aliases rv_zabha rv_zacas rv_zalasr rv_zawrs rv_zba rv_zbb rv_zbc rv_zbkb rv_zbkc rv_zbkx rv_zbp rv_zbs rv_zcb rv_zcmop rv_zcmp rv_zcmt rv_zfbfmin rv_zfh rv_zfh_zfa rv_zicbo rv_zicfilp rv_zicfiss rv_zicntr rv_zicond rv_zicsr rv_zifencei rv_zihintntl rv_zimop rv_zk rv_zkn rv_zknh rv_zks rv_zksed rv_zksh rv_zvbb rv_zvbc rv_zvfbfmin rv_zvfbfwma rv_zvkg rv_zvkn rv_zvkned rv_zvknha rv_zvknhb rv_zvks rv_zvksed rv_zvksh)))
          |        (let ((tmp_1 (not tmp_0)))
          |        tmp_1)))
          |(declare-const nameId_0 Int)
          |(assert (let ((tmp_2 (= nameId_0 55)))
          |        (let ((tmp_3 (and tmp_2 rv64_i)))
          |        (let ((tmp_4 (and tmp_3)))
          |        tmp_4))))
          |(check-sat)
          |(reset)
      """.stripMargin.split('\n').toSeq*
      )

      SingleInstruction.rvprobeTestOpcodeZ3Output("sat")

      SingleInstruction.rvprobeTestArgMLIR(
        """
          |"builtin.module"() ({
          |  "func.func"() ({
          |    "smt.solver"() ({
          |      "smt.set_logic"() <{logic = "QF_LIA"}> : () -> ()
          |      %0 = "smt.declare_fun"() <{namePrefix = "nameId_0"}> : () -> !smt.int
          |      %1 = "smt.int.constant"() <{value = 55 : i32}> : () -> !smt.int
          |      %2 = "smt.eq"(%0, %1) : (!smt.int, !smt.int) -> !smt.bool
          |      "smt.assert"(%2) : (!smt.bool) -> ()
          |      %3 = "smt.declare_fun"() <{namePrefix = "rd_0"}> : () -> !smt.int
          |      %4 = "smt.int.constant"() <{value = 0 : i32}> : () -> !smt.int
          |      %5 = "smt.int.cmp"(%3, %4) <{pred = 3 : i64}> : (!smt.int, !smt.int) -> !smt.bool
          |      "smt.assert"(%5) : (!smt.bool) -> ()
          |      %6 = "smt.int.constant"() <{value = 32 : i32}> : () -> !smt.int
          |      %7 = "smt.int.cmp"(%3, %6) <{pred = 0 : i64}> : (!smt.int, !smt.int) -> !smt.bool
          |      "smt.assert"(%7) : (!smt.bool) -> ()
          |      %8 = "smt.declare_fun"() <{namePrefix = "rs1_0"}> : () -> !smt.int
          |      %9 = "smt.int.constant"() <{value = 0 : i32}> : () -> !smt.int
          |      %10 = "smt.int.cmp"(%8, %9) <{pred = 3 : i64}> : (!smt.int, !smt.int) -> !smt.bool
          |      "smt.assert"(%10) : (!smt.bool) -> ()
          |      %11 = "smt.int.constant"() <{value = 32 : i32}> : () -> !smt.int
          |      %12 = "smt.int.cmp"(%8, %11) <{pred = 0 : i64}> : (!smt.int, !smt.int) -> !smt.bool
          |      "smt.assert"(%12) : (!smt.bool) -> ()
          |      %13 = "smt.declare_fun"() <{namePrefix = "rs2_0"}> : () -> !smt.int
          |      %14 = "smt.int.constant"() <{value = 0 : i32}> : () -> !smt.int
          |      %15 = "smt.int.cmp"(%13, %14) <{pred = 3 : i64}> : (!smt.int, !smt.int) -> !smt.bool
          |      "smt.assert"(%15) : (!smt.bool) -> ()
          |      %16 = "smt.int.constant"() <{value = 32 : i32}> : () -> !smt.int
          |      %17 = "smt.int.cmp"(%13, %16) <{pred = 0 : i64}> : (!smt.int, !smt.int) -> !smt.bool
          |      "smt.assert"(%17) : (!smt.bool) -> ()
          |      %18 = "smt.int.constant"() <{value = 1 : i32}> : () -> !smt.int
          |      %19 = "smt.int.cmp"(%3, %18) <{pred = 3 : i64}> : (!smt.int, !smt.int) -> !smt.bool
          |      %20 = "smt.int.constant"() <{value = 32 : i32}> : () -> !smt.int
          |      %21 = "smt.int.cmp"(%3, %20) <{pred = 0 : i64}> : (!smt.int, !smt.int) -> !smt.bool
          |      %22 = "smt.and"(%19, %21) : (!smt.bool, !smt.bool) -> !smt.bool
          |      %23 = "smt.int.constant"() <{value = 1 : i32}> : () -> !smt.int
          |      %24 = "smt.int.cmp"(%8, %23) <{pred = 3 : i64}> : (!smt.int, !smt.int) -> !smt.bool
          |      %25 = "smt.int.constant"() <{value = 32 : i32}> : () -> !smt.int
          |      %26 = "smt.int.cmp"(%8, %25) <{pred = 0 : i64}> : (!smt.int, !smt.int) -> !smt.bool
          |      %27 = "smt.and"(%24, %26) : (!smt.bool, !smt.bool) -> !smt.bool
          |      %28 = "smt.and"(%22, %27) : (!smt.bool, !smt.bool) -> !smt.bool
          |      %29 = "smt.int.constant"() <{value = 1 : i32}> : () -> !smt.int
          |      %30 = "smt.int.cmp"(%13, %29) <{pred = 3 : i64}> : (!smt.int, !smt.int) -> !smt.bool
          |      %31 = "smt.int.constant"() <{value = 32 : i32}> : () -> !smt.int
          |      %32 = "smt.int.cmp"(%13, %31) <{pred = 0 : i64}> : (!smt.int, !smt.int) -> !smt.bool
          |      %33 = "smt.and"(%30, %32) : (!smt.bool, !smt.bool) -> !smt.bool
          |      %34 = "smt.and"(%28, %33) : (!smt.bool, !smt.bool) -> !smt.bool
          |      %35 = "smt.and"(%34) : (!smt.bool) -> !smt.bool
          |      "smt.assert"(%35) : (!smt.bool) -> ()
          |      "smt.check"() ({
          |        "smt.yield"() : () -> ()
          |      }, {
          |        "smt.yield"() : () -> ()
          |      }, {
          |        "smt.yield"() : () -> ()
          |      }) : () -> ()
          |    }) : () -> ()
          |  }) {symName = "func"} : () -> ()
          |}) : () -> ()
      """.stripMargin.split('\n').toSeq*
      )

      SingleInstruction.rvprobeTestArgSMTLIB(
        """
          |; solver scope 0
          |(set-logic QF_LIA)
          |(declare-const nameId_0 Int)
          |(assert (let ((tmp (= nameId_0 55)))
          |        tmp))
          |(declare-const rd_0 Int)
          |(assert (let ((tmp_0 (>= rd_0 0)))
          |        tmp_0))
          |(assert (let ((tmp_1 (< rd_0 32)))
          |        tmp_1))
          |(declare-const rs1_0 Int)
          |(assert (let ((tmp_2 (>= rs1_0 0)))
          |        tmp_2))
          |(assert (let ((tmp_3 (< rs1_0 32)))
          |        tmp_3))
          |(declare-const rs2_0 Int)
          |(assert (let ((tmp_4 (>= rs2_0 0)))
          |        tmp_4))
          |(assert (let ((tmp_5 (< rs2_0 32)))
          |        tmp_5))
          |(assert (let ((tmp_6 (< rs2_0 32)))
          |        (let ((tmp_7 (>= rs2_0 1)))
          |        (let ((tmp_8 (and tmp_7 tmp_6)))
          |        (let ((tmp_9 (< rs1_0 32)))
          |        (let ((tmp_10 (>= rs1_0 1)))
          |        (let ((tmp_11 (and tmp_10 tmp_9)))
          |        (let ((tmp_12 (< rd_0 32)))
          |        (let ((tmp_13 (>= rd_0 1)))
          |        (let ((tmp_14 (and tmp_13 tmp_12)))
          |        (let ((tmp_15 (and tmp_14 tmp_11)))
          |        (let ((tmp_16 (and tmp_15 tmp_8)))
          |        (let ((tmp_17 (and tmp_16)))
          |        tmp_17)))))))))))))
          |(check-sat)
          |(reset)
      """.stripMargin.split('\n').toSeq*
      )

      SingleInstruction.rvprobeTestArgZ3Output("sat")

      SingleInstruction.rvprobeTestInstructions("0: addw x1 x1 x1")

    test("MultiInstructions"):
      object MultiInstructions extends RVGenerator with HasRVProbeTest:
        val sets          = isRV64GC()
        def constraints() =
          (0 until 50).foreach { i =>
            instruction(i, isAddw()) {
              rdRange(1, 32) & rs1Range(1, 32) & rs2Range(1, 32)
            }
          }
      MultiInstructions.rvprobeTestInstructions("""
                                                  |0: addw x1 x1 x1
                                                  |1: addw x1 x1 x1
                                                  |2: addw x1 x1 x1
                                                  |3: addw x1 x1 x1
                                                  |4: addw x1 x1 x1
                                                  |5: addw x1 x1 x1
                                                  |6: addw x1 x1 x1
                                                  |7: addw x1 x1 x1
                                                  |8: addw x1 x1 x1
                                                  |9: addw x1 x1 x1
                                                  |10: addw x1 x1 x1
                                                  |11: addw x1 x1 x1
                                                  |12: addw x1 x1 x1
                                                  |13: addw x1 x1 x1
                                                  |14: addw x1 x1 x1
                                                  |15: addw x1 x1 x1
                                                  |16: addw x1 x1 x1
                                                  |17: addw x1 x1 x1
                                                  |18: addw x1 x1 x1
                                                  |19: addw x1 x1 x1
                                                  |20: addw x1 x1 x1
                                                  |21: addw x1 x1 x1
                                                  |22: addw x1 x1 x1
                                                  |23: addw x1 x1 x1
                                                  |24: addw x1 x1 x1
                                                  |25: addw x1 x1 x1
                                                  |26: addw x1 x1 x1
                                                  |27: addw x1 x1 x1
                                                  |28: addw x1 x1 x1
                                                  |29: addw x1 x1 x1
                                                  |30: addw x1 x1 x1
                                                  |31: addw x1 x1 x1
                                                  |32: addw x1 x1 x1
                                                  |33: addw x1 x1 x1
                                                  |34: addw x1 x1 x1
                                                  |35: addw x1 x1 x1
                                                  |36: addw x1 x1 x1
                                                  |37: addw x1 x1 x1
                                                  |38: addw x1 x1 x1
                                                  |39: addw x1 x1 x1
                                                  |40: addw x1 x1 x1
                                                  |41: addw x1 x1 x1
                                                  |42: addw x1 x1 x1
                                                  |43: addw x1 x1 x1
                                                  |44: addw x1 x1 x1
                                                  |45: addw x1 x1 x1
                                                  |46: addw x1 x1 x1
                                                  |47: addw x1 x1 x1
                                                  |48: addw x1 x1 x1
                                                  |49: addw x1 x1 x1
                                                  |""".stripMargin.split('\n').toSeq*)

    test("ComplexRecipe"):
      object ComplexRecipe extends RVGenerator with HasRVProbeTest:
        val sets          = isRV64GC()
        def constraints() =
          val instructionCount = 50
          (0 until instructionCount).foreach { i =>
            instruction(i, isAddw()) {
              rdRange(1, 32) & rs1Range(1, 32) & rs2Range(1, 32)
            }
          }

          sequence(0, instructionCount).coverBins(_.rd, (1 until 32).map(i => i.S))
          sequence(0, instructionCount).coverBins(_.rs1, (1 until 32).map(i => i.S))
          sequence(0, instructionCount).coverBins(_.rs2, (1 until 32).map(i => i.S))

          coverSigns(instructionCount, isAddw(), true, true, true)
      ComplexRecipe.rvprobeTestInstructions("""
                                              |0: addw x30 x2 x7
                                              |1: addw x9 x31 x1
                                              |2: addw x5 x4 x1
                                              |3: addw x17 x5 x1
                                              |4: addw x1 x14 x1
                                              |5: addw x20 x1 x1
                                              |6: addw x10 x1 x19
                                              |7: addw x25 x1 x1
                                              |8: addw x3 x1 x1
                                              |9: addw x22 x1 x1
                                              |10: addw x13 x18 x1
                                              |11: addw x19 x26 x5
                                              |12: addw x1 x1 x1
                                              |13: addw x1 x1 x1
                                              |14: addw x1 x12 x1
                                              |15: addw x1 x25 x8
                                              |16: addw x1 x1 x1
                                              |17: addw x26 x1 x10
                                              |18: addw x1 x30 x4
                                              |19: addw x31 x6 x1
                                              |20: addw x28 x17 x1
                                              |21: addw x29 x1 x1
                                              |22: addw x12 x1 x1
                                              |23: addw x14 x1 x1
                                              |24: addw x1 x1 x2
                                              |25: addw x8 x15 x1
                                              |26: addw x15 x1 x3
                                              |27: addw x1 x21 x6
                                              |28: addw x1 x1 x9
                                              |29: addw x4 x27 x11
                                              |30: addw x1 x3 x12
                                              |31: addw x1 x24 x13
                                              |32: addw x24 x13 x14
                                              |33: addw x1 x1 x15
                                              |34: addw x1 x1 x16
                                              |35: addw x27 x1 x17
                                              |36: addw x1 x1 x18
                                              |37: addw x16 x22 x1
                                              |38: addw x2 x1 x20
                                              |39: addw x1 x20 x21
                                              |40: addw x11 x16 x22
                                              |41: addw x21 x28 x23
                                              |42: addw x1 x19 x24
                                              |43: addw x1 x7 x25
                                              |44: addw x23 x11 x26
                                              |45: addw x18 x8 x27
                                              |46: addw x6 x9 x28
                                              |47: addw x1 x10 x29
                                              |48: addw x1 x23 x30
                                              |49: addw x7 x29 x31
                                              |50: addi x1 x31 -2048
                                              |51: addi x2 x31 2047
                                              |52: addw x3 x1 x2
                                              |53: addw x3 x2 x1
                                              |""".stripMargin.split('\n').toSeq*)
