// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 xinpian-tech

// Equivalent to:
//   assign {CO, SUM} = A + B + CI;
hw.module @Add8(in %A : i8, in %B : i8, in %CI : i1, out CO : i1, out SUM : i8) {
  %false = hw.constant false
  %c0_i8 = hw.constant 0 : i8
  %A9 = comb.concat %false, %A : i1, i8
  %B9 = comb.concat %false, %B : i1, i8
  %CI9 = comb.concat %c0_i8, %CI : i8, i1
  %sum = comb.add bin %A9, %B9, %CI9 : i9
  %CO = comb.extract %sum from 8 : (i9) -> i1
  %SUM = comb.extract %sum from 0 : (i9) -> i8
  hw.output %CO, %SUM : i1, i8
}
