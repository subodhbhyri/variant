#!/usr/bin/env bash
# Shared by the deploy scripts: where things are, how to read .env, and how to call docker compose for this stack.
# Sourced, not run.

DEPLOY_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "$DEPLOY_DIR/.." && pwd)"
PROJECT="${VARIANT_PROJECT:-variant}"
ENV_FILE="${VARIANT_ENV_FILE:-$DEPLOY_DIR/.env}"
STATE_DIR="${VARIANT_STATE_DIR:-/var/lib/variant}"

say() { printf '%s\n' "$*"; }
die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

# Value of a variable in .env (last assignment wins; surrounding quotes removed). Empty if unset.
env_value() {
  local line
  line="$(grep -E "^[[:space:]]*$1=" "$ENV_FILE" 2>/dev/null | tail -n 1 || true)"
  line="${line#*=}"
  line="${line%\"}"; line="${line#\"}"
  printf '%s' "$line"
}

require_env_file() {
  [ -f "$ENV_FILE" ] || die "$ENV_FILE not found. Copy deploy/.env.example to deploy/.env and fill it in."
}

profile_file() {
  local p
  p="$(env_value PROFILE)"
  p="${p:-standard}"
  [ -f "$DEPLOY_DIR/profiles/$p.env" ] || die "PROFILE=$p is not standard or small"
  printf '%s' "$DEPLOY_DIR/profiles/$p.env"
}

# docker compose for this stack, with the profile and .env (later --env-file wins).
compose() {
  docker compose -p "$PROJECT" -f "$DEPLOY_DIR/docker-compose.prod.yml" \
    --env-file "$(profile_file)" --env-file "$ENV_FILE" "$@"
}

# Name of a named volume of this project.
volume_name() { printf '%s_%s' "$PROJECT" "$1"; }

# Image used for tar/chown helper containers: one the stack already pulled, so nothing new is downloaded.
helper_image() { printf '%s' 'postgres:16.4-alpine'; }
