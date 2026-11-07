# NixOS deployment notes

1. Copy this repository to /opt/location-tracker on the NixOS host.
2. Import the module from your NixOS configuration:

   ```nix
   { imports = [ /path/to/location_server/nixos/location-tracker.nix ]; }
   ```

3. Make sure the self-signed certificate files exist at /etc/caddy/certs/cert.pem and /etc/caddy/certs/key.pem.
4. Confirm the hostname in the module is your real DDNS hostname, `tracker.example.com` by default.
5. Rebuild and switch:

   ```bash
   sudo nixos-rebuild switch
   ```

The module starts Gunicorn on 127.0.0.1:8000, runs migrations and collectstatic before startup, and configures Caddy to terminate TLS with the self-signed certificate.
