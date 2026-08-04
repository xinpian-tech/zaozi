# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2024 Jiuyang Liu <liu@jiuyang.me>

{ coreutils
, lib
, stdenv
, writeShellApplication
, writeText
, scala3BspSemanticLs
, scala3BspSemanticLsZedPlugin
}:

let
  extensionId = "scala3-bsp-semantic-ls-zed";
  settings = writeText "zaozi-zed-settings.json" ''
    {
      "languages": {
        "Scala": {
          "language_servers": ["scala3-bsp-semantic-ls"]
        }
      },
      "lsp": {
        "scala3-bsp-semantic-ls": {
          "binary": {
            "path": "${scala3BspSemanticLs}/bin/scala3-bsp-semantic-ls",
            "arguments": []
          }
        }
      }
    }
  '';
  setExtensionsDirectory =
    if stdenv.isDarwin then
      ''
        zed_extensions_dir="$HOME/Library/Application Support/Zed/extensions/installed"
      ''
    else
      ''
        zed_extensions_dir="''${XDG_DATA_HOME:-$HOME/.local/share}/zed/extensions/installed"
      '';
  installHook = ''
    ${setExtensionsDirectory}
    extension_destination="$zed_extensions_dir/${extensionId}"
    install -d "$zed_extensions_dir"

    if [ -e "$extension_destination" ] && [ ! -L "$extension_destination" ]; then
      echo "Cannot install Zed extension: $extension_destination already exists and is not a symlink" >&2
      exit 1
    fi

    ln -sfn \
      ${scala3BspSemanticLsZedPlugin}/share/zed/extensions/${extensionId} \
      "$extension_destination"
    ${lib.optionalString stdenv.isLinux ''
      if [ ! -e .zed/settings.json ]; then
        install -Dm644 ${settings} .zed/settings.json
      fi
    ''}
  '';
in
writeShellApplication {
  name = "zaozi-zed-install-hook";
  runtimeInputs = [ coreutils ];
  text = installHook;
  derivationArgs.passthru = {
    inherit installHook settings;
    shellHook = installHook;
  };
}
