# Build the pinned upstream CLI with its matching sbt dependency archive.
{ upstream, system }:
let
  pkgs = upstream.inputs.nixpkgs.legacyPackages.${system};
  src = upstream.outPath;
in
upstream.inputs.sbt.mkSbtDerivation.${system} {
  pname = "sn-bindgen";
  version = upstream.shortRev;
  inherit src;
  depsSha256 = "sha256-u/I2tJyOax6+RszcrUQKHsTbNdvxlZJHfiVDXtQ6dK0=";
  depsWarmupCommand = ''
    sbt 'update' 'bindgen/compile'
    sbt 'iface/compile ; iface2_12/compile ; iface3/compile'
    sbt 'ifaceNative/compile ; ifaceNative2_12/compile ; ifaceNative3/compile'
  '';
  nativeBuildInputs = with pkgs; [ clang_17 libunwind stdenv which zlib ];
  buildInputs = [ pkgs.llvmPackages_17.libclang ];
  buildPhase = "sbt 'show buildBinary'";
  installPhase = "install -Dm755 bin/bindgen $out/bin/bindgen";
  meta.mainProgram = "bindgen";
}
