{
  description = "mic-grid-loader development environment";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    flake-parts.url = "github:hercules-ci/flake-parts";
    systems.url = "github:nix-systems/default";
  };

  outputs = inputs:
    inputs.flake-parts.lib.mkFlake { inherit inputs; } {
      systems = import inputs.systems;
      perSystem = { config, self', pkgs, lib, system, ... }: let
        java = pkgs.jdk21;
        nativeBuildInputs = with pkgs; [
          java
          gradle
        ];
        buildInputs = with pkgs; [
          libGL
          xorg.libX11
          xorg.libXext
          xorg.libXcursor
          xorg.libXrandr
          xorg.libXi
          xorg.libXxf86vm
          libpulseaudio
          openal
          flite
          udev
        ];
      in {
        devShells.default = pkgs.mkShell {
          inherit nativeBuildInputs buildInputs;
          env = {
            LD_LIBRARY_PATH = lib.makeLibraryPath buildInputs;
            JAVA_HOME = "${java.home}";
          };
        };
      };
    };
}
