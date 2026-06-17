# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2024 Jiuyang Liu <liu@jiuyang.me>
final: prev:

let
  # LLVM's CMake adds `-D_LIBCPP_HARDENING_MODE=...` globally while the pinned
  # nixpkgs' cc-wrapper injects the same macro with a different value, producing a
  # (harmless) "redefined" warning on every TU. LLVM's bundled third-party/benchmark
  # is the only subproject built with -Werror/-pedantic-errors, so there the warning
  # becomes fatal and breaks the build. We don't need those benchmarks for
  # circt-install, so just don't build them.
  #
  # We do this inside the LLVM package scope (overrideScope) and take BOTH libllvm
  # and mlir from that one scope, so mlir is built against the same fixed LLVM
  # instead of building a second (benchmark-enabled) copy for itself. The original
  # `.override { buildSharedLibs = true; }` re-evaluated circt-nix's llvm.nix once
  # per package, which produced two separate LLVM builds — only one of which we
  # could reach. The exposed scope defaults to BUILD_SHARED_LIBS=OFF, so we re-add
  # the shared-libs flag here (the only thing buildSharedLibs=true changed).
  llvmScope = prev.llvmPackages_circt.llvmPkgs.overrideScope (
    lfinal: lprev: {
      libllvm = lprev.libllvm.overrideAttrs (old: {
        cmakeFlags = old.cmakeFlags ++ [ "-DBUILD_SHARED_LIBS:BOOL=ON" "-DLLVM_INCLUDE_BENCHMARKS=OFF" ];
        passthru = old.passthru // { buildSharedLibs = true; };
      });
      mlir = lprev.mlir.overrideAttrs (old: {
        cmakeFlags = old.cmakeFlags ++ [ "-DBUILD_SHARED_LIBS:BOOL=ON" ];
        passthru = old.passthru // { buildSharedLibs = true; };
      });
    }
  );
in
{
  libllvm = llvmScope.libllvm;
  mlir = llvmScope.mlir;

  circt = prev.circt.override {
    buildSharedLibs = true;
    inherit (final) libllvm mlir;
  };

  circt-install = final.callPackage ./pkgs/circt-install.nix { };

  mlir-install = final.callPackage ./pkgs/mlir-install.nix { };

  zaozi = final.callPackage ./zaozi { };
}
