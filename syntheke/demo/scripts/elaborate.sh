#!/usr/bin/env bash
set -euo pipefail

root=$1
out=$(realpath -m "$2")
config=${3:-}
[ -n "$config" ] && config=$(realpath "$config")

cd "$root"
read -r -a mill <<< "${MILL:-mill}"
"${mill[@]}" syntheke.demo.assembly > /dev/null
jar=$("${mill[@]}" --ticker false show syntheke.demo.assembly | tr -d '"' | sed 's|^ref:[^/]*||')

libs=""
[ -n "${MLIR_INSTALL_PATH:-}" ] && libs="$MLIR_INSTALL_PATH/lib"
[ -n "${CIRCT_INSTALL_PATH:-}" ] && libs="$libs:$CIRCT_INSTALL_PATH/lib"

rm -rf "$out"
mkdir -p "$out" "$out.mlirbc"

ZAOZI_OUTDIR="$out.mlirbc" \
  java -Xss32m --enable-native-access=ALL-UNNAMED \
    -Djava.library.path="$libs" -jar "$jar" "$out" ${config:+"$config"}
