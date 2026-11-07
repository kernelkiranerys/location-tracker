{ config, pkgs, lib, ... }:

let
  appDir = "/opt/location-tracker";

  myPython = pkgs.python312.withPackages (ps: with ps; [
    django
    djangorestframework
    gunicorn
  ]);
in {
  users.users.locationtracker = {
    isSystemUser = true;
    group = "locationtracker";
    home = appDir;
    createHome = true;
  };

  users.groups.locationtracker = {};

  systemd.services.location-tracker = {
    description = "Django location tracker service";
    after = [ "network.target" ];
    wants = [ "network.target" ];
    wantedBy = [ "multi-user.target" ];

    serviceConfig = {
      Type = "simple";
      User = "locationtracker";
      Group = "locationtracker";
      WorkingDirectory = appDir;
      Environment = [
        "PYTHONPATH=${appDir}"
        "DJANGO_SETTINGS_MODULE=location_server.settings"
        "DJANGO_DEBUG=False"
      ];

      ExecStartPre = [
        "${myPython}/bin/python ${appDir}/manage.py migrate --no-input"
        "${myPython}/bin/python ${appDir}/manage.py collectstatic --no-input"
      ];

      ExecStart = ''
        ${myPython}/bin/gunicorn \
          --bind 127.0.0.1:8000 \
          --workers 3 \
          --access-logfile - \
          --error-logfile - \
          location_server.wsgi:application
      '';

      Restart = "always";
      RestartSec = "5s";
    };
  };

  services.caddy = {
    enable = true;
    # No email needed - self-signed cert
    # No plugins needed
  };

  services.caddy.virtualHosts = {
    "tracker.example.com:8443" = {
      extraConfig = ''
        reverse_proxy localhost:8000
        tls /etc/caddy/certs/cert.pem /etc/caddy/certs/key.pem
      '';
    };
  };
}
