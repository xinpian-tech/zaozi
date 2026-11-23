{
  description = "Syntheke design document, built with Typix";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";
    typix = {
      url = "github:loqusion/typix";
      inputs.nixpkgs.follows = "nixpkgs";
    };
  };

  outputs = { nixpkgs, flake-utils, typix, ... }:
    flake-utils.lib.eachDefaultSystem (system:
      let
        # fletcher pins cetz 0.3.4, which pins oxifmt 0.2.1; typix needs the whole closure listed.
        typstPackages = [
          {
            name = "fletcher";
            version = "0.5.8";
            hash = "sha256-kKVp5WN/EbHEz2GCTkr8i8DRiAdqlr4R7EW6drElgWk=";
          }
          {
            name = "cetz";
            version = "0.3.4";
            hash = "sha256-5w3UYRUSdi4hCvAjrp9HslzrUw7BhgDdeCiDRHGvqd4=";
          }
          {
            name = "oxifmt";
            version = "0.2.1";
            hash = "sha256-8PNPa9TGFybMZ1uuJwb5ET0WGIInmIgg8h24BmdfxlU=";
          }
        ];
        design = typix.lib.${system}.buildTypstProject {
          name = "syntheke-design.pdf";
          src = typix.lib.${system}.cleanTypstSource ./design;
          typstSource = "main.typ";
          unstable_typstPackages = typstPackages;
        };
      in
      {
        packages = {
          default = design;
          design = design;
        };
      });
}
