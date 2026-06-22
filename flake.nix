# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2024 Jiuyang Liu <liu@jiuyang.me>
{
  description = "Zaozi: A Scala-based hardware design framework leveraging MLIR and CIRCT";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable-small";
    circt-src = {
      type = "github";
      owner = "llvm";
      repo = "circt";
      ref = "main";
      flake = false;
    };
    llvm-src = {
      type = "github";
      owner = "llvm";
      repo = "llvm-project";
      # from CIRCT submodule
      rev = "e6566c571aead7b48bdf13a8c170515abaeea74e";
      flake = false;
    };
    circt-nix = {
      url = "github:unlsycn/circt-nix";
      inputs = {
        nixpkgs.follows = "nixpkgs";
        circt-src.follows = "circt-src";
        llvm-submodule-src.follows = "llvm-src";
      };
    };
    flake-utils.url = "github:numtide/flake-utils";
    mill-ivy-fetcher.url = "github:Avimitin/mill-ivy-fetcher";
    # Pinned Scala 3.8.3 source for the patched same-version compiler/PC fork
    # (Metals integration). 3.8.3 is the latest Scala 3 the Metals line supports
    # (1.6.7 caps at 3.8.3); 3.8.4 is unsupported by every released Metals.
    # flake=false: consumed as a source tree by a nix derivation that builds the
    # marker-only patched jars.
    scala3-src = {
      type = "github";
      owner = "scala";
      repo = "scala3";
      ref = "3.8.3";
      flake = false;
    };
  };

  outputs =
    inputs@{
      self,
      nixpkgs,
      flake-utils,
      mill-ivy-fetcher,
      circt-nix,
      ...
    }:
    let
      overlay = import ./nix/overlay.nix;
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
            mill-ivy-fetcher.overlays.mill-overlay
            overlay
            local-overlay
            mill-ivy-fetcher.overlays.default
          ];
          inherit system;
        };
        # Same-version 3.8.3 compiler + presentation-compiler jars built from the pinned
        # scala3 source (fixed-output, needs network for the dotty dep closure).
        # proxyHost/proxyPort are null by default (normal network); pass them via
        # .override for a proxied sandbox.
        scala3-shadow-jars = pkgs.callPackage ./nix/pkgs/scala3-shadow-jars.nix {
          scala3-src = inputs.scala3-src;
          srcRev = inputs.scala3-src.rev or "unknown";
        };
        scala3-shadow-cache = pkgs.callPackage ./nix/pkgs/scala3-shadow-cache.nix {
          inherit scala3-shadow-jars;
        };
        # Metals 1.6.7 packaged via coursier (supports Scala 3.8.3; nixpkgs only ships 1.6.5,
        # which caps Scala 3 support at 3.8.0).
        metals_1_6_7 = pkgs.callPackage ./nix/pkgs/metals-bin.nix { };
        # Metals-ready cache: the shadow cache + the extra offline closure Metals' PC-setup
        # path needs, fetched coherently into the shadow cache.
        scala3-shadow-metals-extra = pkgs.callPackage ./nix/pkgs/scala3-shadow-metals-extra.nix {
          inherit scala3-shadow-cache;
        };
      in
      {
        formatter = pkgs.nixpkgs-fmt;
        legacyPackages = pkgs;
        packages = {
          default = pkgs.zaozi.zaozi-assembly;
          zaozi-assembly = pkgs.zaozi.zaozi-assembly;
          mlir-install = pkgs.mlir-install;
          circt-install = pkgs.circt-install;
          # Metals integration: reproducible dotty 3.8.3 sbt build-load gate, driven
          # by this flake's pinned sbt/jdk21/git over the flake-pinned scala3 source
          # (inputs.scala3-src). Runs as an app (needs network for sbt plugin
          # resolution, so not a sandboxed `nix flake check`):
          #   PXY_HOST=.. PXY_PORT=.. nix run .#scala3-build-load
          scala3-build-load = pkgs.writeShellApplication {
            name = "scala3-build-load";
            runtimeInputs = [ pkgs.sbt pkgs.jdk21 pkgs.git ];
            text = ''
              exec bash ${./nix/checks/scala3-build-load.sh} "${inputs.scala3-src}" "$@"
            '';
          };
          inherit scala3-shadow-jars;
          # Isolated coursier cache shadowing the patched jars: the Zaozi compile closure
          # plus the presentation-compiler closure, with the patched compiler/PC jars
          # overlaid at their stock coordinates. The sole authoritative cache both consumer
          # JVMs (Mill/scalac and Metals/PC) resolve from. Fixed-output (coursier fetches
          # the PC closure); pass proxyHost/proxyPort via .override for a proxied sandbox.
          inherit scala3-shadow-cache;
          # Repeatable verification gate for the shadow artifacts: asserts the patched
          # jar + cache contract, hashes.json consistency, patched != stock, and proves
          # the marker patch is inert (patched compiler with the property unset produces
          # compile output identical to stock 3.8.3 and emits no marker; with the
          # property set it emits the gated marker). Run: nix run .#scala3-shadow-artifacts
          scala3-shadow-artifacts =
            let
              stockJar = name: hash: pkgs.fetchurl {
                inherit name hash;
                url = "https://repo1.maven.org/maven2/org/scala-lang/${name}/3.8.3/${name}-3.8.3.jar";
              };
              stockCompiler = stockJar "scala3-compiler_3" "sha256-zV5apUthDjdSLe6hk0Sp2WFNX7yc8FLeCKZs63waiXQ=";
              stockPc = stockJar "scala3-presentation-compiler_3" "sha256-AO0MabUpXkrwRFjwkJ28oox44GtXdNNGwC81izdlzvk=";
            in
            pkgs.writeShellApplication {
              name = "scala3-shadow-artifacts";
              runtimeInputs = with pkgs; [ bash jdk21 unzip jq diffutils coreutils gnugrep findutils ];
              text = ''
                exec bash ${./nix/checks/scala3-shadow-artifacts.sh} \
                  --shadow-jars "${scala3-shadow-jars}" \
                  --shadow-cache "${scala3-shadow-cache}" \
                  --stock-compiler "${stockCompiler}" \
                  --stock-pc "${stockPc}" "$@"
              '';
            };
          # Proves the shadow cache wins the REAL Mill/coursier compiler resolution: for
          # each cache state, an isolated COURSIER_CACHE (+ isolated HOME/IVY_HOME, no
          # network) is the sole source for `mill --no-daemon --offline show
          # zaozi.scalaCompilerClasspath`; the resolved compiler jar path + hash + the
          # gated marker are checked, with the patched/empty/stock cache-state matrix. The
          # stock cache is the existing ivy-gather zaozi-lock cache; the workspace is this
          # flake's own source. Run: nix run .#scala3-shadow-resolution
          scala3-shadow-resolution =
            let
              stockCompiler = pkgs.fetchurl {
                name = "scala3-compiler_3-3.8.3.jar";
                url = "https://repo1.maven.org/maven2/org/scala-lang/scala3-compiler_3/3.8.3/scala3-compiler_3-3.8.3.jar";
                hash = "sha256-zV5apUthDjdSLe6hk0Sp2WFNX7yc8FLeCKZs63waiXQ=";
              };
            in
            pkgs.writeShellApplication {
              name = "scala3-shadow-resolution";
              runtimeInputs = with pkgs; [ bash mill jdk21 jq gnugrep findutils coreutils gnutar ];
              text = ''
                exec bash ${./nix/checks/scala3-shadow-resolution.sh} \
                  --shadow-cache "${scala3-shadow-cache}" \
                  --shadow-jars "${scala3-shadow-jars}" \
                  --stock-cache "${pkgs.ivy-gather ./nix/zaozi/zaozi-lock.nix}" \
                  --stock-compiler "${stockCompiler}" \
                  --workspace "${self}" "$@"
              '';
            };
          inherit metals_1_6_7 scala3-shadow-metals-extra;
          # AC-4 Metals/PC gate: drive headless Metals 1.6.7 against an isolated copy of the
          # shadow cache (+ the Metals PC-setup closure) and prove textDocument/completion
          # returns __zaozi_marker__ from the patched 3.8.3 PC, with JVM-side PC jar hash
          # provenance == hashes.json; empty cache fails offline; stock PC returns no marker.
          # Run: nix run .#scala3-shadow-metals-resolution
          scala3-shadow-metals-resolution =
            let
              stockPc = pkgs.fetchurl {
                name = "scala3-presentation-compiler_3-3.8.3.jar";
                url = "https://repo1.maven.org/maven2/org/scala-lang/scala3-presentation-compiler_3/3.8.3/scala3-presentation-compiler_3-3.8.3.jar";
                hash = "sha256-AO0MabUpXkrwRFjwkJ28oox44GtXdNNGwC81izdlzvk=";
              };
              stockCompiler = pkgs.fetchurl {
                name = "scala3-compiler_3-3.8.3.jar";
                url = "https://repo1.maven.org/maven2/org/scala-lang/scala3-compiler_3/3.8.3/scala3-compiler_3-3.8.3.jar";
                hash = "sha256-zV5apUthDjdSLe6hk0Sp2WFNX7yc8FLeCKZs63waiXQ=";
              };
            in
            pkgs.writeShellApplication {
              name = "scala3-shadow-metals-resolution";
              runtimeInputs = with pkgs; [ bash mill jdk21 python3 jq gnugrep findutils coreutils gnutar ];
              text = ''
                exec bash ${./nix/checks/scala3-shadow-metals-resolution.sh} \
                  --metals "${metals_1_6_7}" \
                  --metals-cache "${scala3-shadow-metals-extra}" \
                  --shadow-jars "${scala3-shadow-jars}" \
                  --stock-pc "${stockPc}" \
                  --stock-compiler "${stockCompiler}" \
                  --probe "${./nix/checks/metals_lsp_probe.py}" \
                  --mill "${pkgs.mill}" "$@"
              '';
            };
        };
        devShells.default = pkgs.mkShell {
          inputsFrom = [ pkgs.zaozi.zaozi-assembly ];
          # metals pinned (nixpkgs 1.6.5) for the Metals/IDE integration;
          # launching the editor from this dev shell is the supported path for
          # the shadowed PC (DEC-2). nix/checks/metals-pc-smoke.sh is only an
          # artifact/JDK load helper — it checks that the stock
          # scala3-presentation-compiler_3:3.8.3 class loads under the dev-shell
          # JDK; it does NOT start Metals. Proving Metals itself resolves+loads
          # the 3.8.x PC on a BSP workspace is the headless-LSP harness
          # (see doc/shadow-layout.md).
          nativeBuildInputs = with pkgs; [ nixd jdk25 metals ];
          env = with pkgs; {
            CIRCT_INSTALL_PATH = circt-install;
            MLIR_INSTALL_PATH = mlir-install;
            JEXTRACT_INSTALL_PATH = jextract;
            LIBC_INCLUDE_PATH = "${stdenv.cc.libc.dev}/include";
            LIT_INSTALL_PATH = lit;
            SCALA_CLI_INSTALL_PATH = scala-cli;
            RISCV_OPCODES_INSTALL_PATH = riscv-opcodes;
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
          '';
        };
      }
    );
}
