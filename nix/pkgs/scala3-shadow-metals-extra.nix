# A Metals-ready isolated coursier cache: the shadow cache (patched compiler/PC + the PC
# closure) PLUS the extra offline closure Metals' presentation-compiler setup path resolves
# (mtags/coursier-cli/os-lib/scala-collection-compat + scala-library 2.13.x). The extra is
# fetched INTO a copy of the shadow cache so coursier resolves it coherently against the
# already-present artifacts (a standalone resolve merged afterwards leaves Metals' offline
# PC setup unable to assemble a consistent classpath). The patched PC jar already present is
# kept (coursier never re-fetches a coordinate it already has).
#
# Layout/normalisation mirror scala3-shadow-cache: $out/cache/https/..., jar+pom only.
{ lib
, stdenv
, coursier
, scala3-shadow-cache
, metalsVersion ? "1.6.7"
, scalaVersion ? "3.8.3"
, proxyHost ? null
, proxyPort ? null
, outputHash ? "sha256-ueMUqp7aM84Q9oodIV4rLtDrIOhWB5JJjgzcPI7IZhQ="
}:

stdenv.mkDerivation {
  pname = "scala3-shadow-metals-extra";
  version = metalsVersion;
  dontUnpack = true;

  nativeBuildInputs = [ coursier ];

  outputHashMode = "recursive";
  outputHashAlgo = "sha256";
  inherit outputHash;

  buildPhase = ''
    runHook preBuild
    export HOME="$TMPDIR/home"
    mkdir -p "$HOME" "$out/cache"

    proxyOpts=""
    ${lib.optionalString (proxyHost != null) ''
      proxyOpts="-Dhttp.proxyHost=${proxyHost} -Dhttp.proxyPort=${toString proxyPort} -Dhttps.proxyHost=${proxyHost} -Dhttps.proxyPort=${toString proxyPort}"
    ''}
    export JAVA_TOOL_OPTIONS="$proxyOpts"

    # Start from the shadow cache (patched PC + PC closure) so the Metals extra resolves
    # coherently against it.
    cp -rL --no-preserve=mode,ownership "${scala3-shadow-cache}/cache/https" "$out/cache/https"
    export COURSIER_CACHE="$out/cache"

    # Fetch the Metals PC-setup closure AND the presentation compiler itself. Fetching the PC
    # coordinate here (not just relying on the copied shadow cache) pulls the maven-metadata.xml
    # for its version-range dependencies (e.g. gson:[2.9.1,3.0)), which coursier needs to
    # resolve the PC offline. Metals' MtagsResolver.isSupportedScalaVersion == resolve().isDefined,
    # so a missing range metadata file makes Metals log "unsupported Scala ${scalaVersion}" and
    # never start the patched PC.
    cs fetch --cache "$COURSIER_CACHE" \
      org.scala-lang:scala3-presentation-compiler_3:${scalaVersion} \
      org.scala-lang:scala-library:2.13.18 \
      org.scalameta:mtags_2.13.18:${metalsVersion} \
      io.get-coursier:coursier-cli_3:2.1.25-M17 \
      com.lihaoyi:os-lib_3:0.11.5 \
      org.scala-lang.modules:scala-collection-compat_3:2.13.0

    # Mill's SemanticDB worker + the javac SemanticDB plugin: Metals' BSP buildTargetCompile drives
    # Mill's SemanticDbJavaModule to produce the WORKSPACE SemanticDB that Metals indexes for
    # cross-file go-to-definition / find-references. Without these offline, Mill's
    # `workerClasspath` / `resolvedSemanticDbJavaPluginMvnDeps` tasks fail and Metals never builds a
    # cross-file index (same-file navigation still works from the open file's SemanticDB).
    cs fetch --cache "$COURSIER_CACHE" \
      com.lihaoyi:mill-libs-javalib-scalameta-worker_3:1.1.2 \
      com.sourcegraph:semanticdb-javac:0.11.1 \
      org.scalameta:scalameta_2.13:4.14.7 \
      org.scalameta:semanticdb-shared_2.13:4.14.7 \
      org.scala-lang:scala-compiler:2.13.15 \
      io.github.java-diff-utils:java-diff-utils:4.12 \
      com.google.protobuf:protobuf-java:3.19.6

    # Offline-resolvable set: drop coursier sidecar dotfiles + _remote.repositories, but KEEP
    # maven-metadata.xml so coursier can resolve version-range deps offline (see above).
    find "$out/cache" -type f -name '.*' -delete
    find "$out/cache" -type f -name '_remote.repositories' -delete
    runHook postBuild
  '';

  dontInstall = true;

  meta = {
    description = "Metals ${metalsVersion}-ready isolated cache (shadow cache + PC-setup offline closure)";
    platforms = lib.platforms.linux;
  };
}
