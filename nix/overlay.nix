# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2024 Jiuyang Liu <liu@jiuyang.me>
{ mvn-trace-forge, ... }:
final: prev:

{
  inherit (mvn-trace-forge.packages.${final.stdenv.hostPlatform.system}) mtf;

  circt-install = final.callPackage ./pkgs/circt-install.nix { };

  mlir-install = final.callPackage ./pkgs/mlir-install.nix { };

  zaozi = final.callPackage ./zaozi { };
}
