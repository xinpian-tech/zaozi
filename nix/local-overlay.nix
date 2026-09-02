# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2024 Jiuyang Liu <liu@jiuyang.me>
final: prev:

{
  mill = prev.millVersions.mill_1_1_2.override { jre = final.jdk25; };

  riscv-opcodes = final.callPackage ./pkgs/riscv-opcodes.nix { };

  espresso = final.callPackage ./pkgs/espresso.nix { };

  # Only syntheke's demo needs these.
  syntheke = rec {
    ramulator = final.callPackage ./syntheke/ramulator.nix { };
    ramulator-capi = final.callPackage ./syntheke/ramulator-capi.nix { inherit ramulator; };
    dpi = final.callPackage ./syntheke/dpi.nix { inherit ramulator-capi; };
    simprobe = final.callPackage ./syntheke/simprobe.nix { };
    riscv-toolchain = final.callPackage ./syntheke/riscv-toolchain.nix { };
  };
}
