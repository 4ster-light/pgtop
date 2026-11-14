{
  description = "pgtop — PostgreSQL workload advisor that emits ranked, actionable findings";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    flake-parts.url = "github:hercules-ci/flake-parts";
  };

  outputs = inputs:
    inputs.flake-parts.lib.mkFlake {inherit inputs;} {
      systems = ["x86_64-linux" "aarch64-linux" "x86_64-darwin" "aarch64-darwin"];

      perSystem = {pkgs, lib, ...}: let
        jdk = pkgs.jdk21_headless;

        # nixpkgs ships scalafix 0.12, whose parser predates the Scala 3
        # indentation syntax this project is written in. Pin the current CLI.
        scalafix = pkgs.writeShellApplication {
          name = "scalafix";
          runtimeInputs = [pkgs.coursier jdk pkgs.cacert];
          text = ''
            export JAVA_HOME=${jdk}
            exec coursier launch --quiet ch.epfl.scala:scalafix-cli_2.13.16:0.14.3 \
              --main-class scalafix.cli.Cli -- "$@"
          '';
        };

        # Source tree without build artefacts, used by the package and the apps.
        projectSource = lib.cleanSourceWith {
          src = ./.;
          filter = path: type: let
            base = baseNameOf (toString path);
          in
            !(builtins.elem base ["out" ".git" ".bsp" ".bloop" ".metals" "result"]);
        };

        tools = [jdk pkgs.mill pkgs.scalafmt scalafix pkgs.coursier pkgs.git pkgs.cacert];

        # A launcher that runs the CLI through Mill, keeping the build in the
        # user's cache because a Nix store path is read-only.
        pgtop = pkgs.writeShellApplication {
          name = "pgtop";
          runtimeInputs = [jdk pkgs.mill];
          text = ''
            export JAVA_HOME=${jdk}
            cache="''${XDG_CACHE_HOME:-$HOME/.cache}/pgtop"
            marker="$cache/${baseNameOf (toString projectSource)}"
            mkdir -p "$cache"
            if [ ! -e "$marker" ]; then
              rm -rf "$cache/src"
              cp -r ${projectSource} "$cache/src"
              touch "$marker"
            fi
            cd "$cache/src"
            exec mill --no-server -i cli.run -- "$@"
          '';
        };

        # The quality gate needs Maven Central and none of it can be vendored
        # without a dependency lock, so it runs from the project tree with the
        # dev shell toolchain instead of as a sandboxed derivation.
        gate = name: commands:
          pkgs.writeShellApplication {
            inherit name;
            runtimeInputs = tools;
            text = ''
              export JAVA_HOME=${jdk}
              cd ${projectSource}
              ${commands}
            '';
          };
      in {
        devShells.default = pkgs.mkShell {
          name = "pgtop";
          packages = tools ++ [pkgs.scala-cli pkgs.postgresql_17];
          JAVA_HOME = "${jdk}";
          shellHook = ''
            echo "pgtop dev shell (JDK 21, Mill $(mill --version 2>/dev/null | tail -1))"
            echo "  mill __.compile        compile everything"
            echo "  mill lint.check        scalafmt --test + scalafix --check"
            echo "  mill __.test           core, heuristics and report unit tests"
            echo "  mill itest.test        integration test against a throwaway Postgres (needs Docker)"
            echo "  mill cli.run -- --help run the CLI"
          '';
        };

        packages = {
          default = pgtop;
          inherit pgtop;
        };

        apps = {
          default = {
            type = "app";
            program = "${pgtop}/bin/pgtop";
          };
          check = {
            type = "app";
            program = "${gate "pgtop-check" ''
              mill --no-server lint.check
              mill --no-server core.test
              mill --no-server heuristics.test
              mill --no-server report.test
            ''}/bin/pgtop-check";
          };
          integration = {
            type = "app";
            program = "${gate "pgtop-integration" ''
              mill --no-server itest.test
            ''}/bin/pgtop-integration";
          };
        };

        formatter = pkgs.alejandra;
      };
    };
}
