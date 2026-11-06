# tandem-push

The optional push companion of [Tandem](../README.md): it polls **one** household's Tandoor
shopping list and wakes the household's phones through UnifiedPush when the list changed, so a
partner's item arrives as a notification within a minute. The wake-up never contains list content;
the phone syncs and shows its own notification. Tandem works fine without it.

- Go standard library only, static binary on `scratch`, runs as a non-root user, ~10 MB image,
  well under 30 MB RAM idle.
- Configuration by environment variables only (`TANDOOR_URL`, `TANDOOR_TOKEN` or
  `TANDOOR_TOKEN_FILE`, `POLL_SECONDS`, `LISTEN_ADDR`, `DATA_DIR`, `TRUSTED_PROXIES`, …). No
  defaults point at any real host.
- Operator guide, data kept, security and the API: [docs/PUSH.md](../docs/PUSH.md).
- Design notes: docs/BYGGE.md, section "Push-tjänst".

## Development

Everything is built and tested in GitHub Actions (`.github/workflows/push-service.yml`):

- `go test -race ./...`: unit tests with fake Tandoor and fake push endpoints.
- `go test -tags integration ./...`: run by the workflow against a throwaway Tandoor (2.6.13 and
  2.7.1) and the built image; a partner's item must wake the phone within 60 s.
- The image is built on every change, for linux/amd64 and linux/arm64, and published to GHCR only
  from a `v*` tag (docs/RELEASE.md).

## License

Apache License 2.0, like the rest of the repository.
