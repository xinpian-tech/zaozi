# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2024 Jiuyang Liu <liu@jiuyang.me>
{
  description = "Zaozi: A Scala-based hardware design framework leveraging MLIR and CIRCT";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable-small";
    circt-nix.url = "github:xinpian-tech/circt-nix/xinpian-main";
    sn-bindgen.url = "github:indoorvivants/sn-bindgen/main";
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
            (final: _: { sn-bindgen = import ./nix/pkgs/sn-bindgen.nix {
              upstream = inputs.sn-bindgen;
              inherit system;
            }; })
            mvn-trace-forge.overlays.default
            overlay
            local-overlay
          ];
          inherit system;
        };
        scala3BspSemanticLs = scala3-bsp-semantic-ls.packages.${system}.default.override {
          rustWorkspace = scala3-bsp-semantic-ls.checks.${system}.rust-build.overrideAttrs (old: {
            patches = (old.patches or [ ]) ++ [ ./nix/patches/scala-native-presentation-compiler.patch ];
          });
        };
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
          default = pkgs.zaozi.package;
          zaozi = pkgs.zaozi.package;
          zaozi-runtime = pkgs.zaozi.package.override { withScaladoc = false; };
          mlir-install = pkgs.mlir-install;
          circt-install = pkgs.circt-install;
          sn-bindgen = pkgs.sn-bindgen;
          scala3-bsp-semantic-ls = scala3BspSemanticLs;
        };
        devShells.default = pkgs.mkShell {
          inputsFrom = [ pkgs.zaozi.package ];
          nativeBuildInputs = with pkgs; [ mtf nixd jdk25 sn-bindgen clang ] ++ lib.optionals stdenv.isLinux [
            scala3BspSemanticLs
            util-linux # taskset for the single-CPU elaboration test
          ];
          env = with pkgs; {
            CIRCT_INSTALL_PATH = circt-install;
            MLIR_INSTALL_PATH = mlir-install;
            SN_BINDGEN = "${sn-bindgen}/bin/bindgen";
            CLANG = "${clang}/bin/clang";
            CLANGPP = "${clang}/bin/clang++";
            LIBC_INCLUDE_PATH = "${stdenv.cc.libc.dev}/include";
            LIT_INSTALL_PATH = lit;
            # Share regular Mill outputs with BSP so SemanticDB produced by
            # `mill __.compile` is immediately visible to the language server.
            MILL_NO_SEPARATE_BSP_OUTPUT_DIR = "1";
            SCALA_CLI_INSTALL_PATH = scala-cli;
            RISCV_OPCODES_INSTALL_PATH = riscv-opcodes;
            Z3_LIB = "${z3.lib}/lib/libz3.so";
          };
          shellHook = ''
            ${pkgs.lib.optionalString pkgs.stdenv.isLinux ''
              install -Dm644 ${zedSettings} .zed/settings.json
            ''}
          '';
        };
      }
    );
}
