# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2024 Jiuyang Liu <liu@jiuyang.me>
final: prev:

{
  mill = prev.millVersions.mill_1_1_2.override { jre = final.jdk25; };

  riscv-opcodes = final.callPackage ./pkgs/riscv-opcodes.nix { };

  espresso = final.callPackage ./pkgs/espresso.nix { };
}
