// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.rvprobe.tests

import me.jiuyang.smtlib.default.{*, given}
import me.jiuyang.rvprobe.*
import me.jiuyang.rvprobe.constraints.*

import utest.*

object RecipeTest extends TestSuite:
  val tests = Tests:
    test("SingleInstruction"):
      object SingleInstruction extends RVGenerator with HasRVProbeTest:
        val sets          = Seq(isRV64I())
        def constraints() =
          instruction(0) {
            isAddw() & rdRange(1, 32) & rs1Range(1, 32) & rs2Range(1, 32)
          }

      SingleInstruction.rvprobeTestMLIR(
        """
          |"builtin.module"() ({
          |"func.func"() ({
          |"smt.solver"() ({
          |"smt.set_logic"() <{logic = "QF_LIA"}> : () -> ()
          |%0 = "smt.declare_fun"() <{namePrefix = "rv32_c"}> : () -> !smt.bool
          |%1 = "smt.declare_fun"() <{namePrefix = "rv32_c_f"}> : () -> !smt.bool
          |%2 = "smt.declare_fun"() <{namePrefix = "rv32_d_zfa"}> : () -> !smt.bool
          |%3 = "smt.declare_fun"() <{namePrefix = "rv32_i"}> : () -> !smt.bool
          |%4 = "smt.declare_fun"() <{namePrefix = "rv32_zbb"}> : () -> !smt.bool
          |%5 = "smt.declare_fun"() <{namePrefix = "rv32_zbkb"}> : () -> !smt.bool
          |%6 = "smt.declare_fun"() <{namePrefix = "rv32_zbs"}> : () -> !smt.bool
          |%7 = "smt.declare_fun"() <{namePrefix = "rv32_zicntr"}> : () -> !smt.bool
          |%8 = "smt.declare_fun"() <{namePrefix = "rv32_zk"}> : () -> !smt.bool
          |%9 = "smt.declare_fun"() <{namePrefix = "rv32_zkn"}> : () -> !smt.bool
          |%10 = "smt.declare_fun"() <{namePrefix = "rv32_zknd"}> : () -> !smt.bool
          |%11 = "smt.declare_fun"() <{namePrefix = "rv32_zkne"}> : () -> !smt.bool
          |%12 = "smt.declare_fun"() <{namePrefix = "rv32_zknh"}> : () -> !smt.bool
          |%13 = "smt.declare_fun"() <{namePrefix = "rv32_zks"}> : () -> !smt.bool
          |%14 = "smt.declare_fun"() <{namePrefix = "rv64_a"}> : () -> !smt.bool
          |%15 = "smt.declare_fun"() <{namePrefix = "rv64_c"}> : () -> !smt.bool
          |%16 = "smt.declare_fun"() <{namePrefix = "rv64_d"}> : () -> !smt.bool
          |%17 = "smt.declare_fun"() <{namePrefix = "rv64_f"}> : () -> !smt.bool
          |%18 = "smt.declare_fun"() <{namePrefix = "rv64_h"}> : () -> !smt.bool
          |%19 = "smt.declare_fun"() <{namePrefix = "rv64_i"}> : () -> !smt.bool
          |%20 = "smt.declare_fun"() <{namePrefix = "rv64_m"}> : () -> !smt.bool
          |%21 = "smt.declare_fun"() <{namePrefix = "rv64_q"}> : () -> !smt.bool
          |%22 = "smt.declare_fun"() <{namePrefix = "rv64_q_zfa"}> : () -> !smt.bool
          |%23 = "smt.declare_fun"() <{namePrefix = "rv64_zacas"}> : () -> !smt.bool
          |%24 = "smt.declare_fun"() <{namePrefix = "rv64_zba"}> : () -> !smt.bool
          |%25 = "smt.declare_fun"() <{namePrefix = "rv64_zbb"}> : () -> !smt.bool
          |%26 = "smt.declare_fun"() <{namePrefix = "rv64_zbkb"}> : () -> !smt.bool
          |%27 = "smt.declare_fun"() <{namePrefix = "rv64_zbp"}> : () -> !smt.bool
          |%28 = "smt.declare_fun"() <{namePrefix = "rv64_zbs"}> : () -> !smt.bool
          |%29 = "smt.declare_fun"() <{namePrefix = "rv64_zcb"}> : () -> !smt.bool
          |%30 = "smt.declare_fun"() <{namePrefix = "rv64_zfh"}> : () -> !smt.bool
          |%31 = "smt.declare_fun"() <{namePrefix = "rv64_zk"}> : () -> !smt.bool
          |%32 = "smt.declare_fun"() <{namePrefix = "rv64_zkn"}> : () -> !smt.bool
          |%33 = "smt.declare_fun"() <{namePrefix = "rv64_zknd"}> : () -> !smt.bool
          |%34 = "smt.declare_fun"() <{namePrefix = "rv64_zkne"}> : () -> !smt.bool
          |%35 = "smt.declare_fun"() <{namePrefix = "rv64_zknh"}> : () -> !smt.bool
          |%36 = "smt.declare_fun"() <{namePrefix = "rv64_zks"}> : () -> !smt.bool
          |%37 = "smt.declare_fun"() <{namePrefix = "rv_a"}> : () -> !smt.bool
          |%38 = "smt.declare_fun"() <{namePrefix = "rv_c"}> : () -> !smt.bool
          |%39 = "smt.declare_fun"() <{namePrefix = "rv_c_d"}> : () -> !smt.bool
          |%40 = "smt.declare_fun"() <{namePrefix = "rv_c_zicfiss"}> : () -> !smt.bool
          |%41 = "smt.declare_fun"() <{namePrefix = "rv_c_zihintntl"}> : () -> !smt.bool
          |%42 = "smt.declare_fun"() <{namePrefix = "rv_d"}> : () -> !smt.bool
          |%43 = "smt.declare_fun"() <{namePrefix = "rv_d_zfa"}> : () -> !smt.bool
          |%44 = "smt.declare_fun"() <{namePrefix = "rv_d_zfh"}> : () -> !smt.bool
          |%45 = "smt.declare_fun"() <{namePrefix = "rv_f"}> : () -> !smt.bool
          |%46 = "smt.declare_fun"() <{namePrefix = "rv_f_zfa"}> : () -> !smt.bool
          |%47 = "smt.declare_fun"() <{namePrefix = "rv_h"}> : () -> !smt.bool
          |%48 = "smt.declare_fun"() <{namePrefix = "rv_i"}> : () -> !smt.bool
          |%49 = "smt.declare_fun"() <{namePrefix = "rv_m"}> : () -> !smt.bool
          |%50 = "smt.declare_fun"() <{namePrefix = "rv_q"}> : () -> !smt.bool
          |%51 = "smt.declare_fun"() <{namePrefix = "rv_q_zfa"}> : () -> !smt.bool
          |%52 = "smt.declare_fun"() <{namePrefix = "rv_q_zfh"}> : () -> !smt.bool
          |%53 = "smt.declare_fun"() <{namePrefix = "rv_s"}> : () -> !smt.bool
          |%54 = "smt.declare_fun"() <{namePrefix = "rv_sdext"}> : () -> !smt.bool
          |%55 = "smt.declare_fun"() <{namePrefix = "rv_smdbltrp"}> : () -> !smt.bool
          |%56 = "smt.declare_fun"() <{namePrefix = "rv_smrnmi"}> : () -> !smt.bool
          |%57 = "smt.declare_fun"() <{namePrefix = "rv_svinval"}> : () -> !smt.bool
          |%58 = "smt.declare_fun"() <{namePrefix = "rv_system"}> : () -> !smt.bool
          |%59 = "smt.declare_fun"() <{namePrefix = "rv_v"}> : () -> !smt.bool
          |%60 = "smt.declare_fun"() <{namePrefix = "rv_v_aliases"}> : () -> !smt.bool
          |%61 = "smt.declare_fun"() <{namePrefix = "rv_zabha"}> : () -> !smt.bool
          |%62 = "smt.declare_fun"() <{namePrefix = "rv_zacas"}> : () -> !smt.bool
          |%63 = "smt.declare_fun"() <{namePrefix = "rv_zalasr"}> : () -> !smt.bool
          |%64 = "smt.declare_fun"() <{namePrefix = "rv_zawrs"}> : () -> !smt.bool
          |%65 = "smt.declare_fun"() <{namePrefix = "rv_zba"}> : () -> !smt.bool
          |%66 = "smt.declare_fun"() <{namePrefix = "rv_zbb"}> : () -> !smt.bool
          |%67 = "smt.declare_fun"() <{namePrefix = "rv_zbc"}> : () -> !smt.bool
          |%68 = "smt.declare_fun"() <{namePrefix = "rv_zbkb"}> : () -> !smt.bool
          |%69 = "smt.declare_fun"() <{namePrefix = "rv_zbkc"}> : () -> !smt.bool
          |%70 = "smt.declare_fun"() <{namePrefix = "rv_zbkx"}> : () -> !smt.bool
          |%71 = "smt.declare_fun"() <{namePrefix = "rv_zbp"}> : () -> !smt.bool
          |%72 = "smt.declare_fun"() <{namePrefix = "rv_zbs"}> : () -> !smt.bool
          |%73 = "smt.declare_fun"() <{namePrefix = "rv_zcb"}> : () -> !smt.bool
          |%74 = "smt.declare_fun"() <{namePrefix = "rv_zcmop"}> : () -> !smt.bool
          |%75 = "smt.declare_fun"() <{namePrefix = "rv_zcmp"}> : () -> !smt.bool
          |%76 = "smt.declare_fun"() <{namePrefix = "rv_zcmt"}> : () -> !smt.bool
          |%77 = "smt.declare_fun"() <{namePrefix = "rv_zfbfmin"}> : () -> !smt.bool
          |%78 = "smt.declare_fun"() <{namePrefix = "rv_zfh"}> : () -> !smt.bool
          |%79 = "smt.declare_fun"() <{namePrefix = "rv_zfh_zfa"}> : () -> !smt.bool
          |%80 = "smt.declare_fun"() <{namePrefix = "rv_zicbo"}> : () -> !smt.bool
          |%81 = "smt.declare_fun"() <{namePrefix = "rv_zicfilp"}> : () -> !smt.bool
          |%82 = "smt.declare_fun"() <{namePrefix = "rv_zicfiss"}> : () -> !smt.bool
          |%83 = "smt.declare_fun"() <{namePrefix = "rv_zicntr"}> : () -> !smt.bool
          |%84 = "smt.declare_fun"() <{namePrefix = "rv_zicond"}> : () -> !smt.bool
          |%85 = "smt.declare_fun"() <{namePrefix = "rv_zicsr"}> : () -> !smt.bool
          |%86 = "smt.declare_fun"() <{namePrefix = "rv_zifencei"}> : () -> !smt.bool
          |%87 = "smt.declare_fun"() <{namePrefix = "rv_zihintntl"}> : () -> !smt.bool
          |%88 = "smt.declare_fun"() <{namePrefix = "rv_zimop"}> : () -> !smt.bool
          |%89 = "smt.declare_fun"() <{namePrefix = "rv_zk"}> : () -> !smt.bool
          |%90 = "smt.declare_fun"() <{namePrefix = "rv_zkn"}> : () -> !smt.bool
          |%91 = "smt.declare_fun"() <{namePrefix = "rv_zknh"}> : () -> !smt.bool
          |%92 = "smt.declare_fun"() <{namePrefix = "rv_zks"}> : () -> !smt.bool
          |%93 = "smt.declare_fun"() <{namePrefix = "rv_zksed"}> : () -> !smt.bool
          |%94 = "smt.declare_fun"() <{namePrefix = "rv_zksh"}> : () -> !smt.bool
          |%95 = "smt.declare_fun"() <{namePrefix = "rv_zvbb"}> : () -> !smt.bool
          |%96 = "smt.declare_fun"() <{namePrefix = "rv_zvbc"}> : () -> !smt.bool
          |%97 = "smt.declare_fun"() <{namePrefix = "rv_zvfbfmin"}> : () -> !smt.bool
          |%98 = "smt.declare_fun"() <{namePrefix = "rv_zvfbfwma"}> : () -> !smt.bool
          |%99 = "smt.declare_fun"() <{namePrefix = "rv_zvkg"}> : () -> !smt.bool
          |%100 = "smt.declare_fun"() <{namePrefix = "rv_zvkn"}> : () -> !smt.bool
          |%101 = "smt.declare_fun"() <{namePrefix = "rv_zvkned"}> : () -> !smt.bool
          |%102 = "smt.declare_fun"() <{namePrefix = "rv_zvknha"}> : () -> !smt.bool
          |%103 = "smt.declare_fun"() <{namePrefix = "rv_zvknhb"}> : () -> !smt.bool
          |%104 = "smt.declare_fun"() <{namePrefix = "rv_zvks"}> : () -> !smt.bool
          |%105 = "smt.declare_fun"() <{namePrefix = "rv_zvksed"}> : () -> !smt.bool
          |%106 = "smt.declare_fun"() <{namePrefix = "rv_zvksh"}> : () -> !smt.bool
          |%107 = "smt.and"(%19) : (!smt.bool) -> !smt.bool
          |"smt.assert"(%107) : (!smt.bool) -> ()
          |%108 = "smt.or"(%0, %1, %2, %3, %4, %5, %6, %7, %8, %9, %10, %11, %12, %13, %14, %15, %16, %17, %18, %20, %21, %22, %23, %24, %25, %26, %27, %28, %29, %30, %31, %32, %33, %34, %35, %36, %37, %38, %39, %40, %41, %42, %43, %44, %45, %46, %47, %48, %49, %50, %51, %52, %53, %54, %55, %56, %57, %58, %59, %60, %61, %62, %63, %64, %65, %66, %67, %68, %69, %70, %71, %72, %73, %74, %75, %76, %77, %78, %79, %80, %81, %82, %83, %84, %85, %86, %87, %88, %89, %90, %91, %92, %93, %94, %95, %96, %97, %98, %99, %100, %101, %102, %103, %104, %105, %106) : (!smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool, !smt.bool) -> !smt.bool
          |%109 = "smt.not"(%108) : (!smt.bool) -> !smt.bool
          |"smt.assert"(%109) : (!smt.bool) -> ()
          |%110 = "smt.declare_fun"() <{namePrefix = "nameId_0"}> : () -> !smt.int
          |%111 = "smt.declare_fun"() <{namePrefix = "amoop_0"}> : () -> !smt.int
          |%112 = "smt.declare_fun"() <{namePrefix = "aq_0"}> : () -> !smt.int
          |%113 = "smt.declare_fun"() <{namePrefix = "aqrl_0"}> : () -> !smt.int
          |%114 = "smt.declare_fun"() <{namePrefix = "bimm12hi_0"}> : () -> !smt.int
          |%115 = "smt.declare_fun"() <{namePrefix = "bimm12lo_0"}> : () -> !smt.int
          |%116 = "smt.declare_fun"() <{namePrefix = "bs_0"}> : () -> !smt.int
          |%117 = "smt.declare_fun"() <{namePrefix = "c_bimm9hi_0"}> : () -> !smt.int
          |%118 = "smt.declare_fun"() <{namePrefix = "c_bimm9lo_0"}> : () -> !smt.int
          |%119 = "smt.declare_fun"() <{namePrefix = "c_imm12_0"}> : () -> !smt.int
          |%120 = "smt.declare_fun"() <{namePrefix = "c_imm6hi_0"}> : () -> !smt.int
          |%121 = "smt.declare_fun"() <{namePrefix = "c_imm6lo_0"}> : () -> !smt.int
          |%122 = "smt.declare_fun"() <{namePrefix = "c_index_0"}> : () -> !smt.int
          |%123 = "smt.declare_fun"() <{namePrefix = "c_nzimm10hi_0"}> : () -> !smt.int
          |%124 = "smt.declare_fun"() <{namePrefix = "c_nzimm10lo_0"}> : () -> !smt.int
          |%125 = "smt.declare_fun"() <{namePrefix = "c_nzimm18hi_0"}> : () -> !smt.int
          |%126 = "smt.declare_fun"() <{namePrefix = "c_nzimm18lo_0"}> : () -> !smt.int
          |%127 = "smt.declare_fun"() <{namePrefix = "c_nzimm6hi_0"}> : () -> !smt.int
          |%128 = "smt.declare_fun"() <{namePrefix = "c_nzimm6lo_0"}> : () -> !smt.int
          |%129 = "smt.declare_fun"() <{namePrefix = "c_nzuimm10_0"}> : () -> !smt.int
          |%130 = "smt.declare_fun"() <{namePrefix = "c_nzuimm5_0"}> : () -> !smt.int
          |%131 = "smt.declare_fun"() <{namePrefix = "c_nzuimm6hi_0"}> : () -> !smt.int
          |%132 = "smt.declare_fun"() <{namePrefix = "c_nzuimm6lo_0"}> : () -> !smt.int
          |%133 = "smt.declare_fun"() <{namePrefix = "c_rlist_0"}> : () -> !smt.int
          |%134 = "smt.declare_fun"() <{namePrefix = "c_rs1_n0_0"}> : () -> !smt.int
          |%135 = "smt.declare_fun"() <{namePrefix = "c_rs2_0"}> : () -> !smt.int
          |%136 = "smt.declare_fun"() <{namePrefix = "c_rs2_n0_0"}> : () -> !smt.int
          |%137 = "smt.declare_fun"() <{namePrefix = "c_spimm_0"}> : () -> !smt.int
          |%138 = "smt.declare_fun"() <{namePrefix = "c_sreg1_0"}> : () -> !smt.int
          |%139 = "smt.declare_fun"() <{namePrefix = "c_sreg2_0"}> : () -> !smt.int
          |%140 = "smt.declare_fun"() <{namePrefix = "c_uimm1_0"}> : () -> !smt.int
          |%141 = "smt.declare_fun"() <{namePrefix = "c_uimm10sp_s_0"}> : () -> !smt.int
          |%142 = "smt.declare_fun"() <{namePrefix = "c_uimm10sphi_0"}> : () -> !smt.int
          |%143 = "smt.declare_fun"() <{namePrefix = "c_uimm10splo_0"}> : () -> !smt.int
          |%144 = "smt.declare_fun"() <{namePrefix = "c_uimm2_0"}> : () -> !smt.int
          |%145 = "smt.declare_fun"() <{namePrefix = "c_uimm7hi_0"}> : () -> !smt.int
          |%146 = "smt.declare_fun"() <{namePrefix = "c_uimm7lo_0"}> : () -> !smt.int
          |%147 = "smt.declare_fun"() <{namePrefix = "c_uimm8hi_0"}> : () -> !smt.int
          |%148 = "smt.declare_fun"() <{namePrefix = "c_uimm8lo_0"}> : () -> !smt.int
          |%149 = "smt.declare_fun"() <{namePrefix = "c_uimm8sp_s_0"}> : () -> !smt.int
          |%150 = "smt.declare_fun"() <{namePrefix = "c_uimm8sphi_0"}> : () -> !smt.int
          |%151 = "smt.declare_fun"() <{namePrefix = "c_uimm8splo_0"}> : () -> !smt.int
          |%152 = "smt.declare_fun"() <{namePrefix = "c_uimm9hi_0"}> : () -> !smt.int
          |%153 = "smt.declare_fun"() <{namePrefix = "c_uimm9lo_0"}> : () -> !smt.int
          |%154 = "smt.declare_fun"() <{namePrefix = "c_uimm9sp_s_0"}> : () -> !smt.int
          |%155 = "smt.declare_fun"() <{namePrefix = "c_uimm9sphi_0"}> : () -> !smt.int
          |%156 = "smt.declare_fun"() <{namePrefix = "c_uimm9splo_0"}> : () -> !smt.int
          |%157 = "smt.declare_fun"() <{namePrefix = "csr_0"}> : () -> !smt.int
          |%158 = "smt.declare_fun"() <{namePrefix = "fm_0"}> : () -> !smt.int
          |%159 = "smt.declare_fun"() <{namePrefix = "funct2_0"}> : () -> !smt.int
          |%160 = "smt.declare_fun"() <{namePrefix = "funct3_0"}> : () -> !smt.int
          |%161 = "smt.declare_fun"() <{namePrefix = "funct7_0"}> : () -> !smt.int
          |%162 = "smt.declare_fun"() <{namePrefix = "imm12_0"}> : () -> !smt.int
          |%163 = "smt.declare_fun"() <{namePrefix = "imm12hi_0"}> : () -> !smt.int
          |%164 = "smt.declare_fun"() <{namePrefix = "imm12lo_0"}> : () -> !smt.int
          |%165 = "smt.declare_fun"() <{namePrefix = "imm2_0"}> : () -> !smt.int
          |%166 = "smt.declare_fun"() <{namePrefix = "imm20_0"}> : () -> !smt.int
          |%167 = "smt.declare_fun"() <{namePrefix = "imm3_0"}> : () -> !smt.int
          |%168 = "smt.declare_fun"() <{namePrefix = "imm4_0"}> : () -> !smt.int
          |%169 = "smt.declare_fun"() <{namePrefix = "imm5_0"}> : () -> !smt.int
          |%170 = "smt.declare_fun"() <{namePrefix = "imm6_0"}> : () -> !smt.int
          |%171 = "smt.declare_fun"() <{namePrefix = "jimm20_0"}> : () -> !smt.int
          |%172 = "smt.declare_fun"() <{namePrefix = "nf_0"}> : () -> !smt.int
          |%173 = "smt.declare_fun"() <{namePrefix = "opcode_0"}> : () -> !smt.int
          |%174 = "smt.declare_fun"() <{namePrefix = "pred_0"}> : () -> !smt.int
          |%175 = "smt.declare_fun"() <{namePrefix = "rc_0"}> : () -> !smt.int
          |%176 = "smt.declare_fun"() <{namePrefix = "rd_0"}> : () -> !smt.int
          |%177 = "smt.declare_fun"() <{namePrefix = "rd_n0_0"}> : () -> !smt.int
          |%178 = "smt.declare_fun"() <{namePrefix = "rd_n2_0"}> : () -> !smt.int
          |%179 = "smt.declare_fun"() <{namePrefix = "rd_p_0"}> : () -> !smt.int
          |%180 = "smt.declare_fun"() <{namePrefix = "rd_rs1_0"}> : () -> !smt.int
          |%181 = "smt.declare_fun"() <{namePrefix = "rd_rs1_n0_0"}> : () -> !smt.int
          |%182 = "smt.declare_fun"() <{namePrefix = "rd_rs1_p_0"}> : () -> !smt.int
          |%183 = "smt.declare_fun"() <{namePrefix = "rl_0"}> : () -> !smt.int
          |%184 = "smt.declare_fun"() <{namePrefix = "rm_0"}> : () -> !smt.int
          |%185 = "smt.declare_fun"() <{namePrefix = "rnum_0"}> : () -> !smt.int
          |%186 = "smt.declare_fun"() <{namePrefix = "rs1_0"}> : () -> !smt.int
          |%187 = "smt.declare_fun"() <{namePrefix = "rs1_n0_0"}> : () -> !smt.int
          |%188 = "smt.declare_fun"() <{namePrefix = "rs1_p_0"}> : () -> !smt.int
          |%189 = "smt.declare_fun"() <{namePrefix = "rs2_0"}> : () -> !smt.int
          |%190 = "smt.declare_fun"() <{namePrefix = "rs2_p_0"}> : () -> !smt.int
          |%191 = "smt.declare_fun"() <{namePrefix = "rs3_0"}> : () -> !smt.int
          |%192 = "smt.declare_fun"() <{namePrefix = "rt_0"}> : () -> !smt.int
          |%193 = "smt.declare_fun"() <{namePrefix = "shamtd_0"}> : () -> !smt.int
          |%194 = "smt.declare_fun"() <{namePrefix = "shamtq_0"}> : () -> !smt.int
          |%195 = "smt.declare_fun"() <{namePrefix = "shamtw_0"}> : () -> !smt.int
          |%196 = "smt.declare_fun"() <{namePrefix = "shamtw4_0"}> : () -> !smt.int
          |%197 = "smt.declare_fun"() <{namePrefix = "simm5_0"}> : () -> !smt.int
          |%198 = "smt.declare_fun"() <{namePrefix = "succ_0"}> : () -> !smt.int
          |%199 = "smt.declare_fun"() <{namePrefix = "vd_0"}> : () -> !smt.int
          |%200 = "smt.declare_fun"() <{namePrefix = "vm_0"}> : () -> !smt.int
          |%201 = "smt.declare_fun"() <{namePrefix = "vs1_0"}> : () -> !smt.int
          |%202 = "smt.declare_fun"() <{namePrefix = "vs2_0"}> : () -> !smt.int
          |%203 = "smt.declare_fun"() <{namePrefix = "vs3_0"}> : () -> !smt.int
          |%204 = "smt.declare_fun"() <{namePrefix = "wd_0"}> : () -> !smt.int
          |%205 = "smt.declare_fun"() <{namePrefix = "zimm_0"}> : () -> !smt.int
          |%206 = "smt.declare_fun"() <{namePrefix = "zimm10_0"}> : () -> !smt.int
          |%207 = "smt.declare_fun"() <{namePrefix = "zimm11_0"}> : () -> !smt.int
          |%208 = "smt.declare_fun"() <{namePrefix = "zimm5_0"}> : () -> !smt.int
          |%209 = "smt.declare_fun"() <{namePrefix = "zimm6hi_0"}> : () -> !smt.int
          |%210 = "smt.declare_fun"() <{namePrefix = "zimm6lo_0"}> : () -> !smt.int
          |%211 = "smt.int.constant"() <{value = 55 : i32}> : () -> !smt.int
          |%212 = "smt.eq"(%110, %211) : (!smt.int, !smt.int) -> !smt.bool
          |%213 = "smt.int.constant"() <{value = 0 : i32}> : () -> !smt.int
          |%214 = "smt.int.cmp"(%176, %213) <{pred = 3 : i64}> : (!smt.int, !smt.int) -> !smt.bool
          |%215 = "smt.int.constant"() <{value = 32 : i32}> : () -> !smt.int
          |%216 = "smt.int.cmp"(%176, %215) <{pred = 0 : i64}> : (!smt.int, !smt.int) -> !smt.bool
          |%217 = "smt.and"(%214, %216) : (!smt.bool, !smt.bool) -> !smt.bool
          |%218 = "smt.and"(%212, %217) : (!smt.bool, !smt.bool) -> !smt.bool
          |%219 = "smt.int.constant"() <{value = 0 : i32}> : () -> !smt.int
          |%220 = "smt.int.cmp"(%186, %219) <{pred = 3 : i64}> : (!smt.int, !smt.int) -> !smt.bool
          |%221 = "smt.int.constant"() <{value = 32 : i32}> : () -> !smt.int
          |%222 = "smt.int.cmp"(%186, %221) <{pred = 0 : i64}> : (!smt.int, !smt.int) -> !smt.bool
          |%223 = "smt.and"(%220, %222) : (!smt.bool, !smt.bool) -> !smt.bool
          |%224 = "smt.and"(%218, %223) : (!smt.bool, !smt.bool) -> !smt.bool
          |%225 = "smt.int.constant"() <{value = 0 : i32}> : () -> !smt.int
          |%226 = "smt.int.cmp"(%189, %225) <{pred = 3 : i64}> : (!smt.int, !smt.int) -> !smt.bool
          |%227 = "smt.int.constant"() <{value = 32 : i32}> : () -> !smt.int
          |%228 = "smt.int.cmp"(%189, %227) <{pred = 0 : i64}> : (!smt.int, !smt.int) -> !smt.bool
          |%229 = "smt.and"(%226, %228) : (!smt.bool, !smt.bool) -> !smt.bool
          |%230 = "smt.and"(%224, %229) : (!smt.bool, !smt.bool) -> !smt.bool
          |%231 = "smt.and"(%230, %19) : (!smt.bool, !smt.bool) -> !smt.bool
          |%232 = "smt.int.constant"() <{value = 1 : i32}> : () -> !smt.int
          |%233 = "smt.int.cmp"(%176, %232) <{pred = 3 : i64}> : (!smt.int, !smt.int) -> !smt.bool
          |%234 = "smt.int.constant"() <{value = 32 : i32}> : () -> !smt.int
          |%235 = "smt.int.cmp"(%176, %234) <{pred = 0 : i64}> : (!smt.int, !smt.int) -> !smt.bool
          |%236 = "smt.and"(%233, %235) : (!smt.bool, !smt.bool) -> !smt.bool
          |%237 = "smt.and"(%231, %236) : (!smt.bool, !smt.bool) -> !smt.bool
          |%238 = "smt.int.constant"() <{value = 1 : i32}> : () -> !smt.int
          |%239 = "smt.int.cmp"(%186, %238) <{pred = 3 : i64}> : (!smt.int, !smt.int) -> !smt.bool
          |%240 = "smt.int.constant"() <{value = 32 : i32}> : () -> !smt.int
          |%241 = "smt.int.cmp"(%186, %240) <{pred = 0 : i64}> : (!smt.int, !smt.int) -> !smt.bool
          |%242 = "smt.and"(%239, %241) : (!smt.bool, !smt.bool) -> !smt.bool
          |%243 = "smt.and"(%237, %242) : (!smt.bool, !smt.bool) -> !smt.bool
          |%244 = "smt.int.constant"() <{value = 1 : i32}> : () -> !smt.int
          |%245 = "smt.int.cmp"(%189, %244) <{pred = 3 : i64}> : (!smt.int, !smt.int) -> !smt.bool
          |%246 = "smt.int.constant"() <{value = 32 : i32}> : () -> !smt.int
          |%247 = "smt.int.cmp"(%189, %246) <{pred = 0 : i64}> : (!smt.int, !smt.int) -> !smt.bool
          |%248 = "smt.and"(%245, %247) : (!smt.bool, !smt.bool) -> !smt.bool
          |%249 = "smt.and"(%243, %248) : (!smt.bool, !smt.bool) -> !smt.bool
          |"smt.assert"(%249) : (!smt.bool) -> ()
          |"smt.check"() ({
          |"smt.yield"() : () -> ()
          |}, {
          |"smt.yield"() : () -> ()
          |}, {
          |"smt.yield"() : () -> ()
          |}) : () -> ()
          |}) : () -> ()
          |}) {symName = "func"} : () -> ()
          |}) : () -> ()
          |""".stripMargin.split('\n').toSeq*
      )

      SingleInstruction.rvprobeTestSMTLIB(
        """
          |(set-logic QF_LIA)
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
          |(declare-const rv64_i Bool)
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
          |tmp))
          |(assert (let ((tmp_0 (or rv32_c rv32_c_f rv32_d_zfa rv32_i rv32_zbb rv32_zbkb rv32_zbs rv32_zicntr rv32_zk rv32_zkn rv32_zknd rv32_zkne rv32_zknh rv32_zks rv64_a rv64_c rv64_d rv64_f rv64_h rv64_m rv64_q rv64_q_zfa rv64_zacas rv64_zba rv64_zbb rv64_zbkb rv64_zbp rv64_zbs rv64_zcb rv64_zfh rv64_zk rv64_zkn rv64_zknd rv64_zkne rv64_zknh rv64_zks rv_a rv_c rv_c_d rv_c_zicfiss rv_c_zihintntl rv_d rv_d_zfa rv_d_zfh rv_f rv_f_zfa rv_h rv_i rv_m rv_q rv_q_zfa rv_q_zfh rv_s rv_sdext rv_smdbltrp rv_smrnmi rv_svinval rv_system rv_v rv_v_aliases rv_zabha rv_zacas rv_zalasr rv_zawrs rv_zba rv_zbb rv_zbc rv_zbkb rv_zbkc rv_zbkx rv_zbp rv_zbs rv_zcb rv_zcmop rv_zcmp rv_zcmt rv_zfbfmin rv_zfh rv_zfh_zfa rv_zicbo rv_zicfilp rv_zicfiss rv_zicntr rv_zicond rv_zicsr rv_zifencei rv_zihintntl rv_zimop rv_zk rv_zkn rv_zknh rv_zks rv_zksed rv_zksh rv_zvbb rv_zvbc rv_zvfbfmin rv_zvfbfwma rv_zvkg rv_zvkn rv_zvkned rv_zvknha rv_zvknhb rv_zvks rv_zvksed rv_zvksh)))
          |(let ((tmp_1 (not tmp_0)))
          |tmp_1)))
          |(declare-const nameId_0 Int)
          |(declare-const amoop_0 Int)
          |(declare-const aq_0 Int)
          |(declare-const aqrl_0 Int)
          |(declare-const bimm12hi_0 Int)
          |(declare-const bimm12lo_0 Int)
          |(declare-const bs_0 Int)
          |(declare-const c_bimm9hi_0 Int)
          |(declare-const c_bimm9lo_0 Int)
          |(declare-const c_imm12_0 Int)
          |(declare-const c_imm6hi_0 Int)
          |(declare-const c_imm6lo_0 Int)
          |(declare-const c_index_0 Int)
          |(declare-const c_nzimm10hi_0 Int)
          |(declare-const c_nzimm10lo_0 Int)
          |(declare-const c_nzimm18hi_0 Int)
          |(declare-const c_nzimm18lo_0 Int)
          |(declare-const c_nzimm6hi_0 Int)
          |(declare-const c_nzimm6lo_0 Int)
          |(declare-const c_nzuimm10_0 Int)
          |(declare-const c_nzuimm5_0 Int)
          |(declare-const c_nzuimm6hi_0 Int)
          |(declare-const c_nzuimm6lo_0 Int)
          |(declare-const c_rlist_0 Int)
          |(declare-const c_rs1_n0_0 Int)
          |(declare-const c_rs2_0 Int)
          |(declare-const c_rs2_n0_0 Int)
          |(declare-const c_spimm_0 Int)
          |(declare-const c_sreg1_0 Int)
          |(declare-const c_sreg2_0 Int)
          |(declare-const c_uimm1_0 Int)
          |(declare-const c_uimm10sp_s_0 Int)
          |(declare-const c_uimm10sphi_0 Int)
          |(declare-const c_uimm10splo_0 Int)
          |(declare-const c_uimm2_0 Int)
          |(declare-const c_uimm7hi_0 Int)
          |(declare-const c_uimm7lo_0 Int)
          |(declare-const c_uimm8hi_0 Int)
          |(declare-const c_uimm8lo_0 Int)
          |(declare-const c_uimm8sp_s_0 Int)
          |(declare-const c_uimm8sphi_0 Int)
          |(declare-const c_uimm8splo_0 Int)
          |(declare-const c_uimm9hi_0 Int)
          |(declare-const c_uimm9lo_0 Int)
          |(declare-const c_uimm9sp_s_0 Int)
          |(declare-const c_uimm9sphi_0 Int)
          |(declare-const c_uimm9splo_0 Int)
          |(declare-const csr_0 Int)
          |(declare-const fm_0 Int)
          |(declare-const funct2_0 Int)
          |(declare-const funct3_0 Int)
          |(declare-const funct7_0 Int)
          |(declare-const imm12_0 Int)
          |(declare-const imm12hi_0 Int)
          |(declare-const imm12lo_0 Int)
          |(declare-const imm2_0 Int)
          |(declare-const imm20_0 Int)
          |(declare-const imm3_0 Int)
          |(declare-const imm4_0 Int)
          |(declare-const imm5_0 Int)
          |(declare-const imm6_0 Int)
          |(declare-const jimm20_0 Int)
          |(declare-const nf_0 Int)
          |(declare-const opcode_0 Int)
          |(declare-const pred_0 Int)
          |(declare-const rc_0 Int)
          |(declare-const rd_0 Int)
          |(declare-const rd_n0_0 Int)
          |(declare-const rd_n2_0 Int)
          |(declare-const rd_p_0 Int)
          |(declare-const rd_rs1_0 Int)
          |(declare-const rd_rs1_n0_0 Int)
          |(declare-const rd_rs1_p_0 Int)
          |(declare-const rl_0 Int)
          |(declare-const rm_0 Int)
          |(declare-const rnum_0 Int)
          |(declare-const rs1_0 Int)
          |(declare-const rs1_n0_0 Int)
          |(declare-const rs1_p_0 Int)
          |(declare-const rs2_0 Int)
          |(declare-const rs2_p_0 Int)
          |(declare-const rs3_0 Int)
          |(declare-const rt_0 Int)
          |(declare-const shamtd_0 Int)
          |(declare-const shamtq_0 Int)
          |(declare-const shamtw_0 Int)
          |(declare-const shamtw4_0 Int)
          |(declare-const simm5_0 Int)
          |(declare-const succ_0 Int)
          |(declare-const vd_0 Int)
          |(declare-const vm_0 Int)
          |(declare-const vs1_0 Int)
          |(declare-const vs2_0 Int)
          |(declare-const vs3_0 Int)
          |(declare-const wd_0 Int)
          |(declare-const zimm_0 Int)
          |(declare-const zimm10_0 Int)
          |(declare-const zimm11_0 Int)
          |(declare-const zimm5_0 Int)
          |(declare-const zimm6hi_0 Int)
          |(declare-const zimm6lo_0 Int)
          |(assert (let ((tmp_2 (< rs2_0 32)))
          |(let ((tmp_3 (>= rs2_0 1)))
          |(let ((tmp_4 (and tmp_3 tmp_2)))
          |(let ((tmp_5 (< rs1_0 32)))
          |(let ((tmp_6 (>= rs1_0 1)))
          |(let ((tmp_7 (and tmp_6 tmp_5)))
          |(let ((tmp_8 (< rd_0 32)))
          |(let ((tmp_9 (>= rd_0 1)))
          |(let ((tmp_10 (and tmp_9 tmp_8)))
          |(let ((tmp_11 (< rs2_0 32)))
          |(let ((tmp_12 (>= rs2_0 0)))
          |(let ((tmp_13 (and tmp_12 tmp_11)))
          |(let ((tmp_14 (< rs1_0 32)))
          |(let ((tmp_15 (>= rs1_0 0)))
          |(let ((tmp_16 (and tmp_15 tmp_14)))
          |(let ((tmp_17 (< rd_0 32)))
          |(let ((tmp_18 (>= rd_0 0)))
          |(let ((tmp_19 (and tmp_18 tmp_17)))
          |(let ((tmp_20 (= nameId_0 55)))
          |(let ((tmp_21 (and tmp_20 tmp_19)))
          |(let ((tmp_22 (and tmp_21 tmp_16)))
          |(let ((tmp_23 (and tmp_22 tmp_13)))
          |(let ((tmp_24 (and tmp_23 rv64_i)))
          |(let ((tmp_25 (and tmp_24 tmp_10)))
          |(let ((tmp_26 (and tmp_25 tmp_7)))
          |(let ((tmp_27 (and tmp_26 tmp_4)))
          |tmp_27)))))))))))))))))))))))))))
          |(check-sat)
          |(reset)
          |""".stripMargin.split('\n').toSeq*
      )

      SingleInstruction.rvprobeTestZ3Output("sat")

      SingleInstruction.rvprobeTestInstructions("0: addw x1 x1 x1")

    test("MultiInstructions"):
      object MultiInstructions extends RVGenerator with HasRVProbeTest:
        val sets          = isRV64GC()
        def constraints() =
          (0 until 50).foreach { i =>
            instruction(i) {
              isAddw() & rdRange(1, 32) & rs1Range(1, 32) & rs2Range(1, 32)
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
            instruction(i) {
              isAddw() & rdRange(1, 32) & rs1Range(1, 32) & rs2Range(1, 32)
            }
          }

          coverBins(0 until instructionCount)(_.rd, (1 until 32).map(i => i.S))
          coverBins(0 until instructionCount)(_.rs1, (1 until 32).map(i => i.S))
          coverBins(0 until instructionCount)(_.rs2, (1 until 32).map(i => i.S))

          coverSigns(instructionCount, isAddw(), true, true, true)
      ComplexRecipe.rvprobeTestInstructions(""" 
                                              |0: addw x1 x1 x1
                                              |1: addw x1 x1 x1
                                              |2: addw x1 x1 x1
                                              |3: addw x1 x1 x1
                                              |4: addw x1 x1 x1
                                              |5: addw x1 x1 x1
                                              |6: addw x1 x1 x1
                                              |7: addw x1 x1 x1
                                              |8: addw x1 x1 x1
                                              |9: addw x1 x11 x1
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
                                              |20: addw x2 x1 x2
                                              |21: addw x3 x20 x3
                                              |22: addw x5 x2 x4
                                              |23: addw x6 x3 x5
                                              |24: addw x7 x4 x6
                                              |25: addw x8 x5 x7
                                              |26: addw x9 x6 x8
                                              |27: addw x10 x7 x9
                                              |28: addw x11 x8 x10
                                              |29: addw x12 x9 x11
                                              |30: addw x13 x10 x12
                                              |31: addw x14 x12 x13
                                              |32: addw x15 x13 x14
                                              |33: addw x16 x14 x15
                                              |34: addw x17 x15 x16
                                              |35: addw x18 x16 x17
                                              |36: addw x19 x17 x18
                                              |37: addw x20 x18 x19
                                              |38: addw x21 x19 x20
                                              |39: addw x22 x21 x21
                                              |40: addw x23 x22 x22
                                              |41: addw x24 x23 x23
                                              |42: addw x25 x24 x24
                                              |43: addw x26 x25 x25
                                              |44: addw x27 x26 x26
                                              |45: addw x28 x27 x27
                                              |46: addw x29 x28 x28
                                              |47: addw x30 x29 x29
                                              |48: addw x31 x30 x30
                                              |49: addw x4 x31 x31
                                              |50: addi x1 x31 -2048
                                              |51: addi x2 x31 2047
                                              |52: addw x3 x1 x2
                                              |53: addw x3 x2 x1
                                              |""".stripMargin.split('\n').toSeq*)
