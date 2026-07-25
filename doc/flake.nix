{
  description = "Syntheke documentation (design series + archives), built with Typix";

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
        pkgs = nixpkgs.legacyPackages.${system};
        typixLib = typix.lib.${system};

        fontPaths = [
          "${pkgs.noto-fonts-cjk-serif}/share/fonts"
          "${pkgs.noto-fonts-cjk-sans}/share/fonts"
          "${pkgs.jetbrains-mono}/share/fonts"
        ];

        # fletcher pins cetz 0.3.4, which pins oxifmt 0.2.1; typix needs the
        # full transitive closure listed explicitly.
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

        design = typixLib.buildTypstProject {
          name = "syntheke-design.pdf";
          src = typixLib.cleanTypstSource ./design;
          typstSource = "main.typ";
          inherit fontPaths;
          unstable_typstPackages = typstPackages;
        };

        archive = name: source:
          typixLib.buildTypstProject {
            name = "${name}.pdf";
            src = pkgs.lib.fileset.toSource {
              root = ./.;
              fileset = source;
            };
            typstSource = "${name}.typ";
            inherit fontPaths;
          };

        analysis = name:
          typixLib.buildTypstProject {
            name = "${name}.pdf";
            src = pkgs.lib.fileset.toSource {
              root = ./.;
              fileset = ./analysis;
            };
            typstSource = "analysis/${name}.typ";
            inherit fontPaths;
          };
      in
      {
        packages = {
          default = design;
          design = design;
          rational = archive "rational" ./rational.typ;
          naming = archive "naming" ./naming.typ;
          cardinality-survey = analysis "cardinality-survey";
        };
      });
}
