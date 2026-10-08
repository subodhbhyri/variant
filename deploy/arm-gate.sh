#!/usr/bin/env bash
# The ARM gate (PHASE6_SPEC.md section 10A.4). Every golden file was measured with LibreOffice on x86; on an ARM server
# the product's promise (nothing on the page moves) has to be PROVEN before the server is trusted:
#
#   1. the images are built here, for this machine (linux/arm64)
#   2. corpus-check passes on all 9 corpus resumes, inside the arm64 image
#   3. P5-T17's identity check passes on the 3 fixture postings (real MiniLM model)
#
# On success it writes the gate record that deploy.sh insists on. If anything fails, do not use this server for
# real users: use the x86 fallback and send the output to the project owner.
#
#   deploy/arm-gate.sh <folder with the 9 corpus .docx files> [--shred]
#
# The corpus resumes are real people's data. Copy them to the server only for this check; --shred overwrites and
# removes them when the check ends (pass or fail). Without --shred, delete them yourself the same way.
set -euo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

CORPUS="${1:-}"; SHRED="${2:-}"
[ -d "$CORPUS" ] || die "usage: arm-gate.sh <corpus-folder> [--shred]"
CORPUS="$(cd "$CORPUS" && pwd)"
ARCH="$(uname -m)"
case "$ARCH" in
  aarch64|arm64) ;;
  *) say "This machine is $ARCH, not ARM: no gate is needed."; exit 0 ;;
esac
require_env_file

finish() {
  if [ "$SHRED" = "--shred" ]; then
    say "shredding the corpus copy in $CORPUS"
    find "$CORPUS" -type f -exec shred -u {} + 2>/dev/null || true
    rm -rf "$CORPUS"
  else
    say "REMINDER: delete the corpus copy now:  find $CORPUS -type f -exec shred -u {} + ; rm -rf $CORPUS"
  fi
  [ -n "${MODELS:-}" ] && rm -rf "$MODELS"
  return 0
}
trap finish EXIT

N="$(find "$CORPUS" -maxdepth 1 -name '*.docx' | wc -l)"
[ "$N" -eq 9 ] || die "expected the 9 corpus resumes (.docx) in $CORPUS, found $N"

say "building the images for $ARCH (this takes a while)"
compose build
docker build -q -f "$REPO_DIR/docker/Dockerfile" -t variant-gate:local "$REPO_DIR" >/dev/null

# Same LibreOffice as the x86 measurements (24.2.x)?
LO="$(docker run --rm --entrypoint dpkg-query variant-renderer:local -W -f='${Version}' libreoffice-core)"
say "LibreOffice in the renderer image: $LO"
case "$LO" in 4:24.2.*|24.2.*) ;; *) die "LibreOffice is $LO, not 24.2.x: the golden files do not apply" ;; esac

say "corpus-check on all 9 resumes (slow: about 4 minutes on x86)"
docker run --rm -v "$CORPUS:/app/corpus:ro" variant-gate:local \
  gradle --no-daemon :cli:run --args="corpus-check /app/corpus /app/golden" | tee /tmp/arm-corpus-check.log
grep -q 'ALL PASS' /tmp/arm-corpus-check.log || die "corpus-check FAILED on ARM: do not use this server"

say "P5-T17 identity on the 3 fixture postings (real MiniLM)"
MODELS="$(mktemp -d)"
cid="$(docker create variant-web:local)"
docker cp "$cid:/opt/models/all-MiniLM-L6-v2" "$MODELS/all-MiniLM-L6-v2"
docker rm "$cid" >/dev/null
docker run --rm -v "$MODELS:/models:ro" -e VARIANT_MODEL_DIR=/models/all-MiniLM-L6-v2 variant-gate:local \
  gradle --no-daemon :engine:test --tests '*PerformanceP5T17Test' | tee /tmp/arm-p5t17.log
grep -q 'BUILD SUCCESSFUL' /tmp/arm-p5t17.log || die "the P5-T17 identity check FAILED on ARM: do not use this server"

mkdir -p "$STATE_DIR"
cat > "$STATE_DIR/arm-gate.json" <<EOF
{
  "passed": true,
  "arch": "$ARCH",
  "libreoffice": "$LO",
  "engine_tree": "$(git -C "$REPO_DIR" rev-parse HEAD:engine)",
  "renderer_tree": "$(git -C "$REPO_DIR" rev-parse HEAD:renderer)",
  "docker_tree": "$(git -C "$REPO_DIR" rev-parse HEAD:docker)",
  "commit": "$(git -C "$REPO_DIR" rev-parse HEAD)",
  "checked_at": "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
}
EOF
say "ARM GATE PASSED. Record written to $STATE_DIR/arm-gate.json"
