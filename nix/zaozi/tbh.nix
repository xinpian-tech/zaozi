# SPDX-License-Identifier: Apache-2.0

{ lib
, stdenvNoCC
, stdenv
, makeWrapper
, python3
, ripgrep
, verilator
, circt-install
, mlir-install
, mill
, jdk25
, jextract
, lit
, z3
, scala-cli
, espresso
, mkMavenRepository
, gnumake
, coreutils
}:

let
  python = python3.withPackages (packages: [ packages.cffi ]);
  mavenRepository = mkMavenRepository { lockFile = ./../../mtf.lock.json; };
  toolPath = lib.makeBinPath [
    ripgrep
    verilator
    circt-install
    mlir-install
    mill
    jdk25
    jextract
    lit
    z3
    scala-cli
    espresso
    stdenv.cc
    gnumake
    coreutils
  ];
in
stdenvNoCC.mkDerivation {
  pname = "zaozi-tbh";
  version = "0.0.1";

  src = with lib.fileset;
    toSource {
      root = ./../..;
      fileset = ./../../tblib/tbh;
    };

  nativeBuildInputs = [ makeWrapper ];

  installPhase = ''
    runHook preInstall
    mkdir -p $out/bin $out/share/zaozi
    cp -r tblib/tbh $out/share/zaozi/tbh
    cat > $out/share/zaozi/tbh/toolchain.json <<EOF
    {
      "python": "${python}/bin/python3",
      "verilator": "${verilator}/bin/verilator",
      "path": "${toolPath}",
      "circt": "${circt-install}",
      "mlir": "${mlir-install}",
      "java": "${jdk25}",
      "jextract": "${jextract}",
      "libc_include": "${stdenv.cc.libc.dev}/include",
      "lit": "${lit}",
      "z3_lib": "${z3.lib}/lib/libz3.so",
      "maven_repository": "file://${mavenRepository}"
    }
    EOF
    makeWrapper ${python}/bin/python3 $out/bin/tbh \
      --add-flags "-B $out/share/zaozi/tbh/tbh" \
      --prefix PATH : "${toolPath}"
    runHook postInstall
  '';
}
