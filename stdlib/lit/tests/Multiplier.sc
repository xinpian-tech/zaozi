// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 xinpian-tech

// DEFINE: %{test} = scala-cli --server=false --java-home=%JAVAHOME --extra-jars=%RUNCLASSPATH --scala-version=%SCALAVERSION -O="-experimental" %JAVAOPTS --main-class "me.jiuyang.stdlib.multiplier.default.Multiplier" %s --
// DEFINE: %{bmc1x1} = circt-bmc %t-w1m1.clean.hw.mlir --module=Multiplier_aWidth1_bWidth1_CheckContract_0 -b 1 --shared-libs=%Z3LIB --run
// DEFINE: %{bmc1x8} = circt-bmc %t-w1m8.clean.hw.mlir --module=Multiplier_aWidth1_bWidth8_CheckContract_0 -b 1 --shared-libs=%Z3LIB --run
// DEFINE: %{bmc8x1} = circt-bmc %t-w8m1.clean.hw.mlir --module=Multiplier_aWidth8_bWidth1_CheckContract_0 -b 1 --shared-libs=%Z3LIB --run
// DEFINE: %{bmc2x2} = circt-bmc %t-w2m2.clean.hw.mlir --module=Multiplier_aWidth2_bWidth2_CheckContract_0 -b 1 --shared-libs=%Z3LIB --run
// DEFINE: %{bmc2x8} = circt-bmc %t-w2m8.clean.hw.mlir --module=Multiplier_aWidth2_bWidth8_CheckContract_0 -b 1 --shared-libs=%Z3LIB --run
// DEFINE: %{bmc8x2} = circt-bmc %t-w8m2.clean.hw.mlir --module=Multiplier_aWidth8_bWidth2_CheckContract_0 -b 1 --shared-libs=%Z3LIB --run
// DEFINE: %{bmc3x3} = circt-bmc %t-w3m3.clean.hw.mlir --module=Multiplier_aWidth3_bWidth3_CheckContract_0 -b 1 --shared-libs=%Z3LIB --run
// DEFINE: %{bmc3x8} = circt-bmc %t-w3m8.clean.hw.mlir --module=Multiplier_aWidth3_bWidth8_CheckContract_0 -b 1 --shared-libs=%Z3LIB --run
// DEFINE: %{bmc4x4} = circt-bmc %t-w4m4.clean.hw.mlir --module=Multiplier_aWidth4_bWidth4_CheckContract_0 -b 1 --shared-libs=%Z3LIB --run
// DEFINE: %{bmc4x8} = circt-bmc %t-w4m8.clean.hw.mlir --module=Multiplier_aWidth4_bWidth8_CheckContract_0 -b 1 --shared-libs=%Z3LIB --run
// DEFINE: %{bmc8x3} = circt-bmc %t-w8m3.clean.hw.mlir --module=Multiplier_aWidth8_bWidth3_CheckContract_0 -b 1 --shared-libs=%Z3LIB --run

// Degenerate 1 x 1 path
// RUN: %{test} config %t-w1m1.json --aWidth 1 --bWidth 1
// RUN: mkdir -p %t-w1m1.modules
// RUN: (cd %t-w1m1.modules && %{test} design %t-w1m1.json)
// RUN: firld --base-circuit=Multiplier_aWidth1_bWidth1 %t-w1m1.modules/*.mlirbc -o %t-w1m1.linked.mlir
// RUN: firtool %t-w1m1.linked.mlir | FileCheck %s -check-prefix=ONE1X1
// RUN: firtool %t-w1m1.linked.mlir --hw-pass-plugin='lower-contracts' --output-hw-mlir=%t-w1m1.contract.hw.mlir --disable-output
// RUN: circt-opt %t-w1m1.contract.hw.mlir --strip-om --symbol-dce -o %t-w1m1.clean.hw.mlir
// RUN: %{bmc1x1} | FileCheck %s -check-prefix=BMC
// RUN: rm %t-w1m1.json %t-w1m1.linked.mlir %t-w1m1.contract.hw.mlir %t-w1m1.clean.hw.mlir -f
// RUN: rm -rf %t-w1m1.modules

// A is the one-bit operand
// RUN: %{test} config %t-w1m8.json --aWidth 1 --bWidth 8
// RUN: mkdir -p %t-w1m8.modules
// RUN: (cd %t-w1m8.modules && %{test} design %t-w1m8.json)
// RUN: firld --base-circuit=Multiplier_aWidth1_bWidth8 %t-w1m8.modules/*.mlirbc -o %t-w1m8.linked.mlir
// RUN: firtool %t-w1m8.linked.mlir | FileCheck %s -check-prefix=ONE1X8
// RUN: firtool %t-w1m8.linked.mlir --hw-pass-plugin='lower-contracts' --output-hw-mlir=%t-w1m8.contract.hw.mlir --disable-output
// RUN: circt-opt %t-w1m8.contract.hw.mlir --strip-om --symbol-dce -o %t-w1m8.clean.hw.mlir
// RUN: %{bmc1x8} | FileCheck %s -check-prefix=BMC
// RUN: rm %t-w1m8.json %t-w1m8.linked.mlir %t-w1m8.contract.hw.mlir %t-w1m8.clean.hw.mlir -f
// RUN: rm -rf %t-w1m8.modules

// B is the one-bit operand
// RUN: %{test} config %t-w8m1.json --aWidth 8 --bWidth 1
// RUN: mkdir -p %t-w8m1.modules
// RUN: (cd %t-w8m1.modules && %{test} design %t-w8m1.json)
// RUN: firld --base-circuit=Multiplier_aWidth8_bWidth1 %t-w8m1.modules/*.mlirbc -o %t-w8m1.linked.mlir
// RUN: firtool %t-w8m1.linked.mlir | FileCheck %s -check-prefix=ONE8X1
// RUN: firtool %t-w8m1.linked.mlir --hw-pass-plugin='lower-contracts' --output-hw-mlir=%t-w8m1.contract.hw.mlir --disable-output
// RUN: circt-opt %t-w8m1.contract.hw.mlir --strip-om --symbol-dce -o %t-w8m1.clean.hw.mlir
// RUN: %{bmc8x1} | FileCheck %s -check-prefix=BMC
// RUN: rm %t-w8m1.json %t-w8m1.linked.mlir %t-w8m1.contract.hw.mlir %t-w8m1.clean.hw.mlir -f
// RUN: rm -rf %t-w8m1.modules

// Degenerate 2 x 2 path
// RUN: %{test} config %t-w2m2.json --aWidth 2 --bWidth 2
// RUN: mkdir -p %t-w2m2.modules
// RUN: (cd %t-w2m2.modules && %{test} design %t-w2m2.json)
// RUN: firld --base-circuit=Multiplier_aWidth2_bWidth2 %t-w2m2.modules/*.mlirbc -o %t-w2m2.linked.mlir
// RUN: firtool %t-w2m2.linked.mlir | FileCheck %s -check-prefix=TWO2X2
// RUN: firtool %t-w2m2.linked.mlir --hw-pass-plugin='lower-contracts' --output-hw-mlir=%t-w2m2.contract.hw.mlir --disable-output
// RUN: circt-opt %t-w2m2.contract.hw.mlir --strip-om --symbol-dce -o %t-w2m2.clean.hw.mlir
// RUN: %{bmc2x2} | FileCheck %s -check-prefix=BMC
// RUN: rm %t-w2m2.json %t-w2m2.linked.mlir %t-w2m2.contract.hw.mlir %t-w2m2.clean.hw.mlir -f
// RUN: rm -rf %t-w2m2.modules

// A is the two-bit operand
// RUN: %{test} config %t-w2m8.json --aWidth 2 --bWidth 8
// RUN: mkdir -p %t-w2m8.modules
// RUN: (cd %t-w2m8.modules && %{test} design %t-w2m8.json)
// RUN: firld --base-circuit=Multiplier_aWidth2_bWidth8 %t-w2m8.modules/*.mlirbc -o %t-w2m8.linked.mlir
// RUN: firtool %t-w2m8.linked.mlir | FileCheck %s -check-prefix=TWO2X8
// RUN: firtool %t-w2m8.linked.mlir --hw-pass-plugin='lower-contracts' --output-hw-mlir=%t-w2m8.contract.hw.mlir --disable-output
// RUN: circt-opt %t-w2m8.contract.hw.mlir --strip-om --symbol-dce -o %t-w2m8.clean.hw.mlir
// RUN: %{bmc2x8} | FileCheck %s -check-prefix=BMC
// RUN: rm %t-w2m8.json %t-w2m8.linked.mlir %t-w2m8.contract.hw.mlir %t-w2m8.clean.hw.mlir -f
// RUN: rm -rf %t-w2m8.modules

// B is the two-bit operand
// RUN: %{test} config %t-w8m2.json --aWidth 8 --bWidth 2
// RUN: mkdir -p %t-w8m2.modules
// RUN: (cd %t-w8m2.modules && %{test} design %t-w8m2.json)
// RUN: firld --base-circuit=Multiplier_aWidth8_bWidth2 %t-w8m2.modules/*.mlirbc -o %t-w8m2.linked.mlir
// RUN: firtool %t-w8m2.linked.mlir | FileCheck %s -check-prefix=TWO8X2
// RUN: firtool %t-w8m2.linked.mlir --hw-pass-plugin='lower-contracts' --output-hw-mlir=%t-w8m2.contract.hw.mlir --disable-output
// RUN: circt-opt %t-w8m2.contract.hw.mlir --strip-om --symbol-dce -o %t-w8m2.clean.hw.mlir
// RUN: %{bmc8x2} | FileCheck %s -check-prefix=BMC
// RUN: rm %t-w8m2.json %t-w8m2.linked.mlir %t-w8m2.contract.hw.mlir %t-w8m2.clean.hw.mlir -f
// RUN: rm -rf %t-w8m2.modules

// Smallest three-by-vector path
// RUN: %{test} config %t-w3m3.json --aWidth 3 --bWidth 3
// RUN: mkdir -p %t-w3m3.modules
// RUN: (cd %t-w3m3.modules && %{test} design %t-w3m3.json)
// RUN: firld --base-circuit=Multiplier_aWidth3_bWidth3 %t-w3m3.modules/*.mlirbc -o %t-w3m3.linked.mlir
// RUN: firtool %t-w3m3.linked.mlir | FileCheck %s -check-prefix=THREE3X3
// RUN: firtool %t-w3m3.linked.mlir --hw-pass-plugin='lower-contracts' --output-hw-mlir=%t-w3m3.contract.hw.mlir --disable-output
// RUN: circt-opt %t-w3m3.contract.hw.mlir --strip-om --symbol-dce -o %t-w3m3.clean.hw.mlir
// RUN: %{bmc3x3} | FileCheck %s -check-prefix=BMC
// RUN: rm %t-w3m3.json %t-w3m3.linked.mlir %t-w3m3.contract.hw.mlir %t-w3m3.clean.hw.mlir -f
// RUN: rm -rf %t-w3m3.modules

// Three-by-vector path and representative contract lowering
// RUN: %{test} config %t-w3m8.json --aWidth 3 --bWidth 8
// RUN: mkdir -p %t-w3m8.modules
// RUN: (cd %t-w3m8.modules && %{test} design %t-w3m8.json)
// RUN: firld --base-circuit=Multiplier_aWidth3_bWidth8 %t-w3m8.modules/*.mlirbc -o %t-w3m8.linked.mlir
// RUN: circt-opt %t-w3m8.linked.mlir | FileCheck %s -check-prefix=CONTRACT
// RUN: firtool %t-w3m8.linked.mlir | FileCheck %s -check-prefix=THREE3X8
// RUN: firtool %t-w3m8.linked.mlir --hw-pass-plugin='lower-contracts' --output-hw-mlir=%t-w3m8.contract.hw.mlir --disable-output
// RUN: circt-opt %t-w3m8.contract.hw.mlir --strip-om --symbol-dce -o %t-w3m8.clean.hw.mlir
// RUN: FileCheck %s -check-prefix=LOWERED --input-file=%t-w3m8.clean.hw.mlir
// RUN: %{bmc3x8} | FileCheck %s -check-prefix=BMC
// RUN: rm %t-w3m8.json %t-w3m8.linked.mlir %t-w3m8.contract.hw.mlir %t-w3m8.clean.hw.mlir -f
// RUN: rm -rf %t-w3m8.modules

// Square general CSA path
// RUN: %{test} config %t-w4m4.json --aWidth 4 --bWidth 4
// RUN: mkdir -p %t-w4m4.modules
// RUN: (cd %t-w4m4.modules && %{test} design %t-w4m4.json)
// RUN: firld --base-circuit=Multiplier_aWidth4_bWidth4 %t-w4m4.modules/*.mlirbc -o %t-w4m4.linked.mlir
// RUN: firtool %t-w4m4.linked.mlir | FileCheck %s -check-prefix=CSA4X4
// RUN: firtool %t-w4m4.linked.mlir --hw-pass-plugin='lower-contracts' --output-hw-mlir=%t-w4m4.contract.hw.mlir --disable-output
// RUN: circt-opt %t-w4m4.contract.hw.mlir --strip-om --symbol-dce -o %t-w4m4.clean.hw.mlir
// RUN: %{bmc4x4} | FileCheck %s -check-prefix=BMC
// RUN: rm %t-w4m4.json %t-w4m4.linked.mlir %t-w4m4.contract.hw.mlir %t-w4m4.clean.hw.mlir -f
// RUN: rm -rf %t-w4m4.modules

// Rectangular general CSA path with B wider than A; exercises the CHN ZB insertion above the A rows
// RUN: %{test} config %t-w4m8.json --aWidth 4 --bWidth 8
// RUN: mkdir -p %t-w4m8.modules
// RUN: (cd %t-w4m8.modules && %{test} design %t-w4m8.json)
// RUN: firld --base-circuit=Multiplier_aWidth4_bWidth8 %t-w4m8.modules/*.mlirbc -o %t-w4m8.linked.mlir
// RUN: firtool %t-w4m8.linked.mlir | FileCheck %s -check-prefix=CSA4X8
// RUN: firtool %t-w4m8.linked.mlir --hw-pass-plugin='lower-contracts' --output-hw-mlir=%t-w4m8.contract.hw.mlir --disable-output
// RUN: circt-opt %t-w4m8.contract.hw.mlir --strip-om --symbol-dce -o %t-w4m8.clean.hw.mlir
// RUN: %{bmc4x8} | FileCheck %s -check-prefix=BMC
// RUN: rm %t-w4m8.json %t-w4m8.linked.mlir %t-w4m8.contract.hw.mlir %t-w4m8.clean.hw.mlir -f
// RUN: rm -rf %t-w4m8.modules

// Rectangular general CSA path; n x 3 is intentionally not specialized
// RUN: %{test} config %t-w8m3.json --aWidth 8 --bWidth 3
// RUN: mkdir -p %t-w8m3.modules
// RUN: (cd %t-w8m3.modules && %{test} design %t-w8m3.json)
// RUN: firld --base-circuit=Multiplier_aWidth8_bWidth3 %t-w8m3.modules/*.mlirbc -o %t-w8m3.linked.mlir
// RUN: firtool %t-w8m3.linked.mlir | FileCheck %s -check-prefix=CSA8X3
// RUN: firtool %t-w8m3.linked.mlir --hw-pass-plugin='lower-contracts' --output-hw-mlir=%t-w8m3.contract.hw.mlir --disable-output
// RUN: circt-opt %t-w8m3.contract.hw.mlir --strip-om --symbol-dce -o %t-w8m3.clean.hw.mlir
// RUN: %{bmc8x3} | FileCheck %s -check-prefix=BMC
// RUN: rm %t-w8m3.json %t-w8m3.linked.mlir %t-w8m3.contract.hw.mlir %t-w8m3.clean.hw.mlir -f
// RUN: rm -rf %t-w8m3.modules

// ONE1X1-LABEL: module Multiplier_aWidth1_bWidth1
// ONE1X1: Incrementer_width2
// ONE1X1-NOT: module OneByVectorMultiplier_
// ONE1X1-NOT: AbsVal_width
// ONE1X1-NOT: BrentKungAdder_width

// ONE1X8-LABEL: module Multiplier_aWidth1_bWidth8
// ONE1X8: AbsVal_width8
// ONE1X8: Incrementer_width9_radix4
// ONE1X8-NOT: module OneByVectorMultiplier_
// ONE1X8-NOT: BrentKungAdder_width

// ONE8X1-LABEL: module Multiplier_aWidth8_bWidth1
// ONE8X1: AbsVal_width8
// ONE8X1: Incrementer_width9_radix4
// ONE8X1-NOT: module OneByVectorMultiplier_
// ONE8X1-NOT: BrentKungAdder_width

// TWO2X2-LABEL: module Multiplier_aWidth2_bWidth2
// TWO2X2: AbsVal_width2
// TWO2X2: Incrementer_width4_radix4
// TWO2X2-NOT: module TwoByVectorMultiplier_
// TWO2X2-NOT: BrentKungAdder_width

// TWO2X8-LABEL: module Multiplier_aWidth2_bWidth8
// TWO2X8: AbsVal_width2
// TWO2X8: AbsVal_width8
// TWO2X8: BrentKungAdder_width8_radix4
// TWO2X8: Incrementer_width10_radix4
// TWO2X8-NOT: module TwoByVectorMultiplier_

// TWO8X2-LABEL: module Multiplier_aWidth8_bWidth2
// TWO8X2: AbsVal_width8
// TWO8X2: AbsVal_width2
// TWO8X2: BrentKungAdder_width8_radix4
// TWO8X2: Incrementer_width10_radix4
// TWO8X2-NOT: module TwoByVectorMultiplier_

// THREE3X3-LABEL: module Multiplier_aWidth3_bWidth3
// THREE3X3: AbsVal_width3
// THREE3X3: BrentKungAdder_width5_radix4
// THREE3X3: Incrementer_width6_radix4
// THREE3X3-NOT: module ThreeByVectorMultiplier_

// CONTRACT: firrtl.contract
// CONTRACT: multiplier_matches_mul
// THREE3X8-LABEL: module Multiplier_aWidth3_bWidth8
// THREE3X8: AbsVal_width3
// THREE3X8: AbsVal_width8
// THREE3X8: BrentKungAdder_width10_radix4
// THREE3X8: Incrementer_width11_radix4
// THREE3X8-NOT: module ThreeByVectorMultiplier_
// LOWERED-LABEL: verif.formal @Multiplier_aWidth3_bWidth8_CheckContract_0
// LOWERED: verif.assert
// LOWERED-SAME: multiplier_matches_mul

// CSA4X4-LABEL: module Multiplier_aWidth4_bWidth4
// CSA4X4: BrentKungAdder_width6_radix4
// CSA4X4-NOT: module CsaMultiplier_
// CSA4X4-NOT: AbsVal_width
// CSA4X4-NOT: Incrementer_width

// CSA4X8-LABEL: module Multiplier_aWidth4_bWidth8
// CSA4X8: BrentKungAdder_width10_radix4
// CSA4X8-NOT: module CsaMultiplier_
// CSA4X8-NOT: AbsVal_width
// CSA4X8-NOT: Incrementer_width

// CSA8X3-LABEL: module Multiplier_aWidth8_bWidth3
// CSA8X3: BrentKungAdder_width9_radix4
// CSA8X3-NOT: module CsaMultiplier_
// CSA8X3-NOT: module ThreeByVectorMultiplier_
// CSA8X3-NOT: AbsVal_width
// CSA8X3-NOT: Incrementer_width

// BMC: Bound reached with no violations!
