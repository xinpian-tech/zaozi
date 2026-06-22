# Builds same-version 3.8.3 scala3-compiler_3 and scala3-presentation-compiler_3
# jars FROM SOURCE (the pinned scala3-src), for the Metals/IDE shadow toolchain.
#
# Why a fixed-output derivation (FOD): the dotty sbt build resolves its plugin/dep
# closure from Maven, which a normal sandboxed derivation cannot do (no network).
# A FOD is granted network access; in exchange its output must be content-addressed,
# so the jars are normalised with add-determinism (already used by zaozi.nix) to keep
# the output hash stable across rebuilds.
#
# Exact version: dotty's Build.scala sets the version to `baseVersion` (= "3.8.3")
# only when `isRelease` (env RELEASEBUILD=yes); otherwise it appends `-bin-SNAPSHOT`.
# So RELEASEBUILD=yes yields the exact stock coordinate `3.8.3` (no custom suffix).
#
# Proxy: in a proxied/sandboxed network, pass proxyHost/proxyPort so sbt's resolver
# can reach Maven (the FOD build env is minimal). In a normal network leave them null.
{ lib
, stdenv
, sbt
, jdk21
, git
, unzip
, perl
, add-determinism
, scala3-src
, version ? "3.8.3"
, srcRev ? "unknown"
, proxyHost ? null
, proxyPort ? null
  # Fixed-output hash of the normalised jar set (recursive sha256 of $out). Pinned from
  # a real proxied build of the patched same-version jars + hashes.json. Re-pin if the
  # scala3 source rev or the marker patch changes the jar bytes.
, outputHash ? "sha256-A8jTlf2wo5GUh/KCrnIPMY+5e5PEc8Jyrg0HYMDNQ30="
}:

stdenv.mkDerivation {
  pname = "scala3-shadow-jars";
  inherit version;
  src = scala3-src;

  nativeBuildInputs = [ sbt jdk21 git unzip perl add-determinism ];

  outputHashMode = "recursive";
  outputHashAlgo = "sha256";
  inherit outputHash;

  # Drives Build.scala `isRelease` => version == "3.8.3" exactly.
  RELEASEBUILD = "yes";

  dontConfigure = true;

  buildPhase = ''
    runHook preBuild
    export HOME="$TMPDIR/home" COURSIER_CACHE="$TMPDIR/cs" XDG_CACHE_HOME="$TMPDIR/xdg"
    mkdir -p "$HOME/.sbt/boot" "$HOME/.sbt/global" "$HOME/.sbt/staging" "$HOME/.ivy2" \
             "$HOME/.cache" "$HOME/jtmp" "$COURSIER_CACHE" "$XDG_CACHE_HOME"

    proxyOpts=""
    ${lib.optionalString (proxyHost != null) ''
      proxyOpts="-Dhttp.proxyHost=${proxyHost} -Dhttp.proxyPort=${toString proxyPort} -Dhttps.proxyHost=${proxyHost} -Dhttps.proxyPort=${toString proxyPort}"
    ''}
    # The sandbox default user.home is /var/empty (read-only), but sbt's launcher puts
    # its boot lock under user.home/.sbt. Pin user.home and every sbt working dir to the
    # writable $HOME so the launcher can create its boot/lock/ivy state.
    sbtDirs="-Duser.home=$HOME -Dsbt.boot.directory=$HOME/.sbt/boot -Dsbt.global.base=$HOME/.sbt/global -Dsbt.global.staging=$HOME/.sbt/staging -Dsbt.ivy.home=$HOME/.ivy2"
    # sbt's server/ipc socket lives under java.io.tmpdir (default /tmp); creating it under
    # /tmp intermittently fails with AccessDenied in the sandbox. Point java.io.tmpdir at a
    # writable $HOME dir and disable server autostart.
    sbtDirs="$sbtDirs -Djava.io.tmpdir=$HOME/jtmp -Dsbt.server.autostart=false"
    export SBT_OPTS="$proxyOpts $sbtDirs -Dsbt.server=false -Dsbt.ci=true -Xmx6g"
    export JAVA_TOOL_OPTIONS="$proxyOpts -Duser.home=$HOME"

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

    # Normalise for a reproducible FOD output hash (fail closed). Must run BEFORE
    # hashing so the recorded SHA-256s match the bytes that actually ship.
    add-determinism "$out/jars/"*.jar

    # Provenance: record the source rev, patch-set id, and the SHA-256 of each whole
    # jar and of its marker resource, so a later JVM-loaded jar can be compared to this
    # output and patched jars are distinguishable from stock.
    cjar="$out/jars/scala3-compiler_3-${version}.jar"
    pjar="$out/jars/scala3-presentation-compiler_3-${version}.jar"
    cmarker="META-INF/zaozi-shadow/org.scala-lang-scala3-compiler_3-${version}.properties"
    pmarker="META-INF/zaozi-shadow/org.scala-lang-scala3-presentation-compiler_3-${version}.properties"
    csha="$(sha256sum "$cjar" | cut -d' ' -f1)"
    psha="$(sha256sum "$pjar" | cut -d' ' -f1)"
    cmsha="$(unzip -p "$cjar" "$cmarker" | sha256sum | cut -d' ' -f1)"
    pmsha="$(unzip -p "$pjar" "$pmarker" | sha256sum | cut -d' ' -f1)"
    # Assert the marker resources are actually present (non-empty hash of empty input
    # is the well-known SHA-256 of "", which must NOT be accepted).
    empty="e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    if [ "$cmsha" = "$empty" ] || [ "$pmsha" = "$empty" ]; then
      echo "marker resource missing from a jar (empty hash)" >&2; exit 1
    fi
    cat > "$out/share/zaozi-shadow/hashes.json" <<JSON
    {
      "scala3SourceRev": "${srcRev}",
      "patchSet": "marker-v1",
      "version": "${version}",
      "builtBy": "nix",
      "artifacts": {
        "scala3-compiler_3": {
          "jar": "scala3-compiler_3-${version}.jar",
          "jarSha256": "$csha",
          "markerResource": "$cmarker",
          "markerSha256": "$cmsha"
        },
        "scala3-presentation-compiler_3": {
          "jar": "scala3-presentation-compiler_3-${version}.jar",
          "jarSha256": "$psha",
          "markerResource": "$pmarker",
          "markerSha256": "$pmsha"
        }
      }
    }
    JSON
    runHook postInstall
  '';

  meta = {
    description = "Same-version 3.8.3 scala3-compiler/presentation-compiler jars built from source (Metals shadow toolchain)";
    platforms = lib.platforms.linux;
  };
}
