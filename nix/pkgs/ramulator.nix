# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>

# Ramulator 2, built as a plain C++ library: the DRAM model the demo SoC's
# testbench attaches to its memory port through DPI.
{ stdenv
, fetchFromGitHub
, cmake
, ninja
}:

let
  # Ramulator declares these through FetchContent, which wants the network at
  # configure time. They are unpacked where its declarations already look, and
  # FETCHCONTENT_FULLY_DISCONNECTED keeps CMake from going out for them.
  yaml-cpp = fetchFromGitHub {
    owner = "jbeder";
    repo = "yaml-cpp";
    rev = "yaml-cpp-0.9.0";
    hash = "sha256-+FOsPQY44h1g9tEw3O281LkiYKXdW2jnFKw+oTRkhGw=";
  };
  fmt = fetchFromGitHub {
    owner = "fmtlib";
    repo = "fmt";
    rev = "10.2.1";
    hash = "sha256-pEltGLAHLZ3xypD/Ur4dWPWJ9BGVXwqQyKcDWVmC3co=";
  };
in
stdenv.mkDerivation {
  pname = "ramulator";
  version = "2.1-unstable-2026-08-28";

  src = fetchFromGitHub {
    owner = "CMU-SAFARI";
    repo = "ramulator2";
    rev = "9ac28d3f60564c86a1eeb53a373929b17569360b";
    hash = "sha256-4l+iNG7Dh1R97Pih5DnfLqcROUjMV1e/6HchteNyFpM=";
  };

  nativeBuildInputs = [ cmake ninja ];

  postPatch = ''
    mkdir -p ext
    cp -r --no-preserve=mode,ownership ${yaml-cpp} ext/yaml-cpp
    cp -r --no-preserve=mode,ownership ${fmt} ext/fmt
  '';

  # The Python bindings only regenerate the DRAM models from Ramulator's own
  # DSL; the generated C++ is committed upstream, so the library needs neither
  # Python nor nanobind. Configurations are exported by that Python package —
  # this build consumes the exported YAML, it does not produce it.
  cmakeFlags = [
    "-DRAMULATOR_PYTHON_BINDINGS=OFF"
    "-DFETCHCONTENT_FULLY_DISCONNECTED=ON"
  ];

  # Ramulator has no install rules, and puts the library back in its source
  # tree (LIBRARY_OUTPUT_DIRECTORY = PROJECT_SOURCE_DIR).
  installPhase = ''
    runHook preInstall
    mkdir -p $out/lib
    cp $NIX_BUILD_TOP/source/libramulator.so $out/lib/
    cd $NIX_BUILD_TOP/source/src
    find ramulator -name '*.h' -exec install -Dm444 {} $out/include/{} \;
    runHook postInstall
  '';

  meta = {
    description = "Cycle-accurate DRAM simulator (library build)";
    homepage = "https://github.com/CMU-SAFARI/ramulator2";
  };
}
