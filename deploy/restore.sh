#!/usr/bin/env bash
# Restore a backup made by backup.sh into an EMPTY stack (a fresh server, or one whose data you want to replace):
#
#   deploy/restore.sh <backup.tar.age> <age-private-key-file> [--force]
#
# The private key is the one from `age-keygen` on your own machine. Copy it to the server only for the restore and
# delete it afterwards (shred -u). Without --force it refuses to touch a stack that already has users.
set -euo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
umask 077

BACKUP="${1:-}"; KEY="${2:-}"; FORCE="${3:-}"
[ -f "$BACKUP" ] && [ -f "$KEY" ] || die "usage: restore.sh <backup.tar.age> <age-private-key-file> [--force]"
require_env_file
command -v age >/dev/null || die "age is not installed (apt-get install age)"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

say "decrypting"
age -d -i "$KEY" "$BACKUP" | tar -C "$WORK" -xf - || die "could not decrypt $BACKUP with that key"
[ -f "$WORK/db.dump" ] && [ -f "$WORK/storage.tar" ] && [ -f "$WORK/MANIFEST" ] || die "the archive is not a backup of this stack"
(cd "$WORK" && grep -E '^[0-9a-f]{64}  ' MANIFEST | sha256sum -c --quiet -) || die "the archive's checksums do not match: it is damaged"

say "creating the stack's containers and volumes (nothing is started yet)"
compose up --no-start
compose up -d postgres
for _ in $(seq 1 60); do
  compose exec -T postgres pg_isready -U tailor -d tailor >/dev/null 2>&1 && break
  sleep 2
done
compose exec -T postgres pg_isready -U tailor -d tailor >/dev/null || die "postgres did not become ready"

EXISTING="$(compose exec -T postgres psql -U tailor -d tailor -Atc "select count(*) from users" 2>/dev/null | tr -d '\r' || true)"
if [ -n "$EXISTING" ] && [ "$EXISTING" != "0" ] && [ "$FORCE" != "--force" ]; then
  die "this stack already has $EXISTING user(s). Restoring replaces everything; run again with --force if that is what you want."
fi

say "stopping the application"
compose stop caddy api worker >/dev/null 2>&1 || true

say "restoring the database"
compose exec -T postgres psql -U tailor -d postgres -v ON_ERROR_STOP=1 \
  -c "DROP DATABASE IF EXISTS tailor WITH (FORCE)" -c "CREATE DATABASE tailor OWNER tailor" >/dev/null
compose exec -T postgres pg_restore -U tailor -d tailor --no-owner --exit-on-error < "$WORK/db.dump"

say "restoring the files"
docker run --rm -i -v "$(volume_name storage):/data" "$(helper_image)" sh -c \
  'find /data -mindepth 1 -delete && tar -C /data -xf - && chown -R 10001:10001 /data && chmod 700 /data' < "$WORK/storage.tar"

say "starting the application"
compose up -d --wait --wait-timeout 300
say "restored from $(sed -n 's/^created=//p' "$WORK/MANIFEST") (commit $(sed -n 's/^commit=//p' "$WORK/MANIFEST"))"
