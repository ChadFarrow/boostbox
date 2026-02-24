# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

BoostBox is a Clojure API for storing and retrieving Podcasting 2.0 payment metadata (boostagrams). Podcast apps POST boost metadata to `/boost`, receive a short URL, and that URL can later be fetched to retrieve the full metadata via an `x-rss-payment` HTTP header for Lightning invoice descriptions.

## Build & Development Commands

This project uses Nix flakes for all build/dev/test tooling.

### Enter dev environment
```sh
./dev.sh
```

### Available scripts (inside dev shell)
Run `scripts` to list all available commands. Key ones:
- `scripts repl` — Start NREPL on 0.0.0.0:9998
- `scripts tests` — Run full test suite (starts MinIO for S3 tests)
- `scripts watch` — Run tests in watch mode
- `scripts format` — Format all code (Clojure, Nix, Markdown via treefmt)
- `scripts build` — Build with Nix
- `scripts outdated` — Check for outdated dependencies
- `scripts lock` — Update lock files

### Run tests outside dev shell
```sh
./test.sh                    # Full suite with MinIO via Nix testenv
clojure -M:test              # Direct Clojure test runner (no S3 tests)
```

### Build and run
```sh
nix build                    # Compile via clj-nix
./result/bin/boostbox        # Run compiled binary

clojure -T:build uber        # Build uberjar (no Nix required)
java -jar target/boostbox.jar # Run uberjar
```

### Format check (CI)
```sh
nix flake check              # Runs treefmt formatting check
```

## Architecture

### Source Layout

- `src/boostbox/boostbox.clj` — Main application: config, storage, routes, middleware, HTML rendering, server startup (single-file monolith with `(:gen-class)`)
- `src/boostbox/ulid.clj` — Custom ULID encoding/decoding using Crockford Base32
- `src/boostbox/images.clj` — Loads base64 image assets (favicon, logo) from `resources/` at runtime
- `resources/v4vbox.b64` — Base64-encoded background image for landing page
- `resources/favicon.b64` — Base64-encoded favicon
- `test/boostbox/boostbox_test.clj` — All tests (unit + integration, both storage backends)

### Key Patterns

**Storage protocol:** `IStorage` protocol with two implementations:
- `LocalStorage` — filesystem, stores as `{root}/YYYY/MM/DD/{ulid}.json`
- `S3Storage` — AWS/MinIO via `cognitect.aws`, same path structure

**ID generation:** UUIDv7 → ULID (26-char Crockford Base32). The ULID embeds a timestamp used to derive the storage path.

**Validation:** Malli schemas for request/response coercion, integrated with reitit routes.

**HTTP stack:** Aleph server → reitit router → middleware chain (virtual threads, correlation IDs, MuLog logging, body size limiting, CORS, muuntaja content negotiation, Malli coercion, Swagger).

**HTML rendering:** Chassis (hiccup-like DSL) for homepage and boost viewer pages. The homepage displays all stored boosts as clickable cards overlaid on a fixed background image. Shared helpers (`boost-detail-rows`, `boost-metadata-row`, `format-sats`) are used by both the homepage cards and the individual boost viewer page.

**Image assets:** Base64 image data is stored in `resources/*.b64` files (not inline in source) to avoid exceeding JVM's 65535-byte constant pool limit during AOT compilation. The `build.clj` copies both `src/` and `resources/` into the uberjar.

**BOLT11 description:** `rss::payment::{action} {url} {message}` format, truncated to 639-char limit.

**Configuration:** All via environment variables (see README for full table). Key vars: `ENV`, `BB_PORT`, `BB_BASE_URL`, `BB_STORAGE`, `BB_ALLOWED_KEYS`.

### Testing

Tests use Kaocha with cloverage for code coverage. The `run-with-storage` helper runs integration tests against both FS and S3 backends. S3 tests require MinIO (started automatically by `./test.sh` or `scripts tests`). Set `BB_REAL_S3_IN_TEST=1` to enable real S3 tests.

### Deployment

- **Railway:** Dockerfile-based deploy at `https://tardbox.com`. `railway.toml` configures builder and healthcheck. Railway's `PORT` env var is mapped to `BB_PORT` automatically in the Dockerfile CMD. Domain via Namecheap DNS CNAME.
- **Docker:** Multi-stage `Dockerfile` builds an uberjar with `tools.build`, runs on `eclipse-temurin:21-jre-alpine`. Also available as `ghcr.io/noblepayne/boostbox:latest`.
- **Nix:** `nix run github:noblepayne/boostbox`
- **NixOS module:** `module.nix` provides a systemd service with hardened settings

### Dependencies (deps.edn)

HTTP: aleph (server), babashka.http-client (test client). Routing: reitit + swagger. Validation: malli. JSON: jsonista. HTML: chassis. AWS: cognitect.aws. Logging: mulog. IDs: clj-uuid.

Aliases: `:repl` (NREPL + CIDER), `:test` (kaocha), `:test/watch`, `:build` (tools.build uberjar), `:outdated` (antq).
