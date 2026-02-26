# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

BoostBox is a TypeScript/Node.js API for storing and retrieving Podcasting 2.0 payment metadata (boostagrams). Podcast apps POST boost metadata to `/boost`, receive a short URL, and that URL can later be fetched to retrieve the full metadata via an `x-rss-payment` HTTP header for Lightning invoice descriptions.

## Build & Development Commands

### Install dependencies
```sh
npm install
```

### Development
```sh
npm run dev          # Start dev server with hot reload (tsx watch)
npm run lint         # Type-check without emitting
```

### Run tests
```sh
npm test             # Run full test suite (vitest)
npm run test:watch   # Run tests in watch mode
```

### Build and run
```sh
npm run build        # Compile TypeScript to dist/
npm start            # Run compiled app from dist/
```

## Architecture

### Source Layout

- `src/index.ts` — Main application: config, Fastify server, routes, middleware, startup
- `src/ulid.ts` — Custom ULID encoding/decoding using Crockford Base32
- `src/storage.ts` — IStorage interface with LocalStorage (filesystem) and S3Storage implementations
- `src/html.ts` — HTML rendering for homepage and boost viewer pages
- `src/images.ts` — Loads base64 image assets (favicon, logo) from `resources/` at runtime
- `src/schemas.ts` — Zod validation schemas for boost metadata
- `src/bolt11.ts` — BOLT11 invoice description formatting
- `resources/v4vbox.b64` — Base64-encoded background image for landing page
- `resources/favicon.b64` — Base64-encoded favicon
- `test/ulid.test.ts` — ULID unit tests
- `test/bolt11.test.ts` — BOLT11 description unit tests
- `test/e2e.test.ts` — End-to-end integration tests

### Key Patterns

**Storage interface:** `IStorage` interface with two implementations:
- `LocalStorage` — filesystem, stores as `{root}/YYYY/MM/DD/{ulid}.json`
- `S3Storage` — AWS/MinIO via `@aws-sdk/client-s3`, same path structure

**ID generation:** UUIDv7 → ULID (26-char Crockford Base32). The ULID embeds a timestamp used to derive the storage path.

**Validation:** Zod schemas with `.passthrough()` to allow extra fields.

**HTTP stack:** Fastify server with CORS, Swagger/OpenAPI, body size limiting, correlation IDs.

**HTML rendering:** Template literals for homepage and boost viewer pages. The homepage displays all stored boosts as clickable cards overlaid on a fixed background image.

**BOLT11 description:** `rss::payment::{action} {url} {message}` format, truncated to 639-char limit.

**Configuration:** All via environment variables. Key vars: `ENV`, `BB_PORT`, `BB_BASE_URL`, `BB_STORAGE`, `BB_ALLOWED_KEYS`.

### Testing

Tests use Vitest. E2E tests spin up a real Fastify server on a random port with LocalStorage in a temp directory.

### Deployment

- **Railway:** Dockerfile-based deploy at `https://tardbox.com`. `railway.toml` configures builder and healthcheck. Railway's `PORT` env var is mapped to `BB_PORT` automatically in the Dockerfile CMD.
- **Docker:** Multi-stage `Dockerfile` builds TypeScript, runs on `node:22-alpine`.

### Dependencies (package.json)

HTTP: fastify, @fastify/cors, @fastify/swagger, @fastify/swagger-ui. Validation: zod. AWS: @aws-sdk/client-s3. IDs: uuid.

Dev: typescript, tsx, vitest.
