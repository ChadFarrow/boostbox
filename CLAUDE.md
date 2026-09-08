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

Boost bot (separate process, same uberjar — see "Boost Bot" below):

- `src/boostbox/nostr.clj` — BIP-340 schnorr, NIP-01 canonical events, NIP-04, NIP-19 bech32. Zero third-party deps beyond BouncyCastle
- `src/boostbox/relay.clj` — Nostr relay websocket client over aleph
- `src/boostbox/nwc.clj` — Nostr Wallet Connect (NIP-47) client and boostagram extraction
- `src/boostbox/boostagram.clj` — blip-10 → BoostMetadata mapping, NIP-73 tags, note text (all pure)
- `src/boostbox/nostrbot.clj` — `(:gen-class)` entry point: config, cursor/dedupe state, poll loop
- `test/resources/bip340-vectors.csv` — unmodified official BIP-340 vectors from bitcoin/bips
- `railway.bot.toml` — Railway config for the bot service (see Deployment)

Operator scripts (all read-only against the wallet, all prompt for the NWC string with echo off rather than taking it as an argument, where `ps` would expose it):

- `scripts/bot-setup.sh` — validate an NWC connection string and mint the bot's Nostr key into a 0600 file, printing only the npub
- `scripts/nwc-inspect.sh` — dump what the wallet actually reports for recent payments: `description`, TLV types, whether a boostagram can be extracted. The first thing to run when the bot sees payments but publishes nothing
- `scripts/bot-local.sh` — run the bot locally in dry-run with backfill, against the same wallet as the deployed one. Its cursor goes to a temp dir, so the deployed bot's state is untouched

### Key Patterns

**Storage protocol:** `IStorage` protocol with two implementations:
- `LocalStorage` — filesystem, stores as `{root}/YYYY/MM/DD/{ulid}.json`
- `S3Storage` — AWS/MinIO via `cognitect.aws`, same path structure

**ID generation:** UUIDv7 → ULID (26-char Crockford Base32). The ULID embeds a timestamp used to derive the storage path.

**Validation:** Malli schemas for request/response coercion, integrated with reitit routes.

**HTTP stack:** Aleph server → reitit router → middleware chain (virtual threads, correlation IDs, MuLog logging, body size limiting, CORS, muuntaja content negotiation, Malli coercion, Swagger).

**HTML rendering:** Chassis (hiccup-like DSL) for homepage and boost viewer pages. The homepage displays all stored boosts as clickable cards overlaid on a fixed background image. Shared helpers (`boost-detail-rows`, `boost-metadata-row`, `format-sats`) are used by both the homepage cards and the individual boost viewer page.

**Chassis text escaping (footgun):** Chassis HTML-escapes string children by default, including inside `<script>` and `<style>`. Any inline JS containing `<`, `>`, or `&` (e.g. `i<n`, `a<<5`, `x&0xff`) becomes a syntax error when rendered as `[:script some-js-string]`. Wrap inline script/style payloads in `(html/raw ...)` — see how `npub-resolve-js` is injected at both call sites.

**Client-side npub resolution:** `npub-resolve-js` is an inline JS blob injected on both homepage and boost-view. It walks text nodes for `npub1…` (optionally `nostr:`-prefixed), replaces them with a truncated `<span class="npub-ref" data-npub="…">`, then fans out a `kind:0` REQ in parallel across `wss://relay.damus.io`, `wss://nos.lol`, and `wss://relay.primal.net`. First relay to return a profile with `display_name`/`name` wins and closes the other sockets. No server-side Nostr code — all resolution is client-side. If you add another spot that renders an npub, no extra work is needed as long as it ends up in a text node within `document.body`.

**Image assets:** Base64 image data is stored in `resources/*.b64` files (not inline in source) to avoid exceeding JVM's 65535-byte constant pool limit during AOT compilation. The `build.clj` copies both `src/` and `resources/` into the uberjar.

**BOLT11 description:** `rss::payment::{action} {url} {message}` format, truncated to 639-char limit.

**Boost Bot:** A second process — `java -cp boostbox.jar boostbox.nostrbot` — that watches a Lightning wallet over NWC, stores incoming boostagrams via `POST /boost`, and republishes them to Nostr as `kind:1` notes with NIP-73 `i`/`k` tags. It is deliberately NOT a route on the web app: it holds an nsec and a wallet credential, and it is a long-lived loop with at-least-once delivery. `build.clj` compiles everything under `src/`, so its `(:gen-class)` lands in the same uberjar automatically — no build changes needed to add an entry point.

**Bot identity is two events, not one (`BBN_PUBLISH_PROFILE`):** `kind:0` gives the npub a name; the NIP-65 `kind:10002` relay list is what makes it findable, since without one a client cannot know which relays hold the bot's notes. Both are one-shot and opt-in. The relay list marks every relay `write` — the bot publishes and reads nothing — and never includes the wallet relay, which is reachable only with the NWC credential.

**Nostr crypto (do not hand-edit casually):** `boostbox.nostr` implements BIP-340 signing on BouncyCastle's curve math. It is verified against the official BIP-340 vectors in `test/resources/bip340-vectors.csv`, including the 10 failure cases. Any change to the signing, `lift-x`, or tagged-hash code MUST keep those green — a subtly wrong signature is silently accepted by nothing and rejected by every relay.

**NIP-01 serialization is hand-written on purpose:** NIP-01 escapes exactly seven characters and emits everything else — forward slashes, non-ASCII — verbatim. jsonista escapes a different set, which yields a different event id and therefore an invalid signature. Never swap `boostbox.nostr/canonical-event` for a general JSON encoder.

**Three metadata sources (`nostrbot/tx->boost!`):** TLV `7629169` first, then the wallet's parsed copy, then a *boost link* in the payment description, fetched via its `x-rss-payment` header. The third is not a fallback for odd cases — an LNURL payment cannot carry a TLV at all, and that is how most Podcasting 2.0 apps pay a lightning address, so for a bot paid at one it is the **common** path. A link-sourced boost already has a BoostBox record (that is where the link came from), so `publish-boost!` reuses its url and id instead of POSTing a duplicate.

**The boost-link fetch is a security boundary — read before touching it.** The payment description is written by whoever paid us, so the URL is attacker-controlled. Three separate things guard it, and they are not interchangeable:

1. `bg/boost-link` decides a link's **shape** — https only, and within `:boost-link-origins` when that is set. It is *not* the protection: the default is to accept any public https origin, because apps POST to whichever BoostBox they run and a podcaster adding this bot as a split cannot enumerate those in advance. An allowlist here would skip almost every real boost.
2. `nostrbot/public-addresses` decides the **destination** — resolves the host and refuses loopback, link-local, RFC1918, CGNAT, IPv6 ULA, multicast and reserved space. This is the check that matters.
3. `nostrbot/head-pinned!` opens the socket **to the address already checked**, rather than handing a hostname to an HTTP client that would resolve it a second time. That second lookup is the DNS-rebind window. SNI and endpoint identification stay on, so pinning the address does not weaken certificate validation.

`HEAD` only, redirects refused (a public host answering 302 with a private address is the standard way around an address check), headers read under a byte cap, body never read. Do not "simplify" this back to `http/get` on a URL — it looks like an over-complicated way to make one request, and every part of it is load-bearing.

**Payer-written text is bounded (`boostagram/clean`):** message, titles and names all reach a `kind:1` note signed by the bot's key, and every one of them is written by whoever paid. `normalize` strips control characters and caps lengths there, at the boundary, rather than at each use.

**Two schemas, one repo:** `boostbox.boostbox`'s `BoostMetadata` and blip-10 name the same things differently — `feed_guid`/`guid`, `item_guid`/`episode_guid`, `feed_title`/`podcast`, `item_title`/`episode`, `recipient_name`/`name`, `position`/`ts`. Apps built on the BoostBox spec send the former; `normalize` accepts both spellings of all six, blip-10 winning ties. Any new field needs the same treatment or it silently reads as nil for half of real traffic.

**Boostagram GUIDs:** the blip-10 payload lives in TLV record `7629169`. Read the *raw* record, not a wallet's pre-parsed copy: Alby Hub's `Boostagram` struct keeps `feedID`/`itemID` but drops every GUID, and NIP-73 needs the feed GUID (a UUID). `boostbox.nwc/extract-boostagram` encodes this preference.

**blip-10 value traps (beyond the naming above):** `ts` is **seconds into the episode**, which maps to BoostBox's `position`, not its `timestamp` — the wall-clock time comes from the payment's `settled_at`. `value_msat` is only this split's share; `value_msat_total` is the whole boost, and it is **frequently absent** (Alby's parsed struct drops it, single-recipient splits never set it) — every use of it needs a `received-msat` fallback, or the note headline reads "0 sats" and `BBN_MIN_SATS` silently drops real boosts.

**Feed guid vs item guid (`boostbox.boostagram`):** they are validated differently and must stay that way. `<podcast:guid>` is defined as a UUID, so `valid-feed-guid?` rejects everything else — a malformed `i` tag pollutes the global NIP-73 index forever. An episode guid is the RSS `<item><guid>`, an arbitrary string (usually a URL), so `valid-item-guid?` only rejects blanks and absurd lengths and the value is **not** lower-cased. Tightening the item check to UUIDs drops episode tags for almost every real feed.

**Storage root is shared (`boost-key?`):** the nostrbot keeps its cursor at `nostrbot/state.json` under the same FS root / S3 bucket the web app reads. Both `list-all` implementations filter through `bb/boost-key?`, which matches only `.../<26-char ULID>.json`. Any sidecar file added to the storage root must not be listable as a boost — a plain `*.json` filter renders it as a phantom boost card on the homepage.

**Configuration:** All via environment variables (see README for full table). Key vars: `ENV`, `BB_PORT`, `BB_BASE_URL`, `BB_STORAGE`, `BB_ALLOWED_KEYS`.

### Testing

Tests use Kaocha with cloverage for code coverage. The `run-with-storage` helper runs integration tests against both FS and S3 backends. S3 tests require MinIO (started automatically by `./test.sh` or `scripts tests`). Set `BB_REAL_S3_IN_TEST=1` to enable real S3 tests.

### Deployment

- **Railway (web app):** Dockerfile-based deploy at `https://tardbox.com`. `railway.toml` configures builder and healthcheck. Railway's `PORT` env var is mapped to `BB_PORT` automatically in the Dockerfile CMD. Domain via Namecheap DNS CNAME. Storage is `BB_STORAGE=FS` on an attached Railway volume, not S3.
- **Railway (bot):** a *second service on the same repo and image*, whose "Config-as-code file path" must point at **`railway.bot.toml`**. Two traps, both of which fail quietly:
  - The Dockerfile `CMD` starts the *web app*. Without `startCommand` you deploy a second copy of tardbox — and the only symptom is `boostbox.boostbox/app-starting-up` in the logs where `boostbox.nostrbot/bot-starting` should be. That one line is the fastest way to tell which program a service is actually running.
  - `railway.toml` sets `healthcheckPath = "/health"` for every service on the repo, and the bot serves no HTTP at all. Config-as-code beats dashboard settings, so overriding it in the UI does not reliably stick; the bot needs its own config file. `numReplicas = 1` is deliberate — two replicas would poll the same wallet and race on the same state file, publishing duplicate notes.
- **Docker:** Multi-stage `Dockerfile` builds an uberjar with `tools.build`, runs on `eclipse-temurin:21-jre-alpine`. Also available as `ghcr.io/noblepayne/boostbox:latest`.
- **Nix:** `nix run github:noblepayne/boostbox`
- **NixOS module:** `module.nix` provides a systemd service with hardened settings

### Dependencies (deps.edn)

HTTP: aleph (server + relay websocket client), babashka.http-client (test client, and the bot's BoostBox client). Routing: reitit + swagger. Validation: malli. JSON: jsonista. HTML: chassis. AWS: cognitect.aws. Logging: mulog. IDs: clj-uuid. Crypto: bouncycastle (`bcprov-jdk18on`, secp256k1 for BIP-340 and NIP-04 ECDH — pure Java, so no native libs and it works unchanged on the alpine runtime image).

**Adding a dependency requires regenerating `deps-lock.json`** (`scripts lock`, i.e. `nix flake lock && nix run .#deps-lock`). `nix build` pins every artifact by sha256 and will fail against a stale lock.

Aliases: `:repl` (NREPL + CIDER), `:test` (kaocha), `:test/watch`, `:build` (tools.build uberjar), `:outdated` (antq).
