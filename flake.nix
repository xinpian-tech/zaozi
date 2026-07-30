# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2024 Jiuyang Liu <liu@jiuyang.me>
{
  description = "Zaozi: A Scala-based hardware design framework leveraging MLIR and CIRCT";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable-small";
    circt-nix = {
      url = "github:xinpian-tech/circt-nix/xinpian-main";
      inputs.circt-src.url = "github:xinpian-tech/circt/master";
    };
    flake-utils.url = "github:numtide/flake-utils";
    mvn-trace-forge.url = "github:Avimitin/mvn-trace-forge";
    scala3-bsp-semantic-ls.url = "github:xinpian-tech/scala3-bsp-semantic-ls";
  };

  outputs =
    inputs@{ self
    , nixpkgs
    , flake-utils
    , mvn-trace-forge
    , circt-nix
    , scala3-bsp-semantic-ls
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
        zedSettings = pkgs.writeText "zaozi-zed-settings.json" ''
          {
            "languages": {
              "Scala": {
                "language_servers": ["scala3-bsp-semantic-ls"]
              }
            },
            "lsp": {
              "scala3-bsp-semantic-ls": {
                "binary": {
                  "path": "${scala3BspSemanticLs}/bin/scala3-bsp-semantic-ls",
                  "arguments": []
                }
              }
            }
          }
        '';
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
            ${pkgs.lib.optionalString pkgs.stdenv.isLinux ''
              install -Dm644 ${zedSettings} .zed/settings.json
            ''}
          '';
        };
      }
    );
}
