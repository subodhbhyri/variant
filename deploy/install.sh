#!/usr/bin/env bash
# One-time setup of a fresh Ubuntu 24.04 server (PHASE6_SPEC.md section 10A.7). Run as root from the repository:
#
#   sudo deploy/install.sh [--profile standard|small]
#
# Installs Docker, opens ports 22/80/443 (in the host firewall; on Oracle Cloud ALSO open them in the VCN security
# list, deploy/README.md step 3), turns on automatic security updates, hardens SSH (key only, no root login), makes a
# `variant` user, adds a swap file for the small profile, and installs the nightly backup cron. Safe to run twice.
set -euo pipefail

[ "$(id -u)" -eq 0 ] || { echo "run with sudo" >&2; exit 1; }
. /etc/os-release
[ "${ID:-}" = "ubuntu" ] && [ "${VERSION_ID:-}" = "24.04" ] || echo "warning: tested on Ubuntu 24.04, this is ${PRETTY_NAME:-unknown}"

DEPLOY_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "$DEPLOY_DIR/.." && pwd)"
PROFILE=""
while [ $# -gt 0 ]; do
  case "$1" in
    --profile) PROFILE="$2"; shift 2 ;;
    *) echo "unknown option $1" >&2; exit 1 ;;
  esac
done
if [ -z "$PROFILE" ]; then
  mem_mb="$(awk '/MemTotal/ {print int($2/1024)}' /proc/meminfo)"
  if [ "$mem_mb" -lt 8000 ]; then PROFILE=small; else PROFILE=standard; fi
fi
echo "profile: $PROFILE"

export DEBIAN_FRONTEND=noninteractive
apt-get update -y
apt-get install -y --no-install-recommends ca-certificates curl gnupg git age unattended-upgrades iptables-persistent netfilter-persistent

# --- Docker (the official repository) ------------------------------------------------------------------------------
if ! command -v docker >/dev/null; then
  install -m 0755 -d /etc/apt/keyrings
  curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
  chmod a+r /etc/apt/keyrings/docker.asc
  echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu $VERSION_CODENAME stable" \
    > /etc/apt/sources.list.d/docker.list
  apt-get update -y
  apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
fi
systemctl enable --now docker

# --- the variant user ----------------------------------------------------------------------------------------------
id variant >/dev/null 2>&1 || useradd --create-home --shell /bin/bash variant
usermod -aG docker variant
install -d -o variant -g variant -m 755 /var/lib/variant
install -d -o variant -g variant -m 700 /var/backups/variant
# The user who ran sudo keeps access; give `variant` the same SSH keys so deploys can use either.
if [ -n "${SUDO_USER:-}" ] && [ -f "/home/$SUDO_USER/.ssh/authorized_keys" ]; then
  install -d -o variant -g variant -m 700 /home/variant/.ssh
  touch /home/variant/.ssh/authorized_keys
  sort -u "/home/$SUDO_USER/.ssh/authorized_keys" /home/variant/.ssh/authorized_keys -o /home/variant/.ssh/authorized_keys
  chown variant:variant /home/variant/.ssh/authorized_keys
  chmod 600 /home/variant/.ssh/authorized_keys
fi

# The repository was cloned with a read-only GitHub deploy key by the user who ran sudo (README step 7); `variant` pulls
# updates later, so it gets the same key.
if [ -n "${SUDO_USER:-}" ] && [ -f "/home/$SUDO_USER/.ssh/variant_deploy" ]; then
  install -d -o variant -g variant -m 700 /home/variant/.ssh
  install -o variant -g variant -m 600 "/home/$SUDO_USER/.ssh/variant_deploy" /home/variant/.ssh/variant_deploy
  if ! grep -q 'variant_deploy' /home/variant/.ssh/config 2>/dev/null; then
    printf 'Host github.com
  IdentityFile ~/.ssh/variant_deploy
  IdentitiesOnly yes
  StrictHostKeyChecking accept-new
' >> /home/variant/.ssh/config
  fi
  chown variant:variant /home/variant/.ssh/config
  chmod 600 /home/variant/.ssh/config
fi

# --- firewall: 22, 80, 443 -----------------------------------------------------------------------------------------
# Oracle's Ubuntu images ship iptables rules that reject everything but SSH, so the rules go in the INPUT chain
# before the final REJECT. (Docker publishes 80/443 itself; nothing else is published.)
for port in 22 80 443; do
  iptables -C INPUT -p tcp --dport "$port" -j ACCEPT 2>/dev/null || iptables -I INPUT 1 -p tcp --dport "$port" -j ACCEPT
done
netfilter-persistent save

# --- SSH: key only, no root --------------------------------------------------------------------------------------
# Only when someone has a key to log in with, so this can't lock the owner out.
keys=0
for f in /root/.ssh/authorized_keys /home/*/.ssh/authorized_keys; do
  [ -s "$f" ] && keys=1
done
if [ "$keys" -eq 1 ]; then
  cat > /etc/ssh/sshd_config.d/99-variant.conf <<'EOF'
PasswordAuthentication no
KbdInteractiveAuthentication no
PermitRootLogin no
EOF
  sshd -t && systemctl reload ssh
else
  echo "warning: no authorized_keys found anywhere; SSH left as it was. Add your key, then run this again."
fi

# --- automatic security updates -----------------------------------------------------------------------------------
cat > /etc/apt/apt.conf.d/20auto-upgrades <<'EOF'
APT::Periodic::Update-Package-Lists "1";
APT::Periodic::Unattended-Upgrade "1";
EOF

# --- swap for the small profile -----------------------------------------------------------------------------------
if [ "$PROFILE" = "small" ] && ! swapon --show | grep -q .; then
  fallocate -l 2G /swapfile
  chmod 600 /swapfile
  mkswap /swapfile
  swapon /swapfile
  grep -q '^/swapfile' /etc/fstab || echo '/swapfile none swap sw 0 0' >> /etc/fstab
  echo "created a 2 GB swap file"
fi

# --- the repository belongs to `variant`; nightly backup -----------------------------------------------------------
chown -R variant:variant "$REPO_DIR"
cat > /etc/cron.d/variant-backup <<EOF
# Nightly encrypted backup (deploy/backup.sh). Output goes to the system log.
15 3 * * * variant $REPO_DIR/deploy/backup.sh 2>&1 | logger -t variant-backup
EOF
chmod 644 /etc/cron.d/variant-backup

if [ ! -f "$DEPLOY_DIR/.env" ]; then
  cp "$DEPLOY_DIR/.env.example" "$DEPLOY_DIR/.env"
  sed -i "s/^PROFILE=.*/PROFILE=$PROFILE/" "$DEPLOY_DIR/.env"
  chown variant:variant "$DEPLOY_DIR/.env"
  chmod 600 "$DEPLOY_DIR/.env"
  echo "created deploy/.env from the example: fill it in (deploy/README.md step 6)"
fi

echo "install finished. Log in as 'variant' for the next steps."
