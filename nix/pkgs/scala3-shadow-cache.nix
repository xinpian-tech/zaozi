# Self-consistent isolated coursier cache for the Metals/IDE shadow toolchain.
#
# It is the SOLE authoritative source both consumers point at (the Mill/scalac compiler
# JVM and the Metals/presentation-compiler JVM). It carries:
#   - the full Zaozi compile closure (from the existing ivy-gather zaozi-lock cache),
#   - the presentation-compiler resolution closure (fetched by coursier: jars + POMs +
#     parent POMs that are not already in the Zaozi lock),
#   - the PATCHED scala3-compiler_3 and scala3-presentation-compiler_3 jars overlaid at
#     their stock Maven coordinates (stock POMs kept verbatim — the patch changes neither
#     coordinate nor dependencies).
#
# Layout: $out/cache/https/repo1.maven.org/maven2/...  (COURSIER_CACHE = $out/cache).
#
# Why a fixed-output derivation: coursier must reach Maven for the PC closure. The output
# is made deterministic by stripping coursier's per-file sidecars (.<name>__sha1 / __md5 /
# .checked / .computed dotfiles) and maven-metadata, leaving only jar + pom — the same
# minimal offline set the repo's ivy-gather cache already uses. This also satisfies the
# rule "ship no checksum sidecar that describes the replaced (stock) bytes".
{ lib
, stdenv
, coursier
, ivy-gather
, scala3-shadow-jars
, version ? "3.8.3"
, proxyHost ? null
, proxyPort ? null
  # Recursive sha256 of the normalised cache. Pinned from a real proxied build. Re-pin
  # if the lock, the PC closure, or the patched jar bytes change (TOFU: lib.fakeHash until
  # the 3.8.3 rebuild reports the real hash).
, outputHash ? lib.fakeHash
}:

let
  # Overriding this derivation's proxy must also build the jars with it (same pinned
  # content => already in the store, no rebuild).
  jars =
    if proxyHost != null
    then scala3-shadow-jars.override { inherit proxyHost proxyPort; }
    else scala3-shadow-jars;
  baseCache = ivy-gather ../zaozi/zaozi-lock.nix;
  mavenBase = "https/repo1.maven.org/maven2/org/scala-lang";
in
stdenv.mkDerivation {
  pname = "scala3-shadow-cache";
  inherit version;
  dontUnpack = true;

  nativeBuildInputs = [ coursier ];

  outputHashMode = "recursive";
  outputHashAlgo = "sha256";
  inherit outputHash;

  buildPhase = ''
    runHook preBuild
    export HOME="$TMPDIR/home" COURSIER_CACHE="$TMPDIR/csc"
    mkdir -p "$HOME" "$COURSIER_CACHE" "$out/cache"

    proxyOpts=""
    ${lib.optionalString (proxyHost != null) ''
      proxyOpts="-Dhttp.proxyHost=${proxyHost} -Dhttp.proxyPort=${toString proxyPort} -Dhttps.proxyHost=${proxyHost} -Dhttps.proxyPort=${toString proxyPort}"
    ''}
    export JAVA_TOOL_OPTIONS="$proxyOpts"

    # 1. Full Zaozi compile closure (jar+pom, already sidecar-free). Dereference the
    #    ivy-gather symlinks into real writable files so we can overlay/strip.
    cp -rL --no-preserve=mode,ownership "${baseCache}/cache/https" "$out/cache/https"

    # 2. PC resolution closure (jars + POMs + parent POMs) via coursier.
    cs fetch --cache "$COURSIER_CACHE" org.scala-lang:scala3-presentation-compiler_3:${version}
    cp -rL --no-preserve=mode,ownership "$COURSIER_CACHE/https/." "$out/cache/https/"

    # 3. Normalise to the minimal offline set: drop coursier's per-file sidecar dotfiles
    #    (.<name>__sha1 / __md5 / .checked / .computed) and maven-metadata. Leaves only
    #    jar + pom, which is deterministic and is the set the ivy-gather cache already
    #    proves works offline. Also removes any checksum that would describe stock bytes.
    find "$out/cache" -type f -name '.*' -delete
    find "$out/cache" -type f \( -name 'maven-metadata*' -o -name '_remote.repositories' \) -delete

    # 4. Overlay the patched jars at their stock coordinates (POMs untouched).
    cjar="$out/cache/${mavenBase}/scala3-compiler_3/${version}/scala3-compiler_3-${version}.jar"
    pjar="$out/cache/${mavenBase}/scala3-presentation-compiler_3/${version}/scala3-presentation-compiler_3-${version}.jar"
    install -m644 "${jars}/jars/scala3-compiler_3-${version}.jar" "$cjar"
    install -m644 "${jars}/jars/scala3-presentation-compiler_3-${version}.jar" "$pjar"

    # 5. Fail closed: the overlaid bytes must equal the published patched jars, the POMs
    #    must be present, and exactly one jar + one pom must sit at each coordinate.
    cmp -s "$cjar" "${jars}/jars/scala3-compiler_3-${version}.jar"
    cmp -s "$pjar" "${jars}/jars/scala3-presentation-compiler_3-${version}.jar"
    for d in "scala3-compiler_3" "scala3-presentation-compiler_3"; do
      dir="$out/cache/${mavenBase}/$d/${version}"
      njar="$(find "$dir" -maxdepth 1 -type f -name '*.jar' | wc -l)"
      npom="$(find "$dir" -maxdepth 1 -type f -name '*.pom' | wc -l)"
      if [ "$njar" -ne 1 ] || [ "$npom" -ne 1 ]; then
        echo "coordinate $d: expected 1 jar + 1 pom, got $njar jar / $npom pom" >&2
        ls -la "$dir" >&2; exit 1
      fi
    done
    # No dotfile sidecars must remain anywhere.
    if find "$out/cache" -type f -name '.*' | grep -q .; then
      echo "stale sidecar dotfiles remain in the cache" >&2; exit 1
    fi
    runHook postBuild
  '';

  dontInstall = true;

  meta = {
    description = "Isolated coursier cache shadowing the patched 3.8.4 compiler + presentation-compiler";
    platforms = lib.platforms.linux;
  };
}
