# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2024 Jiuyang Liu <liu@jiuyang.me>

{ symlinkJoin, circt, mlir-install, stdenv }:
symlinkJoin {
  name = "circt-install";
  paths = [
    circt
    circt.lib
    circt.dev
  ];

  # Supply the missing C symbol in the pinned Firtool C API. Downstream Scala
  # Native applications link this alongside the other CIRCT CAPI libraries.
  nativeBuildInputs = [ stdenv.cc ];
  postBuild = ''
    $CXX -std=c++17 -O2 -DNDEBUG -fPIC -I$out/include -I${mlir-install}/include \
      -shared ${./firtool.cpp} -L${mlir-install}/lib -Wl,-rpath,${mlir-install}/lib \
      -lMLIRIR -lMLIRSupport -lLLVMSupport \
      -o $out/lib/libCIRCTCAPIZaoziFirtool${stdenv.hostPlatform.extensions.sharedLibrary}
  '';

  inherit (circt) meta;
}
