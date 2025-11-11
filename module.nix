{
  config,
  lib,
  pkgs,
  ...
}:
with lib; let
  options = {
    services.boostbox = {
      enable = mkEnableOption "BoostBox, a simple API for hosting Podcasting 2.0 boost metadata";
      package = mkOption {
        type = types.package;
      };
      user = mkOption {
        type = types.str;
        default = "boostbox";
        description = "User account under which the BoostBox service runs.";
      };
      group = mkOption {
        type = types.str;
        default = "boostbox";
        description = "Group under which the BoostBox service runs.";
      };
      port = mkOption {
        type = types.port;
        default = 8080;
        description = "HTTP API and docs port.";
      };
      extraEnvironment = lib.mkOption {
        description = ''
          Environment variables to pass to BoostBox.
        '';
        type = types.attrsOf lib.types.str;
        default = {};
        example = literalExpression ''
          {
            BB_STORAGE = "FS";
          }
        '';
      };
    };
  };
  cfg = config.services.boostbox;
  env = mkMerge [
    {
      HOME = "/var/lib/boostbox";
      ENV = "PROD";
      BB_FS_ROOT_PATH = "/var/lib/boostbox";
      BB_PORT = "${builtins.toString cfg.port}";
    }
    cfg.extraEnvironment
  ];
in {
  inherit options;

  config = mkIf cfg.enable {
    users.groups.${cfg.group} = {};
    users.users.${cfg.user} = {
      isSystemUser = true;
      group = cfg.group;
    };

    systemd.services.boostbox = {
      description = "BoostBox";
      wantedBy = ["multi-user.target"];
      after = ["network.target"];
      environment = env;
      serviceConfig = {
        ExecStart = "${getExe cfg.package}";
        User = cfg.user;
        Group = cfg.group;
        Restart = "on-failure";
        RestartSec = 10;
        StateDirectory = "boostbox";
        WorkingDirectory = "/var/lib/boostbox";
        LockPersonality = true;
        NoNewPrivileges = true;
      };
    };
  };
}
