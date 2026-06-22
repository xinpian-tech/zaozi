# Reproducible Metals server pinned to an exact version (default 1.6.7 — the latest
# release, which supports Scala 3.8.3). The flake's nixpkgs only ships Metals 1.6.5, whose
# Scala 3 support caps at 3.8.0; the Metals/IDE shadow integration needs a Metals that
# supports the shadowed Scala version, so this packages a specific Metals via coursier.
#
# Two derivations (the nixpkgs metals shape): a fixed-output `metals-deps` that coursier-
# resolves the Metals jar closure (no store references, so it is a valid FOD), and a plain
# wrapper that writes the `java -cp <closure> scala.meta.metals.Main` launcher.
{ lib
, stdenv
, coursier
, jdk21
, runCommandLocal
, version ? "1.6.7"
, proxyHost ? null
, proxyPort ? null
  # Recursive sha256 of the metals-deps jar closure. TOFU: lib.fakeHash until the real
  # proxied build reports it.
, depsHash ? "sha256-bGx3PQGgaTueQ/v/Xk7gp03TzllyMs7nCx9QWXNFdt0="
}:

let
  metals-deps = stdenv.mkDerivation {
    pname = "metals-deps";
    inherit version;
    dontUnpack = true;
    nativeBuildInputs = [ coursier jdk21 ];
    outputHashMode = "recursive";
    outputHashAlgo = "sha256";
    outputHash = depsHash;
    buildPhase = ''
      runHook preBuild
      export HOME="$TMPDIR/home" COURSIER_CACHE="$TMPDIR/cs"
      mkdir -p "$HOME" "$COURSIER_CACHE" "$out/share/java"
      proxyOpts=""
      ${lib.optionalString (proxyHost != null) ''
        proxyOpts="-Dhttp.proxyHost=${proxyHost} -Dhttp.proxyPort=${toString proxyPort} -Dhttps.proxyHost=${proxyHost} -Dhttps.proxyPort=${toString proxyPort}"
      ''}
      export JAVA_TOOL_OPTIONS="$proxyOpts"
      cp_paths="$(cs fetch -p org.scalameta:metals_2.13:${version})"
      for j in $(printf '%s' "$cp_paths" | tr ':' ' '); do
        cp -n "$j" "$out/share/java/" || true
      done
      njars="$(find "$out/share/java" -name '*.jar' | wc -l)"
      [ "$njars" -ge 100 ] || { echo "metals closure too small ($njars jars)" >&2; exit 1; }
      runHook postBuild
    '';
    dontInstall = true;
  };
in
runCommandLocal "metals-${version}"
{
  meta = {
    description = "Metals language server pinned to ${version} (supports Scala 3.8.3) via coursier";
    platforms = lib.platforms.linux;
    mainProgram = "metals";
  };
  passthru = { inherit metals-deps; };
} ''
  mkdir -p "$out/bin"
  cp="$(printf '%s:' ${metals-deps}/share/java/*.jar)"
  printf '#!/usr/bin/env bash\nexec "%s/bin/java" -XX:+UseG1GC -XX:+UseStringDeduplication -Xss4m -Xms100m -cp "%s" scala.meta.metals.Main "$@"\n' \
    "${jdk21}" "''${cp%:}" > "$out/bin/metals"
  chmod +x "$out/bin/metals"
''
