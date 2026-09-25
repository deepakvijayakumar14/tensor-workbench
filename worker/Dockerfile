# syntax=docker/dockerfile:1
FROM python:3.13-slim
COPY --from=ghcr.io/astral-sh/uv:0.12.19 /uv /usr/local/bin/uv
ENV UV_COMPILE_BYTECODE=1 \
    UV_LINK_MODE=copy \
    UV_PROJECT_ENVIRONMENT=/opt/venv \
    PYTHONUNBUFFERED=1
WORKDIR /app
COPY pyproject.toml uv.lock ./
RUN --mount=type=cache,target=/root/.cache/uv uv sync --frozen --no-dev --no-install-project
COPY src src
RUN --mount=type=cache,target=/root/.cache/uv uv sync --frozen --no-dev
ENV PATH=/opt/venv/bin:$PATH
RUN useradd --system --create-home app && mkdir -p /tmp/tensor-worker && chown app /tmp/tensor-worker
USER app
CMD ["python", "-m", "tensor_worker.main"]
