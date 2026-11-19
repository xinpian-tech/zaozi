# SPDX-License-Identifier: Apache-2.0
{ lib, stdenv, mill, jdk25, circt-install, mlir-install, sn-bindgen, clang,
  python3, which, lit, scala-cli, z3, espresso, riscv-opcodes, mkMavenRepository,
  withScaladoc ? true }:
let
  publish = if withScaladoc then "publishM2Local" else "publishM2LocalRuntime";
in
stdenv.mkDerivation {
  pname = "zaozi";
  version = "0.0.1-SNAPSHOT";
  src = with lib.fileset; toSource {
    root = ../..;
    fileset = unions [
      ../../build.mill ../../native ../../zaozi-compiler-plugin ../../circtlib
      ../../mlirlib ../../decoder ../../zaozi ../../smtlib ../../stdlib
      ../../testlib ../../rvdecoderdb ../../omlib
    ];
  };
  nativeBuildInputs = [ mill jdk25 sn-bindgen clang python3 which lit scala-cli espresso z3 ];
  buildInputs = [ circt-install mlir-install z3 (mkMavenRepository { lockFile = ../../mtf.lock.json; }) ];
  env = {
    # Scaladoc traverses the generated bindings, including deeply nested C types.
    JAVA_TOOL_OPTIONS = "-Xss32m";
    MLIR_INSTALL_PATH = mlir-install;
    CIRCT_INSTALL_PATH = circt-install;
    RISCV_OPCODES_INSTALL_PATH = riscv-opcodes;
    LIBC_INCLUDE_PATH = "${stdenv.cc.libc.dev}/include";
    SN_BINDGEN = "${sn-bindgen}/bin/bindgen";
    CLANG = "${clang}/bin/clang";
    CLANGPP = "${clang}/bin/clang++";
    LIT_INSTALL_PATH = lit;
    Z3_LIB = "${z3.lib}/lib/libz3.so";
  };
  buildPhase = ''
    runHook preBuild
    mill --no-daemon --offline -j "$NIX_BUILD_CORES" zaozi.opt.nativeLink + __.${publish} --m2RepoPath "$PWD/maven" || {
      test ! -f out/mill-launcher/log || cat out/mill-launcher/log >&2
      exit 1
    }
    runHook postBuild
  '';
  installPhase = ''
    install -Dm755 out/zaozi/opt/nativeLink.dest/out $out/bin/zaozi-opt
    mkdir -p $out/share
    cp -r maven $out/share/maven
  '';
}
