#!/usr/bin/env bash

set -euo pipefail

readonly SCALA_VERSION="3.8.4"

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
cd "$repo_root"

required_env=(
  CIRCT_INSTALL_PATH
  JEXTRACT_INSTALL_PATH
  LIBC_INCLUDE_PATH
  LIT_INSTALL_PATH
  MLIR_INSTALL_PATH
  RISCV_OPCODES_INSTALL_PATH
  SCALA_CLI_INSTALL_PATH
  Z3_LIB
)

for name in "${required_env[@]}"; do
  if [[ -z "${!name:-}" ]]; then
    printf 'error: %s is not set; run this script from nix develop\n' "$name" >&2
    exit 1
  fi
done

# A fresh lock must not reuse task outputs or a daemon whose dependency
# resolutions happened outside mtf's relay.
mill shutdown >/dev/null 2>&1 || true
rm -rf -- "$repo_root/out"
scala-cli clean "$repo_root"

archive_args=(
  -p "$repo_root"
  --lock "$repo_root/mtf.lock.json"
  "$@"
)

# Scala CLI has its own Coursier resolver even when the application classpath is
# supplied by Mill's Lit tests. Capture its compiler graph directly first.
mtf archive "${archive_args[@]}" --fresh -- \
  scala-cli compile --test --server=false --scala-version="$SCALA_VERSION" \
  -e 'object MtfArchiveProbe'

# Keep mtf's sandboxed JAVA_TOOL_OPTIONS, which pins user.home to its clean
# workdir, and pass the compiler stack size independently through JAVA_OPTS.
export JAVA_OPTS="${JAVA_OPTS:-} -Xss32m"
archive_args+=(--export-env JAVA_OPTS)
for name in "${required_env[@]}"; do
  archive_args+=(--export-env "$name")
done

# Append the general Mill dependency graph, then the targets whose tools resolve
# additional artifacts only when they run.
mtf archive "${archive_args[@]}" -- \
  mill --no-daemon -j 1 __.prepareOffline
mtf archive "${archive_args[@]}" -- \
  mill --no-daemon -j 1 __.scalaCompilerClasspath

# Pull in every utility exercised by CI. Keep these aligned with the commands
# in .github/workflows when a new CI target is added.
mtf archive "${archive_args[@]}" -- \
  mill --no-daemon -j 1 __.checkFormat
mtf archive "${archive_args[@]}" -- \
  mill --no-daemon -j 1 --ticker false zaozi.docJar
mtf archive "${archive_args[@]}" -- \
  mill --no-daemon -j 1 __.testForked
mtf archive "${archive_args[@]}" -- \
  mill --no-daemon -j 1 __.lit.tests.run
mtf archive "${archive_args[@]}" -- \
  mill --no-daemon -j 1 --ticker false zaozi.benchmark.runJmh \
  -wi 0 -i 1 -r 1s -f 1
mtf archive "${archive_args[@]}" -- \
  mill --no-daemon -j 1 __.dumpIncludes
