# Tensor Workbench

Bounded, asynchronous processing of **synthetic** 3D arrays. Generate an input tensor, run a parameter
sweep on a fixed three-slot worker pool, inspect heatmap slices, and download full results.

> The computation is a toy function (`output = gain × coefficient[material_code] + bias`), not a
> physics solver. The project is about the engineering around expensive array workloads: capacity limits,
> retries, artifact storage, and getting large results to a browser.

![Dashboard during a 20-variant sweep](docs/screenshot.png)

**Stack:** Kotlin + Spring Boot · Python + NumPy · PostgreSQL · SeaweedFS (S3) · React + TypeScript · Docker Compose

## Run

Requires Docker only.

```bash
docker compose up --build --wait   # open http://localhost:3000 (API docs: /api/docs)
./scripts/demo.sh                   # optional: the same flow scripted from the CLI
docker compose down -v              # stop and delete all data
```

**Demo:** generate a 128³ dataset → submit gain 1–20 with *Demo failure* checked → watch at most 3 runs
execute and child #3 retry after a transient failure → click a result to scrub slices → download the
`.npy` file.

## How it works

```mermaid
flowchart LR
  B[React UI] -->|/api| A[Spring Boot API]
  A -->|metadata + job queue| P[(PostgreSQL)]
  A -->|verify, range reads, presign| S[(SeaweedFS)]
  W[NumPy worker<br/>3 slots] -->|claim, heartbeat, complete| A
  W -->|input / output files| S
  B -.->|presigned download| S
```

- **Queue:** PostgreSQL, claiming with `FOR UPDATE SKIP LOCKED` in short transactions. No transaction stays open while work runs.
- **Capacity:** one worker service with 3 slot processes. A slot claims only after its previous task stops.
- **Runs vs attempts:** retries stay under one run, and `accepted_attempt_id` marks the authoritative result.
- **Leases and fencing:** heartbeat, complete and fail require the current, unexpired lease token. Stale
  workers get `409`, and object keys are scoped to each attempt, so a stale upload can't overwrite anything.
- **Retries:** transient errors back off exponentially with jitter (3 attempts). Invalid input,
  unsupported versions and out-of-memory errors fail immediately. Failed runs can be retried explicitly.
- **Idempotency:** each submission sends an `Idempotency-Key`. The same payload returns the original
  resource; a different payload returns `409`.
- **Large results:** tensors are `.npy` files in object storage, and the database stores only their keys.
  Previews are pre-rendered slices (at most 128×128, 8-bit), each served with one byte-range read. Full
  downloads use presigned URLs. A 20-run sweep stores 160 MiB; browsing it transferred about 318 KiB.

Details: [docs/DESIGN.md](docs/DESIGN.md).

## Tests

| Suite | Command | Result |
|---|---|---|
| API (real Postgres + SeaweedFS via Testcontainers) | `cd api && ./gradlew test` | 32 passed |
| Worker | `cd worker && uv run pytest` | 24 passed |
| Web | `cd web && npm ci && npm test` | 9 passed |
| End-to-end (against the running stack) | `cd e2e && uv run pytest` | 9 passed |

The end-to-end suite checks that 20 variants peak at 3 simultaneous computations, the demo retry,
idempotency, downloads that match value for value, and recovery after the worker is killed. CI runs all
four suites.

## Measurements

Apple M4 Pro, Docker Desktop, single runs:

- **Worker only, 512³ (134M elements):** compute 1.3 s; peak anonymous memory 41 MiB (memory-mapped file
  pages reached about 1 GiB).
- **Full stack, no synthetic delay:** a 20-run sweep took 2.3 s at 128³ and 5.4 s at 256³. The demo adds a
  labelled 3 s delay per run so the scheduling is visible.

## Limitations

- The computation is synthetic and makes no scientific claims.
- The capacity limit is per worker process, not global.
- No authentication; development credentials only.
- Downloads assume the browser can reach `localhost:8333`.
- One implementation version at a time. Previews are lossy.
