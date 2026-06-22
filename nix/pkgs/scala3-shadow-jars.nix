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
, srcRev ? "unknown"
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

    # Apply the shadow patch into the writable source before package (resources +
    # property-gated behavioural markers; minimal and no effect unless the JVM sets
    # -Dzaozi.shadow.marker=true).
    bash ${./zaozi-shadow-patch.sh} "${version}" "${srcRev}"

    sbt -batch -no-colors "scala3-compiler-bootstrapped/package; scala3-presentation-compiler/package"
    runHook postBuild
  '';

  installPhase = ''
    runHook preInstall
    mkdir -p "$out/jars" "$out/share/zaozi-shadow"

    # Exactly one jar of each coordinate must exist, or the build layout changed.
    pickjar() {
      local name="$1" matches n
      matches="$(find . -type f -name "$name")"
      n="$(printf '%s' "$matches" | grep -c .)"
      if [ "$n" -ne 1 ]; then
        echo "expected exactly 1 $name, found $n:" >&2
        printf '%s\n' "$matches" >&2
        exit 1
      fi
      printf '%s' "$matches"
    }
    cp "$(pickjar 'scala3-compiler_3-${version}.jar')" "$out/jars/"
    cp "$(pickjar 'scala3-presentation-compiler_3-${version}.jar')" "$out/jars/"

    # Normalise for a reproducible FOD output hash (fail closed).
    add-determinism "$out/jars/"*.jar
    runHook postInstall
  '';

  meta = {
    description = "Same-version 3.8.4 scala3-compiler/presentation-compiler jars built from source (Metals shadow toolchain)";
    platforms = lib.platforms.linux;
  };
}
