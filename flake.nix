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
      rev = "e6566c571aead7b48bdf13a8c170515abaeea74e";
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
    # Pinned Scala 3.8.4 source for the patched same-version compiler/PC fork
    # (Metals integration). flake=false: consumed as a source tree by a nix
    # derivation that builds the marker-only patched jars.
    scala3-src = {
      type = "github";
      owner = "scala";
      repo = "scala3";
      ref = "3.8.4";
      flake = false;
    };
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
        # Same-version 3.8.4 compiler + presentation-compiler jars built from the pinned
        # scala3 source (fixed-output, needs network for the dotty dep closure).
        # proxyHost/proxyPort are null by default (normal network); pass them via
        # .override for a proxied sandbox.
        scala3-shadow-jars = pkgs.callPackage ./nix/pkgs/scala3-shadow-jars.nix {
          scala3-src = inputs.scala3-src;
          srcRev = inputs.scala3-src.rev or "unknown";
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
          # Metals integration: reproducible dotty 3.8.4 sbt build-load gate, driven
          # by this flake's pinned sbt/jdk21/git over the flake-pinned scala3 source
          # (inputs.scala3-src). Runs as an app (needs network for sbt plugin
          # resolution, so not a sandboxed `nix flake check`):
          #   PXY_HOST=.. PXY_PORT=.. nix run .#scala3-build-load
          scala3-build-load = pkgs.writeShellApplication {
            name = "scala3-build-load";
            runtimeInputs = [ pkgs.sbt pkgs.jdk21 pkgs.git ];
            text = ''
              exec bash ${./nix/checks/scala3-build-load.sh} "${inputs.scala3-src}" "$@"
            '';
          };
          inherit scala3-shadow-jars;
          # Isolated coursier cache shadowing the patched jars: the Zaozi compile closure
          # plus the presentation-compiler closure, with the patched compiler/PC jars
          # overlaid at their stock coordinates. The sole authoritative cache task5 points
          # both consumer JVMs at. Fixed-output (coursier fetches the PC closure); pass
          # proxyHost/proxyPort via .override for a proxied sandbox.
          scala3-shadow-cache = pkgs.callPackage ./nix/pkgs/scala3-shadow-cache.nix {
            inherit scala3-shadow-jars;
          };
        };
        devShells.default = pkgs.mkShell {
          inputsFrom = [ pkgs.zaozi.zaozi-assembly ];
          # metals pinned (nixpkgs 1.6.5) for the Metals/IDE integration;
          # launching the editor from this dev shell is the supported path for
          # the shadowed PC (DEC-2). nix/checks/metals-pc-smoke.sh is only an
          # artifact/JDK load helper — it checks that the stock
          # scala3-presentation-compiler_3:3.8.4 class loads under the dev-shell
          # JDK; it does NOT start Metals. Proving Metals itself resolves+loads
          # the 3.8.x PC on a BSP workspace is the headless-LSP harness
          # (see doc/shadow-layout.md).
          nativeBuildInputs = with pkgs; [ nixd jdk25 metals ];
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
