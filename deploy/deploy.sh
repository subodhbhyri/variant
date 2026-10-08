#!/usr/bin/env bash
# Deploys a commit to this server (PHASE6_SPEC.md section 10A.7):
#
#   deploy/deploy.sh [git-ref]        # default: origin/main
#
# Fetches the repo at that ref, builds the images on this host, starts the stack (database migrations run when the api
# starts) and waits until every service is healthy. On an ARM machine it REFUSES to start unless the ARM gate
# (deploy/arm-gate.sh) has passed here for this exact engine, renderer and Dockerfile code. Safe to run again.
set -euo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

REF="${1:-origin/main}"
require_env_file

# --- .env must be complete and private ---------------------------------------------------------------------------
perm="$(stat -c %a "$ENV_FILE")"
[ "$perm" = "600" ] || die "$ENV_FILE has mode $perm; run: chmod 600 $ENV_FILE"
for v in APP_HOST POSTGRES_PASSWORD APP_STORAGE_LINK_SECRET BACKUP_AGE_RECIPIENT; do
  val="$(env_value "$v")"
  [ -n "$val" ] && [ "$val" != "change-me" ] || die "$v is not set in $ENV_FILE"
done
[ -n "$(env_value ANTHROPIC_API_KEY)" ] || say "warning: ANTHROPIC_API_KEY is empty: generation will fail until it is set"
if [ -z "$(env_value GOOGLE_CLIENT_ID)" ] && [ "$(env_value APP_MAIL_MODE)" = "off" ]; then
  die "no way to sign in: set GOOGLE_CLIENT_ID/GOOGLE_CLIENT_SECRET (APP_MAIL_MODE is off)"
fi
[ "$(env_value APP_MAIL_MODE)" = "log" ] && die "APP_MAIL_MODE=log prints sign-in links to the log; not for a real server"

# --- the code ------------------------------------------------------------------------------------------------------
PREVIOUS="$(git -C "$REPO_DIR" rev-parse HEAD)"
git -C "$REPO_DIR" fetch --tags --prune origin
git -C "$REPO_DIR" checkout --detach "$REF"
COMMIT="$(git -C "$REPO_DIR" rev-parse HEAD)"
say "deploying $COMMIT (was $PREVIOUS)"

# A backup of the running system first, if there is one.
if compose ps --status running --services 2>/dev/null | grep -qx postgres; then
  say "backing up before the change"
  "$DEPLOY_DIR/backup.sh"
fi

say "building the images on this host"
compose build

# --- the ARM gate --------------------------------------------------------------------------------------------------
ARCH="$(uname -m)"
case "$ARCH" in
  aarch64|arm64)
    GATE="$STATE_DIR/arm-gate.json"
    refuse() {
      say "REFUSING TO START: $1"
      say "This is an ARM machine. Run  deploy/arm-gate.sh <corpus-folder> --shred  first (see deploy/README.md, step 7),"
      say "or deploy on an x86 server."
      git -C "$REPO_DIR" checkout --detach "$PREVIOUS" >/dev/null 2>&1 || true
      exit 3
    }
    [ -f "$GATE" ] || refuse "the ARM gate has not been run on this server."
    grep -q '"passed": true' "$GATE" || refuse "the ARM gate record does not say passed."
    for pair in "engine_tree:engine" "renderer_tree:renderer" "docker_tree:docker"; do
      key="${pair%%:*}"; dir="${pair##*:}"
      recorded="$(sed -n "s/.*\"$key\": \"\\([0-9a-f]*\\)\".*/\\1/p" "$GATE")"
      now="$(git -C "$REPO_DIR" rev-parse "HEAD:$dir")"
      [ "$recorded" = "$now" ] || refuse "the code in $dir/ changed since the gate passed (gate $recorded, now $now)."
    done
    LO="$(docker run --rm --entrypoint dpkg-query variant-renderer:local -W -f='${Version}' libreoffice-core)"
    recorded_lo="$(sed -n 's/.*"libreoffice": "\([^"]*\)".*/\1/p' "$GATE")"
    [ "$LO" = "$recorded_lo" ] || refuse "LibreOffice is now $LO; the gate was passed with $recorded_lo."
    say "ARM gate: passed on this server for this code (LibreOffice $LO)"
    ;;
  *) say "architecture $ARCH: no ARM gate needed" ;;
esac

# --- start ---------------------------------------------------------------------------------------------------------
say "starting the stack (the api applies database migrations as it starts)"
compose up -d --remove-orphans --wait --wait-timeout 420

FLYWAY="$(compose exec -T postgres psql -U tailor -d tailor -Atc "select max(version::int) from flyway_schema_history where success" | tr -d '\r')"
say "database schema version: $FLYWAY"
compose ps
mkdir -p "$STATE_DIR" 2>/dev/null && echo "$COMMIT" > "$STATE_DIR/deployed-commit" || true

HOST="$(env_value APP_HOST)"
if curl -fsS --max-time 15 "https://$HOST/api/healthz" >/dev/null 2>&1; then
  say "https://$HOST/api/healthz answers. Deployed $COMMIT."
else
  say "deployed $COMMIT, but https://$HOST/api/healthz did not answer from here yet."
  say "If this is the first start, Caddy is still getting its certificate (give it a minute), or the host name"
  say "does not point at this server, or ports 80/443 are closed (deploy/README.md, troubleshooting)."
fi
