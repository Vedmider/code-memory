#!/bin/bash
# Shared, overridable configuration for the helper scripts. Every value can be overridden
# by exporting the matching env var first, e.g.:
#   MIRROR=my-registry.example.com PARSING_MODE=multi bash scripts/dev-local.sh all

# Base registry used to compose default image refs (Docker Hub, or a private mirror).
MIRROR="${MIRROR:-docker.io}"
# Passed to the Dockerfiles as the base-image registry (library namespace for official images).
export REGISTRY="${REGISTRY:-${MIRROR}/library}"

# Built images.
export APP_IMAGE="${APP_IMAGE:-code-memory:0.1.0}"
export CHUNKING_IMAGE="${CHUNKING_IMAGE:-code-memory-chunking:0.1.0}"
# Dependency image.
export PGVECTOR_IMAGE="${PGVECTOR_IMAGE:-${MIRROR}/pgvector/pgvector:pg16}"

# Parsing mode: java (default, pure in-process) | single | multi (adds chunking service).
export PARSING_MODE="${PARSING_MODE:-java}"

# App endpoint (compose maps app to :8080).
PORT="${CODEMEM_PORT:-8080}"
BASE="http://localhost:${PORT}"

# Host ports for backing services default to non-standard values so the container stack
# never clashes with a local Postgres (:5432) or a dev chunking service (:8090). The app
# reaches them over the compose network regardless of these.
export PG_HOST_PORT="${PG_HOST_PORT:-55432}"
export CHUNK_HOST_PORT="${CHUNK_HOST_PORT:-18090}"
