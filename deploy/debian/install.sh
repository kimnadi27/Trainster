#!/usr/bin/env bash
# Run manually on your Debian 13 VPS: sudo bash deploy/debian/install.sh PUBLIC_IPV4
set -Eeuo pipefail
trap 'echo "Installation stopped at line $LINENO. Read the error above; existing database is not replaced." >&2' ERR

if [[ $EUID -ne 0 ]]; then echo 'Run with sudo.' >&2; exit 1; fi
IP=${1:-}
if [[ ! $IP =~ ^[0-9]{1,3}(\.[0-9]{1,3}){3}$ ]]; then
    echo 'Usage: sudo bash deploy/debian/install.sh PUBLIC_IPV4' >&2; exit 1
fi
IFS=. read -r -a octets <<< "$IP"
for octet in "${octets[@]}"; do
    if (( 10#$octet > 255 )); then echo 'Invalid IPv4 address.' >&2; exit 1; fi
done
. /etc/os-release
if [[ ${ID:-} != debian || ${VERSION_ID:-} != 13 ]]; then
    echo 'This installer targets Debian 13.' >&2; exit 1
fi
DEPLOY_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
RELEASE_DIR=$(cd -- "$DEPLOY_DIR/../.." && pwd)
test -f "$RELEASE_DIR/server/app/main.py"
MARKER=/etc/trainster/installed-by-trainster

# Do not take over somebody else's web server or application directory.
if [[ ! -f $MARKER ]]; then
    if [[ -e /opt/trainster || -e /etc/nginx/nginx.conf || -e /etc/nginx/sites-enabled/trainster || -e /etc/systemd/system/trainster.service ]]; then
        echo 'An existing Trainster or Nginx installation needs a manual review; no files changed.' >&2; exit 1
    fi
    if command -v ss >/dev/null && [[ -n $(ss -H -ltn '( sport = :80 or sport = :443 or sport = :8000 )') ]]; then
        echo 'Ports 80, 443 or 8000 are already used. Keep existing services; adapt the proxy manually.' >&2
        ss -ltnp '( sport = :80 or sport = :443 or sport = :8000 )' >&2
        exit 1
    fi
fi

apt-get update
apt-get install -y python3 python3-venv nginx curl ca-certificates sqlite3 iproute2
python3 - "$IP" <<'PY'
import ipaddress, sys
if sys.version_info < (3, 12):
    raise SystemExit('Python 3.12 or newer is required.')
if not ipaddress.ip_address(sys.argv[1]).is_global:
    raise SystemExit('Use the actual public IPv4 address of this server.')
PY

if ! id trainster >/dev/null 2>&1; then
    useradd --system --user-group --home-dir /var/lib/trainster --shell /usr/sbin/nologin trainster
fi
install -d -m 0755 /opt/trainster/server/app
install -d -m 0700 /etc/trainster
install -d -m 0750 -o trainster -g trainster /var/lib/trainster
touch "$MARKER"

if [[ -f /var/lib/trainster/trainster.sqlite3 ]]; then
    install -d -m 0700 /var/backups/trainster
    BACKUP=/var/backups/trainster/$(date -u +%Y%m%dT%H%M%SZ)-trainster.sqlite3
    sqlite3 /var/lib/trainster/trainster.sqlite3 ".backup '$BACKUP'"
    chmod 0600 "$BACKUP"
fi
if systemctl is-active --quiet trainster.service; then systemctl stop trainster.service; fi
cp -a "$RELEASE_DIR/server/app/." /opt/trainster/server/app/
install -m 0644 "$RELEASE_DIR/server/requirements.txt" /opt/trainster/server/requirements.txt
python3 -m venv /opt/trainster/server/.venv
/opt/trainster/server/.venv/bin/python -m pip install -r /opt/trainster/server/requirements.txt

if [[ ! -f /etc/trainster/server.env ]]; then
    install -m 0600 "$DEPLOY_DIR/server.env.example" /etc/trainster/server.env
fi
if [[ -f "$RELEASE_DIR/data/trainster.sqlite3" && ! -f /var/lib/trainster/trainster.sqlite3 ]]; then
    install -m 0600 -o trainster -g trainster "$RELEASE_DIR/data/trainster.sqlite3" /var/lib/trainster/trainster.sqlite3
fi
install -m 0644 "$DEPLOY_DIR/trainster.service" /etc/systemd/system/trainster.service
systemctl daemon-reload
systemctl enable --now trainster.service
for attempt in {1..30}; do
    if curl --fail --silent http://127.0.0.1:8000/health >/dev/null; then break; fi
    sleep 1
done
curl --fail --silent --show-error http://127.0.0.1:8000/health

install -d -m 0755 /var/lib/trainster-acme/.well-known/acme-challenge
NGINX_SITE=/etc/nginx/sites-available/trainster
# Retain a working HTTPS configuration on re-runs.
if [[ ! -f /etc/letsencrypt/live/trainster-ip/fullchain.pem ]]; then
    cat > "$NGINX_SITE" <<EOF
server {
    listen 80;
    server_name $IP;
    access_log off;
    location ^~ /.well-known/acme-challenge/ {
        root /var/lib/trainster-acme;
        default_type text/plain;
        try_files \$uri =404;
    }
    location / { return 404; }
}
EOF
    ln -sfn "$NGINX_SITE" /etc/nginx/sites-enabled/trainster
    nginx -t
    systemctl enable --now nginx
    systemctl reload nginx
fi

# Debian's distro Certbot can predate IP-certificate support; use an isolated venv.
python3 -m venv /opt/trainster-certbot
/opt/trainster-certbot/bin/python -m pip install --upgrade 'certbot>=5.4'
# This requests a public IP certificate and accepts Let's Encrypt's subscriber terms.
/opt/trainster-certbot/bin/certbot certonly --non-interactive --agree-tos \
    --register-unsafely-without-email --webroot -w /var/lib/trainster-acme \
    --preferred-profile shortlived --ip-address "$IP" --cert-name trainster-ip \
    --keep-until-expiring

cat > "$NGINX_SITE" <<EOF
server {
    listen 80;
    server_name $IP;
    access_log off;
    location ^~ /.well-known/acme-challenge/ {
        root /var/lib/trainster-acme;
        default_type text/plain;
        try_files \$uri =404;
    }
    location / { return 301 https://$IP\$request_uri; }
}
server {
    listen 443 ssl default_server;
    server_name $IP;
    ssl_certificate /etc/letsencrypt/live/trainster-ip/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/trainster-ip/privkey.pem;
    ssl_protocols TLSv1.2 TLSv1.3;
    client_max_body_size 32m;
    access_log off;
    location / {
        proxy_pass http://127.0.0.1:8000;
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$remote_addr;
        proxy_set_header X-Forwarded-Proto \$scheme;
        proxy_connect_timeout 10s;
        proxy_read_timeout 180s;
    }
}
EOF
nginx -t
systemctl enable --now nginx
systemctl reload nginx
install -m 0644 "$DEPLOY_DIR/trainster-certbot.service" /etc/systemd/system/trainster-certbot.service
install -m 0644 "$DEPLOY_DIR/trainster-certbot.timer" /etc/systemd/system/trainster-certbot.timer
systemctl daemon-reload
systemctl enable --now trainster-certbot.timer
touch "$MARKER"
curl --fail --silent --show-error "https://$IP/health"
printf '\nTrainster is running at https://%s\n' "$IP"
echo 'Set YANDEX_RASP_API_KEY with: sudo nano /etc/trainster/server.env'
echo 'Then run: sudo systemctl restart trainster'
echo 'Validate renewal: sudo /opt/trainster-certbot/bin/certbot renew --cert-name trainster-ip --dry-run'
