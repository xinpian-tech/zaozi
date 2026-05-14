# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2024 Jiuyang Liu <liu@jiuyang.me>
{
  description = "Zaozi: A Scala-based hardware design framework leveraging MLIR and CIRCT";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable-small";
    circt-src = {
      type = "github";
      owner = "llvm";
      repo = "circt";
      ref = "main";
      flake = false;
    };
    llvm-src = {
      type = "github";
      owner = "llvm";
      repo = "llvm-project";
      # from CIRCT submodule
      rev = "055b1efc1fe34106a8dc00a667708d5619077206";
      flake = false;
    };
    circt-nix = {
      url = "github:unlsycn/circt-nix";
      inputs = {
        nixpkgs.follows = "nixpkgs";
        circt-src.follows = "circt-src";
        llvm-submodule-src.follows = "llvm-src";
      };
    };
    flake-utils.url = "github:numtide/flake-utils";
    mill-ivy-fetcher.url = "github:Avimitin/mill-ivy-fetcher";
  };

  outputs =
    inputs@{
      self,
      nixpkgs,
      flake-utils,
      mill-ivy-fetcher,
      circt-nix,
      ...
    }:
    let
      overlay = import ./nix/overlay.nix;
      local-overlay = import ./nix/local-overlay.nix;
    in
    {
      # System-independent attr
      inherit inputs;
      overlays.default = overlay;
    }
    // flake-utils.lib.eachDefaultSystem (
      system:
      let
        pkgs = import nixpkgs {
          overlays = [
            circt-nix.overlays.default
            mill-ivy-fetcher.overlays.mill-overlay
            overlay
            local-overlay
            mill-ivy-fetcher.overlays.default
          ];
          inherit system;
        };
      in
      {
        formatter = pkgs.nixpkgs-fmt;
        legacyPackages = pkgs;
        packages = {
          default = pkgs.zaozi.zaozi-assembly;
          zaozi-assembly = pkgs.zaozi.zaozi-assembly;
          mlir-install = pkgs.mlir-install;
          circt-install = pkgs.circt-install;
        };
        devShells.default = pkgs.mkShell {
          inputsFrom = [ pkgs.zaozi.zaozi-assembly ];
          nativeBuildInputs = with pkgs; [ nixd jdk25 ];
          env = with pkgs; {
            CIRCT_INSTALL_PATH = circt-install;
            MLIR_INSTALL_PATH = mlir-install;
            JEXTRACT_INSTALL_PATH = jextract;
            LIBC_INCLUDE_PATH = "${stdenv.cc.libc.dev}/include";
            LIT_INSTALL_PATH = lit;
            SCALA_CLI_INSTALL_PATH = scala-cli;
            RISCV_OPCODES_INSTALL_PATH = riscv-opcodes;
          };
          # -Djextract.decls.per.header=65535 is scoped to the jextract
          # subprocess via PanamaModule.jextractEnv in build.mill, so it no
          # longer leaks into mill, scalac, or every test/lit fork.
          #
          # -Xss32m stays in the global JAVA_TOOL_OPTIONS because scalac's
          # JavaParser deep-recurses through the 95K-line single-class CAPI.java
          # the jextract.decls.per.header property forces jextract to emit;
          # without it scalac throws StackOverflowError in pullOutFirstConstr.
          shellHook = ''
            export JAVA_TOOL_OPTIONS="$JAVA_TOOL_OPTIONS -Xss32m"
          '';
        };
      }
    );
}
