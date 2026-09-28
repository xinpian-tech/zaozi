# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>

# What assembles the program the demo's debugger downloads. LLVM targets RISC-V out of the box, so this is three
# packages joined into one prefix rather than a cross toolchain to build; nothing goes on PATH, because `ld` and `nm`
# here are not the ones anything else in this shell wants.
{ symlinkJoin
, llvmPackages
, lld
}:

symlinkJoin {
  name = "riscv-toolchain";
  paths = [ llvmPackages.clang-unwrapped lld llvmPackages.llvm ];
}
