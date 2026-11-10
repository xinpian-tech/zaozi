# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2024 Jiuyang Liu <liu@jiuyang.me>

{ lib, newScope, }:
lib.makeScope newScope (scope: {
  tbh = scope.callPackage ./tbh.nix { };
  zaozi-assembly = scope.callPackage ./zaozi.nix { };
})

