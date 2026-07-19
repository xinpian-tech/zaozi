# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2024 Jiuyang Liu <liu@jiuyang.me>
final: prev:

{
  circt = prev.circt.overrideAttrs (old: {
    # CIRCT master supports old lit releases except for this unchecked field.
    postPatch = (old.postPatch or "") + ''
      substituteInPlace test/Tools/circt-tblgen/self-contained/self_contained_td_format.py \
        --replace-fail \
          'test.config.maxIndividualTestTime or None' \
          'getattr(test.config, "maxIndividualTestTime", None) or None'
    '';
  });

  mill = prev.millVersions.mill_1_1_2.override { jre = final.jdk25; };

  riscv-opcodes = final.callPackage ./pkgs/riscv-opcodes.nix { };

  espresso = final.callPackage ./pkgs/espresso.nix { };
}
