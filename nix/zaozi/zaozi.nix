# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2024 Jiuyang Liu <liu@jiuyang.me>

{ lib
, stdenv
, makeWrapper
, mill
, jdk25
, circt-install
, mlir-install
, jextract
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
          ./../../stdlib
          ./../../testlib
          ./../../rvdecoderdb
          ./../../omlib
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
      jdk25
      circt-install
      mlir-install
      jextract
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
    env.JEXTRACT_INSTALL_PATH = jextract;
    env.LIBC_INCLUDE_PATH = "${stdenv.cc.libc.dev}/include";
    env.LIT_INSTALL_PATH = lit;
    # -Djextract.decls.per.header=65535 is scoped to PanamaModule's jextract
    # subprocess (see build.mill jextractEnv). -Xss32m stays global because
    # scalac's JavaParser deep-recurses through the 95K-line single-class
    # CAPI.java the jextract.decls.per.header property forces jextract to
    # emit; without -Xss32m scalac throws StackOverflowError.
    env.JAVA_TOOL_OPTIONS = "-Xss32m";

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
