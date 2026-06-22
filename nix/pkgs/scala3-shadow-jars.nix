# Builds same-version 3.8.4 scala3-compiler_3 and scala3-presentation-compiler_3
# jars FROM SOURCE (the pinned scala3-src), for the Metals/IDE shadow toolchain.
#
# Why a fixed-output derivation (FOD): the dotty sbt build resolves its plugin/dep
# closure from Maven, which a normal sandboxed derivation cannot do (no network).
# A FOD is granted network access; in exchange its output must be content-addressed,
# so the jars are normalised with add-determinism (already used by zaozi.nix) to keep
# the output hash stable across rebuilds.
#
# Exact version: dotty's Build.scala sets the version to `baseVersion` (= "3.8.4")
# only when `isRelease` (env RELEASEBUILD=yes); otherwise it appends `-bin-SNAPSHOT`.
# So RELEASEBUILD=yes yields the exact stock coordinate `3.8.4` (no custom suffix).
#
# Proxy: in a proxied/sandboxed network, pass proxyHost/proxyPort so sbt's resolver
# can reach Maven (the FOD build env is minimal). In a normal network leave them null.
{ lib
, stdenv
, sbt
, jdk21
, git
, unzip
, add-determinism
, scala3-src
, version ? "3.8.4"
, proxyHost ? null
, proxyPort ? null
  # Fixed-output hash of the normalised jar set. TOFU: build once with lib.fakeHash,
  # then pin the hash nix reports.
, outputHash ? lib.fakeHash
}:

stdenv.mkDerivation {
  pname = "scala3-shadow-jars";
  inherit version;
  src = scala3-src;

  nativeBuildInputs = [ sbt jdk21 git unzip add-determinism ];

  outputHashMode = "recursive";
  outputHashAlgo = "sha256";
  inherit outputHash;

  # Drives Build.scala `isRelease` => version == "3.8.4" exactly.
  RELEASEBUILD = "yes";

  dontConfigure = true;

  buildPhase = ''
    runHook preBuild
    export HOME="$TMPDIR/home" COURSIER_CACHE="$TMPDIR/cs" XDG_CACHE_HOME="$TMPDIR/xdg"
    mkdir -p "$HOME" "$COURSIER_CACHE" "$XDG_CACHE_HOME"

    proxyOpts=""
    ${lib.optionalString (proxyHost != null) ''
      proxyOpts="-Dhttp.proxyHost=${proxyHost} -Dhttp.proxyPort=${toString proxyPort} -Dhttps.proxyHost=${proxyHost} -Dhttps.proxyPort=${toString proxyPort}"
    ''}
    export SBT_OPTS="$proxyOpts -Dsbt.server=false -Dsbt.ci=true -Xmx6g"
    export JAVA_TOOL_OPTIONS="$proxyOpts"

    # Writable copy; dotty VersionUtil reads git via jgit, so init a repo (the pinned
    # source has no .git). Keep project/project (wires the recursive meta-build).
    cp -r "$src" work && chmod -R u+w work && cd work
    git init -q
    git -c user.email=build@nix.local -c user.name=nix commit -q --allow-empty -m "scala3 ${version} shadow source"

    # TODO(next round): apply the in-source sentinel patch here (META-INF/zaozi-shadow
    # resources + property-gated -Dzaozi.shadow.marker behavioural markers) before package.

    sbt -batch -no-colors "scala3-compiler-bootstrapped/package; scala3-presentation-compiler/package"
    runHook postBuild
  '';

  installPhase = ''
    runHook preInstall
    mkdir -p "$out/jars" "$out/share/zaozi-shadow"
    cp "$(find . -name 'scala3-compiler_3-${version}.jar' | head -1)" "$out/jars/"
    cp "$(find . -name 'scala3-presentation-compiler_3-${version}.jar' | head -1)" "$out/jars/"
    # Normalise for a reproducible FOD output hash.
    add-determinism "$out/jars/"*.jar || true
    runHook postInstall
  '';

  meta = {
    description = "Same-version 3.8.4 scala3-compiler/presentation-compiler jars built from source (Metals shadow toolchain)";
    platforms = lib.platforms.linux;
  };
}
