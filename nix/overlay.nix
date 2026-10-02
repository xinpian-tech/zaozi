# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2024 Jiuyang Liu <liu@jiuyang.me>
{ mvn-trace-forge, ... }:
final: prev:

let
  inherit (prev.llvmPackages_circt) libllvm mlir;
  circt = (prev.circt.override {
    buildSharedLibs = true;
  }).overrideAttrs (old: {
    patches = (old.patches or [ ]) ++ [ ./patches/sim-procedural-dpi.patch ./patches/dpi-string.patch ];
  });
in
{
  inherit (mvn-trace-forge.packages.${final.stdenv.hostPlatform.system}) mtf;

  circt-install = final.callPackage ./pkgs/circt-install.nix {
    inherit circt;
  };

  mlir-install = final.callPackage ./pkgs/mlir-install.nix {
    inherit libllvm mlir;
  };

  zaozi = final.callPackage ./zaozi { };
}
