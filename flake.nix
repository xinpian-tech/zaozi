# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2024 Jiuyang Liu <liu@jiuyang.me>
{
  description = "Zaozi: A Scala-based hardware design framework leveraging MLIR and CIRCT";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable-small";
    circt-nix.url = "github:xinpian-tech/circt-nix/xinpian-main";
    flake-utils.url = "github:numtide/flake-utils";
    mvn-trace-forge.url = "github:Avimitin/mvn-trace-forge";
    scala3-bsp-semantic-ls.url = "github:xinpian-tech/scala3-bsp-semantic-ls";
    scala3-bsp-semantic-ls-zed-plugin.url = "github:xinpian-tech/scala3-bsp-semantic-ls-zed";
  };

  outputs =
    inputs@{ self
    , nixpkgs
    , flake-utils
    , mvn-trace-forge
    , circt-nix
    , scala3-bsp-semantic-ls
    , scala3-bsp-semantic-ls-zed-plugin
    , ...
    }:
    let
      overlay = import ./nix/overlay.nix inputs;
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
            mvn-trace-forge.overlays.default
            overlay
            local-overlay
          ];
          inherit system;
        };
        scala3BspSemanticLs = scala3-bsp-semantic-ls.packages.${system}.default;
        scala3BspSemanticLsZedPlugin = scala3-bsp-semantic-ls-zed-plugin.packages.${system}.default;
        zedInstallHook = pkgs.callPackage ./nix/pkgs/zed-install-hook.nix {
          inherit scala3BspSemanticLs scala3BspSemanticLsZedPlugin;
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
          zed-install-hook = zedInstallHook;
        }
        // pkgs.lib.optionalAttrs pkgs.stdenv.isLinux {
          zed-settings = zedInstallHook.settings;
        };
        devShells.default = pkgs.mkShell {
          inputsFrom = [
            zedInstallHook
            pkgs.zaozi.zaozi-assembly
          ];
          nativeBuildInputs = with pkgs; [ mtf nixd jdk25 ] ++ lib.optionals stdenv.isLinux [
            scala3BspSemanticLs
          ];
          env = with pkgs; {
            CIRCT_INSTALL_PATH = circt-install;
            MLIR_INSTALL_PATH = mlir-install;
            JEXTRACT_INSTALL_PATH = jextract;
            LIBC_INCLUDE_PATH = "${stdenv.cc.libc.dev}/include";
            LIT_INSTALL_PATH = lit;
            # Share regular Mill outputs with BSP so SemanticDB produced by
            # `mill __.compile` is immediately visible to the language server.
            MILL_NO_SEPARATE_BSP_OUTPUT_DIR = "1";
            SCALA_CLI_INSTALL_PATH = scala-cli;
            RISCV_OPCODES_INSTALL_PATH = riscv-opcodes;
            Z3_LIB = "${z3.lib}/lib/libz3.so";
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
