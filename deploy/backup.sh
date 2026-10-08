#!/usr/bin/env bash
# Backup (PHASE6_SPEC.md section 10A.5): a PostgreSQL dump plus a tar of the file storage volume, encrypted with `age`
# to a PUBLIC key (BACKUP_AGE_RECIPIENT in .env). The matching private key is never on this server, so a stolen backup
# cannot be read. Keeps the last 7 in BACKUP_DIR. Run nightly by cron (install.sh sets that up).
#
#   deploy/backup.sh
set -euo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
umask 077

require_env_file
command -v age >/dev/null || die "age is not installed (apt-get install age)"
RECIPIENT="$(env_value BACKUP_AGE_RECIPIENT)"
case "$RECIPIENT" in age1*) ;; *) die "BACKUP_AGE_RECIPIENT in $ENV_FILE must be a public key starting with age1 (made by age-keygen on YOUR machine)" ;; esac
BACKUP_DIR="${VARIANT_BACKUP_DIR:-$(env_value BACKUP_DIR)}"
BACKUP_DIR="${BACKUP_DIR:-/var/backups/variant}"
KEEP=7

mkdir -p "$BACKUP_DIR"
chmod 700 "$BACKUP_DIR"
WORK="$(mktemp -d "$BACKUP_DIR/.work.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT

STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
OUT="$BACKUP_DIR/variant-backup-$STAMP.tar.age"

# The database first, then the files: a row is only written after its file, so every row in the dump has its file in a
# tar taken a moment later (a file with no row is harmless).
say "dumping the database"
compose exec -T postgres pg_dump -U tailor -d tailor --format=custom --no-owner > "$WORK/db.dump"
[ -s "$WORK/db.dump" ] || die "the database dump is empty"

say "archiving the files"
docker run --rm -v "$(volume_name storage):/data:ro" "$(helper_image)" tar -C /data -cf - . > "$WORK/storage.tar"

{
  echo "created=$STAMP"
  echo "commit=$(git -C "$REPO_DIR" rev-parse HEAD 2>/dev/null || echo unknown)"
  echo "flyway=$(compose exec -T postgres psql -U tailor -d tailor -Atc "select max(version::int) from flyway_schema_history where success" 2>/dev/null | tr -d '\r' || echo unknown)"
  (cd "$WORK" && sha256sum db.dump storage.tar)
} > "$WORK/MANIFEST"

say "encrypting"
tar -C "$WORK" -cf - db.dump storage.tar MANIFEST | age -r "$RECIPIENT" -o "$OUT.part"
mv "$OUT.part" "$OUT"
# The first line of an age file names the format: a cheap check that what was written is an age file.
head -c 21 "$OUT" | grep -q '^age-encryption.org/v1' || die "$OUT is not an age file"
sha256sum "$OUT" | awk '{print $1}' > "$OUT.sha256"

# Keep the newest $KEEP.
ls -1t "$BACKUP_DIR"/variant-backup-*.tar.age 2>/dev/null | tail -n +$((KEEP + 1)) | while read -r old; do
  rm -f "$old" "$old.sha256"
done

say "backup written: $OUT ($(du -h "$OUT" | cut -f1))"
