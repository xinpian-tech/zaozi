# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 xinpian-tech

{ lib
, stdenv
, makeWrapper
, bash
, coreutils
, findutils
}:

let
  scriptPath = lib.makeBinPath [
    bash
    coreutils
    findutils
  ];
in
stdenv.mkDerivation {
  name = "zaozi-jasper";

  src = ./../scripts/jasper;

  nativeBuildInputs = [ makeWrapper ];

  dontConfigure = true;
  dontBuild = true;

  installPhase = ''
    runHook preInstall

    script_dir="$out/share/zaozi/nix/scripts/jasper"
    mkdir -p "$script_dir" "$out/bin"

    cp formal.sh "$script_dir/"
    cp jasper_prove.tcl "$script_dir/"
    cp run_jasper.sh "$script_dir/"

    chmod +x "$script_dir/formal.sh"
    chmod +x "$script_dir/run_jasper.sh"

    makeWrapper "$script_dir/run_jasper.sh" "$out/bin/zaozi-jasper-run" \
      --prefix PATH : "${scriptPath}"

    makeWrapper "$script_dir/formal.sh" "$out/bin/zaozi-jasper" \
      --prefix PATH : "${scriptPath}" \
      --set ZAOZI_JASPER_PROVE_TCL "$script_dir/jasper_prove.tcl" \
      --set ZAOZI_JASPER_RUNNER "$script_dir/run_jasper.sh" \
      --set ZAOZI_JASPER_RUN "$out/bin/zaozi-jasper-run"

    runHook postInstall
  '';

  meta = {
    description = "Wrapped Nix scripts for generic Zaozi JasperGold formal flows";
    mainProgram = "zaozi-jasper";
  };
}
