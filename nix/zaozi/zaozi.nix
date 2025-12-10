# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2024 Jiuyang Liu <liu@jiuyang.me>

{ lib
, stdenv
, makeWrapper
, mill
, circt-install
, mlir-install
, jextract-21
, lit
, scala-cli
, add-determinism
, z3
, espresso
, mill-ivy-fetcher
, ivy-gather
, writeShellApplication
, mill-ivy-env-shell-hook

}:

let
  self = stdenv.mkDerivation rec {
    name = "zaozi";

    src = with lib.fileset;
      toSource {
        root = ./../..;
        fileset = unions [
          ./../../build.mill
          ./../../circtlib
          ./../../mlirlib
          ./../../decoder
          ./../../zaozi
          ./../../smtlib
          ./../../rvdecoderdb
          ./../../omlib
          ./../../rvprobe
        ];
      };

    passthru.bump = writeShellApplication {
      name = "bump-zaozi-mill-lock";
      runtimeInputs = [
        mill
        mill-ivy-fetcher
      ];
      text = ''
        mif run -p "${src}" -o ./nix/zaozi/zaozi-lock.nix "$@"
      '';
    };

    buildInputs = [ (ivy-gather ./zaozi-lock.nix) ];

    nativeBuildInputs = [
      mill
      mill.jre
      circt-install
      mlir-install
      jextract-21
      lit
      scala-cli
      add-determinism
      makeWrapper
      z3
      espresso
    ];

    shellHook = ''
      ${mill-ivy-env-shell-hook}

      mill --no-daemon mill.bsp.BSP/install
      # other commands
    '';

    env.CIRCT_INSTALL_PATH = circt-install;
    env.MLIR_INSTALL_PATH = mlir-install;
    env.JEXTRACT_INSTALL_PATH = jextract-21;
    env.LIT_INSTALL_PATH = lit;
    env.JAVA_TOOL_OPTIONS = "--enable-preview -Djextract.decls.per.header=65535";

    outputs = [ "out" ];

    # FIXME: wait https://github.com/com-lihaoyi/mill/pull/5521
    buildPhase = ''
      mill --no-daemon --offline '__.assembly'
    '';

    installPhase = ''
      mkdir -p $out/share/java

      add-determinism -j $NIX_BUILD_CORES out/zaozi/assembly.dest/out.jar

      mv out/zaozi/assembly.dest/out.jar $out/share/java/elaborator.jar
    '';
  };
in
self
