# Tandem push service: a static Go binary on scratch, non-root, ~10 MB image (docs/PUSH.md).
# Built only in GitHub Actions (push-service.yml); published to GHCR only from a v* tag.
FROM golang:1.27.2-alpine@sha256:f92b6ef800e499660581efdabdf25d9d817a9d124eaf900924f0504e7e27e12d AS build
ARG VERSION=dev
WORKDIR /src
COPY go.mod ./
COPY *.go ./
RUN CGO_ENABLED=0 GOFLAGS=-trimpath go build -ldflags "-s -w -X main.version=${VERSION}" -o /out/push-service . \
    && mkdir -p /out/data \
    && apk add --no-cache ca-certificates >/dev/null

FROM scratch
COPY --from=build /etc/ssl/certs/ca-certificates.crt /etc/ssl/certs/ca-certificates.crt
COPY --from=build /out/push-service /push-service
COPY --from=build --chown=65532:65532 /out/data /data
USER 65532:65532
ENV LISTEN_ADDR=:8080 DATA_DIR=/data
EXPOSE 8080
VOLUME ["/data"]
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 CMD ["/push-service", "-healthcheck"]
ENTRYPOINT ["/push-service"]
