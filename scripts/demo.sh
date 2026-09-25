#!/usr/bin/env bash
# Scripted demo of the main journey, from the command line:
#   start the stack -> generate a 128^3 dataset -> submit a 20-variant sweep with the
#   demo transient failure -> watch bounded progress -> download and checksum one result.
#
# Usage: ./scripts/demo.sh            (requires Docker, curl and python3)
set -euo pipefail

BASE="${WORKBENCH_URL:-http://localhost:3000}"
cd "$(dirname "$0")/.."

field() { python3 -c "import sys, json; d = json.load(sys.stdin); print(eval(sys.argv[1]))" "$1"; }
key() { python3 -c 'import uuid; print("demo-" + str(uuid.uuid4()))'; }
post() { curl -fsS -X POST "$BASE$1" -H 'Content-Type: application/json' -H "Idempotency-Key: $(key)" -d "$2"; }

echo "==> Starting the stack (first run builds images)"
docker compose up --build -d --wait

echo "==> Generating a 128x128x128 int32 dataset (seed 42)"
DATASET=$(post /api/datasets '{"shape":[128,128,128],"seed":42}' | field 'd["id"]')
until [ "$(curl -fsS "$BASE/api/datasets/$DATASET" | field 'd["status"]')" = "READY" ]; do sleep 0.5; done
echo "    dataset $DATASET is READY"

echo "==> Submitting a sweep: gain 1..20 step 1, one child fails transiently on its first attempt"
SWEEP=$(post /api/sweeps "{\"datasetId\":\"$DATASET\",\"parameter\":\"gain\",\"start\":1,\"end\":20,\"step\":1,\"bias\":0,\"implementationVersion\":\"1.0.0\",\"demoTransientFailure\":true}" | field 'd["id"]')
echo "    sweep $SWEEP accepted (202); open $BASE/#sweep=$SWEEP to watch"

while :; do
  STATUS=$(curl -fsS "$BASE/api/sweeps/$SWEEP")
  echo "    $(echo "$STATUS" | field '"queued {queued:2}  running {running}  succeeded {succeeded:2}  failed {failed}".format(**d["counts"]) + "  retry attempts " + str(d["retries"])')"
  [ "$(echo "$STATUS" | field 'd["done"]')" = "True" ] && break
  sleep 1
done

PEAK=$(curl -fsS "$BASE/api/sweeps/$SWEEP/timeline" | field 'd["peakConcurrency"]')
echo "==> Peak simultaneous computations: $PEAK (worker slots: 3)"

RUN=$(curl -fsS "$BASE/api/sweeps/$SWEEP/runs?page=0&size=20" | field '[r for r in d["items"] if r["gain"] == 7][0]["id"]')
EXPECTED=$(curl -fsS "$BASE/api/runs/$RUN" | field '[a for a in d["artifacts"] if a["kind"] == "OUTPUT_TENSOR"][0]["sha256"]')
OUT="$(mktemp -d)/run-gain-7.npy"
curl -fsSL -o "$OUT" "$BASE/api/runs/$RUN/download"   # follows the 302 to a presigned storage URL
ACTUAL=$(shasum -a 256 "$OUT" | cut -d' ' -f1)
echo "==> Downloaded $(wc -c < "$OUT" | tr -d ' ') bytes to $OUT"
[ "$ACTUAL" = "$EXPECTED" ] && echo "    SHA-256 matches the recorded artifact checksum" || { echo "    checksum mismatch"; exit 1; }
echo "==> Done. Dashboard: $BASE/#sweep=$SWEEP&run=$RUN"
