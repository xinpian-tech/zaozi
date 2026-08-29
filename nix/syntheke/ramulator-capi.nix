# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>

# A C ABI over Ramulator. Ramulator's own interface is C++ — classes and a std::function callback — which nothing but
# C++ can call; this is the smallest surface that lets the Rust memory model reach it, and it lives with the packaging
# rather than with the simulation, which is Rust throughout.
{ lib
, stdenv
, ramulator
}:

stdenv.mkDerivation {
  pname = "ramulator-capi";
  version = ramulator.version;

  src = ./ramulator-capi;

  buildInputs = [ ramulator ];

  buildPhase = ''
    runHook preBuild
    $CXX -std=c++20 -O2 -fPIC -shared ramulator_capi.cc \
      -I. -I${ramulator}/include \
      -L${ramulator}/lib -lramulator -Wl,-rpath,${ramulator}/lib \
      -o libramulator_capi.so
    runHook postBuild
  '';

  installPhase = ''
    runHook preInstall
    install -Dm555 libramulator_capi.so "$out/lib/libramulator_capi.so"
    install -Dm444 ramulator_capi.h "$out/include/ramulator_capi.h"
    runHook postInstall
  '';

  meta = {
    description = "C ABI over Ramulator, for callers that are not C++";
    license = lib.licenses.asl20;
    platforms = lib.platforms.unix;
  };
}
