#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"
repo_root=$PWD
scratch=$(mktemp -d)
trap 'rm -rf -- "$scratch"' EXIT

# Record both Maven Central and the Scala Native snapshot repository.
# Keep existing workspace builds, the user's Coursier cache and lock intact
# until every requested artifact has resolved successfully.
export COURSIER_CACHE="$scratch/coursier"
export COURSIER_REPOSITORIES='https://repo.maven.apache.org/maven2|https://central.sonatype.com/repository/maven-snapshots'
export MILL_OUTPUT_DIR="$scratch/out"
export JAVA_OPTS="${JAVA_OPTS:-} -Xss32m"

scala_version=$(sed -n 's/^  val scala  * = "\([^"]*\)"/\1/p' build.mill)
native_version=$(sed -n 's/^  val native  * = "\([^"]*\)"/\1/p' build.mill)
test -n "$scala_version" && test -n "$native_version"
(
  cd "$scratch"
  scala-cli --power package --native --native-version "$native_version" --server=false \
    --scala "$scala_version" -e 'println("native")' -o probe
)
mill --no-daemon '__.scalaCompilerClasspath' + '__.checkFormat' \
  + '__.testForked' + '__.publishM2Local' --m2RepoPath "$scratch/published" + zaozi.opt.nativeLink
python3 nix/zaozi/maven-lock.py "$COURSIER_CACHE" "$scratch/mtf.lock.json"
cp "$scratch/mtf.lock.json" "$repo_root/mtf.lock.json"
