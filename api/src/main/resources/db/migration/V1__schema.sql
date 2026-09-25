-- Tensor Workbench schema.
--
-- Tensor bytes never live in PostgreSQL. Tables hold metadata, queue state and
-- object-storage keys; artifacts themselves live in S3-compatible storage.

-- Fencing tokens: every lease (dataset generation or run attempt) gets a new,
-- strictly increasing token. Writes from a worker must present the token of the
-- lease they hold, so a superseded worker cannot change accepted state.
CREATE SEQUENCE lease_token_seq;

CREATE TABLE datasets (
    id                uuid PRIMARY KEY,
    shape             integer[]   NOT NULL,
    dtype             text        NOT NULL CHECK (dtype = 'int32'),
    seed              bigint      NOT NULL CHECK (seed >= 0),
    generator_version text        NOT NULL,
    status            text        NOT NULL CHECK (status IN ('QUEUED', 'GENERATING', 'READY', 'FAILED')),
    attempt_count     integer     NOT NULL DEFAULT 0,
    max_attempts      integer     NOT NULL CHECK (max_attempts > 0),
    next_attempt_at   timestamptz NOT NULL DEFAULT now(),
    lease_owner       text,
    lease_token       bigint,
    lease_expires_at  timestamptz,
    error_category    text,
    error_message     text,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    ready_at          timestamptz,
    CONSTRAINT datasets_shape_is_3d CHECK (cardinality(shape) = 3 AND shape[1] > 0 AND shape[2] > 0 AND shape[3] > 0),
    CONSTRAINT datasets_lease_only_while_generating CHECK (
        (status = 'GENERATING') = (lease_token IS NOT NULL AND lease_expires_at IS NOT NULL AND lease_owner IS NOT NULL))
);

CREATE INDEX datasets_claimable ON datasets (next_attempt_at, created_at) WHERE status = 'QUEUED';
CREATE INDEX datasets_generating_lease ON datasets (lease_expires_at) WHERE status = 'GENERATING';

-- A sweep is one logical request that fans out into many child runs. Its
-- configuration snapshot is immutable once accepted.
CREATE TABLE sweeps (
    id                     uuid PRIMARY KEY,
    dataset_id             uuid        NOT NULL REFERENCES datasets (id),
    parameter_name         text        NOT NULL CHECK (parameter_name IN ('gain', 'bias')),
    range_start            numeric     NOT NULL,
    range_end              numeric     NOT NULL,
    range_step             numeric     NOT NULL CHECK (range_step > 0),
    variant_count          integer     NOT NULL CHECK (variant_count > 0),
    implementation_version text        NOT NULL,
    config_snapshot        jsonb       NOT NULL,
    created_at             timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT sweeps_range_ordered CHECK (range_start <= range_end)
);

-- A run is one logical computation request. Its execution attempts live in
-- run_attempts; retries stay under the same run.
CREATE TABLE runs (
    id                     uuid PRIMARY KEY,
    dataset_id             uuid        NOT NULL REFERENCES datasets (id),
    sweep_id               uuid REFERENCES sweeps (id),
    sweep_ordinal          integer,
    gain                   numeric     NOT NULL,
    bias                   numeric     NOT NULL,
    implementation_version text        NOT NULL,
    config_snapshot        jsonb       NOT NULL,
    config_hash            text        NOT NULL,
    state                  text        NOT NULL CHECK (state IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED')),
    attempt_count          integer     NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    max_attempts           integer     NOT NULL CHECK (max_attempts > 0),
    next_attempt_at        timestamptz NOT NULL DEFAULT now(),
    accepted_attempt_id    uuid,
    last_error_category    text,
    last_error_message     text,
    created_at             timestamptz NOT NULL DEFAULT now(),
    updated_at             timestamptz NOT NULL DEFAULT now(),
    finished_at            timestamptz,
    CONSTRAINT runs_sweep_child_has_ordinal CHECK ((sweep_id IS NULL) = (sweep_ordinal IS NULL)),
    CONSTRAINT runs_unique_sweep_ordinal UNIQUE (sweep_id, sweep_ordinal),
    CONSTRAINT runs_success_has_accepted_attempt CHECK ((state = 'SUCCEEDED') = (accepted_attempt_id IS NOT NULL))
);

CREATE INDEX runs_claimable ON runs (next_attempt_at, created_at, sweep_ordinal) WHERE state = 'QUEUED';
CREATE INDEX runs_by_sweep ON runs (sweep_id, sweep_ordinal);
CREATE INDEX runs_by_created ON runs (created_at DESC, id DESC);

CREATE TABLE run_attempts (
    id                  uuid PRIMARY KEY,
    run_id              uuid        NOT NULL REFERENCES runs (id),
    attempt_number      integer     NOT NULL CHECK (attempt_number > 0),
    lease_owner         text        NOT NULL,
    lease_token         bigint      NOT NULL UNIQUE,
    lease_expires_at    timestamptz NOT NULL,
    claimed_at          timestamptz NOT NULL DEFAULT now(),
    last_heartbeat_at   timestamptz,
    -- Reported by the worker: when numerical work actually started and stopped.
    compute_started_at  timestamptz,
    compute_finished_at timestamptz,
    finished_at         timestamptz,
    outcome             text CHECK (outcome IN ('SUCCEEDED', 'FAILED', 'LEASE_EXPIRED')),
    error_category      text,
    error_message       text,
    summary             jsonb,
    CONSTRAINT run_attempts_unique_number UNIQUE (run_id, attempt_number),
    CONSTRAINT run_attempts_run_and_id UNIQUE (run_id, id),
    CONSTRAINT run_attempts_open_iff_unfinished CHECK ((outcome IS NULL) = (finished_at IS NULL))
);

-- At most one open (unfinished) attempt per run.
CREATE UNIQUE INDEX run_attempts_one_open_per_run ON run_attempts (run_id) WHERE outcome IS NULL;
CREATE INDEX run_attempts_open_leases ON run_attempts (lease_expires_at) WHERE outcome IS NULL;

-- The accepted attempt must belong to the same run.
ALTER TABLE runs
    ADD CONSTRAINT runs_accepted_attempt_belongs_to_run
        FOREIGN KEY (id, accepted_attempt_id) REFERENCES run_attempts (run_id, id);

-- Object-storage references. Keys are persisted, never expiring URLs.
-- Input tensors are owned by a dataset; outputs by the attempt that produced them.
CREATE TABLE artifacts (
    id           uuid PRIMARY KEY,
    kind         text        NOT NULL CHECK (kind IN ('INPUT_TENSOR', 'OUTPUT_TENSOR', 'PREVIEW_STACK')),
    dataset_id   uuid REFERENCES datasets (id),
    attempt_id   uuid REFERENCES run_attempts (id),
    object_key   text        NOT NULL UNIQUE,
    content_type text        NOT NULL,
    size_bytes   bigint      NOT NULL CHECK (size_bytes >= 0),
    sha256       text        NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    dtype        text        NOT NULL,
    shape        integer[]   NOT NULL,
    metadata     jsonb       NOT NULL DEFAULT '{}'::jsonb,
    created_at   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT artifacts_owner_matches_kind CHECK (
        (kind = 'INPUT_TENSOR' AND dataset_id IS NOT NULL AND attempt_id IS NULL)
            OR (kind IN ('OUTPUT_TENSOR', 'PREVIEW_STACK') AND attempt_id IS NOT NULL AND dataset_id IS NULL))
);

CREATE UNIQUE INDEX artifacts_one_input_per_dataset ON artifacts (dataset_id) WHERE kind = 'INPUT_TENSOR';
CREATE UNIQUE INDEX artifacts_one_kind_per_attempt ON artifacts (attempt_id, kind) WHERE attempt_id IS NOT NULL;

-- Request idempotency, scoped by submission operation. The row is inserted in
-- the same transaction that creates the resource.
CREATE TABLE idempotency_keys (
    operation     text        NOT NULL,
    key           text        NOT NULL CHECK (length(key) BETWEEN 8 AND 200),
    request_hash  text        NOT NULL,
    resource_type text        NOT NULL,
    resource_id   uuid        NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (operation, key)
);
