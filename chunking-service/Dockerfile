# syntax=docker/dockerfile:1.7
# Base images are configurable so the same Dockerfile works against Docker Hub or a mirror:
#   docker build --build-arg REGISTRY=my-mirror.example.com/library .
# or override whole refs:
#   docker build --build-arg BUILD_IMAGE=my/golang:1.23 --build-arg RUNTIME_IMAGE=my/alpine:3.20 .
ARG REGISTRY=docker.io/library
ARG BUILD_IMAGE=${REGISTRY}/golang:1.23-alpine
ARG RUNTIME_IMAGE=${REGISTRY}/alpine:3.20

FROM ${BUILD_IMAGE} AS build
WORKDIR /src
# Stdlib-only service: no module downloads, builds fully offline.
ENV CGO_ENABLED=0 GOTOOLCHAIN=local GOFLAGS=-mod=mod
COPY go.mod ./
COPY main.go ./
COPY openapi.yaml ./
RUN go build -trimpath -ldflags="-s -w" -o /chunk .

FROM ${RUNTIME_IMAGE} AS runtime
RUN addgroup -S app -g 10001 && adduser -S app -G app -u 10001
COPY --from=build /chunk /usr/local/bin/chunk
USER 10001
EXPOSE 8090
ENTRYPOINT ["/usr/local/bin/chunk"]
