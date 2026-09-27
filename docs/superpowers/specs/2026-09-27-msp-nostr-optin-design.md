# MSP 2.0 Nostr posts: the artist picks

Date: 2026-09-27. Status: parked -- Chad wants the page, but it is a ways off. Not built. Depends on
boostbox#43, which carries the manual list (`BBN_PUBLISH_FEED_GUIDS`) until then.

## Context

The `msp-bot` (boostbox) reads every payment to the "MSP 2.0" split. A feed has that split only
when its artist keeps it: the MSP editor adds it with the first recipient, and the artist can
remove it. From boostbox#43 the bot posts a boost as a `kind:1` note from MSP 2.0's own npub, but
only for albums and artists named in a Railway variable, `BBN_PUBLISH_FEED_GUIDS`, that Chad edits
by hand. The first artist on it is Longy (`4d25f0dd-9270-4fb7-8aeb-ddd4a5213585`).

Chad wants to offer the Nostr posts as an MSP service to the artists who make their feeds with MSP
and keep him in their splits, and wants the artists to choose, not him. This spec replaces the
Railway list with a page on MSP where an artist signs in with Nostr, pastes a feed, and picks what
is posted.

## Decisions (Chad, 2026-09-27)

- **An artist opts in one feed at a time.** They sign in with Nostr and paste a feed. MSP saves it
  only when the feed passes three checks:
  1. **Made with MSP:** its `<generator>` starts with `MSP 2.0` (MSP writes
     `MSP 2.0 - Music Side Project Studio`, `xmlGenerator.ts`).
  2. **Chad is in the splits:** a `<podcast:valueRecipient>` named `MSP 2.0`, at a current or
     former MSP address (`musicsideproject@getalby.com`, `chadf@getalby.com`), on the channel or on
     any item.
  3. **The feed names the artist:** a `<podcast:txt purpose="npub">` equal to the signed-in npub.
     MSP's editor writes that tag (`artistNpub`). This is the proof of ownership, and it works
     wherever the feed is hosted -- Longy's are on `headstarts.uk`, not on MSP.
- **The artist picks four things:** which feeds; which payment types (boosts, auto-boosts);
  whether the amount is shown; whether the note mentions them.
- **No streams.** A stream is one payment per minute of listening; a note each would flood the
  artist's followers and the relays.
- **The page replaces the Railway list.** `BBN_PUBLISH_FEED_GUIDS` is for the test before the page
  ships, and is removed after.

## Not goals

- Posting boosts the bot cannot tie to a feed. A boost from a live music show that names only the
  show (v4vmusic's auto-boosts, 10 of Longy's 11 past boosts) stays unposted; that needs the app to
  send `remote_feed_guid`.
- Changing the chart. Every MSP payment is still forwarded to MSP's ingest, opted in or not.
- Re-posting history for artists who opt in later. `scripts/msp-notes.sh repost` can do that by
  hand with the feed guids; wiring it to the list is a follow-up.

## Part 1: the page (MSP-2.0)

A page, `/nostr-posts`, inside `NostrProvider` like `/admin` and `/charts`.

1. **Sign in** with the existing NIP-07 / NIP-46 login (`src/utils/nostrSigner.ts`).
2. **Paste a feed URL.** The server reads it through `api/_utils/safeFetch.ts` / `urlSafety.ts`,
   with MSP's usual size and time limits, and runs the three checks.
3. **The answer.** Either the feed's title and guid with a switch, or the first check that failed,
   in plain words:
   - "This feed was not made with MSP."
   - "This feed does not have MSP 2.0 in its splits."
   - "This feed does not name your npub. Add it as the artist npub in MSP and publish again."
   - "We could not read this feed."
4. **Saved feeds.** The page lists the feeds already saved for this npub, each with a switch and
   a remove button. The artist pastes each feed they want; nothing is found for them.
5. **Choices.** Per artist, not per feed: payment types (boost, auto-boost; at least one),
   *show the amount* (default on), *mention me* (default on).
6. **Save.** A `PUT` signed with NIP-98 (below). The server runs the three checks again for every
   feed that is on, and refuses the save if any fails, naming the feed.

## Part 2: storage and the API (MSP-2.0)

**Storage.** One Vercel Blob per artist: `nostr-optins/<pubkey hex>.json`, overwritten on save.

```json
{
  "pubkey": "<hex>",
  "feeds": [
    {
      "guid": "c5f25062-40b8-4ac4-88d3-a2c3af1f7310",
      "url": "https://headstarts.uk/msp/longy/Healing%20Hands/Healing_Hands.xml",
      "title": "Healing Hands",
      "on": true
    }
  ],
  "actions": ["boost", "auto"],
  "showAmount": true,
  "mention": true,
  "updatedAt": "2026-09-27T12:00:00Z"
}
```

A feed switched off stays in `feeds` with `"on": false`, so the page still shows it. Removing the
file is the same as everything off.

**The artist's endpoints:**

- `POST /api/nostr-optins/check` takes `{url}` and returns the feed's guid and title, or the check
  that failed. It saves nothing.
- `GET /api/nostr-optins/me` returns the stored file, or 404.
- `PUT /api/nostr-optins/me` takes `{feeds: [{url, on}], actions, showAmount, mention}` and returns
  the stored file. The server fills in each feed's guid and title from its own read, never from the
  request.

All three use `Authorization: Nostr <base64 kind:27235>`. The existing `validateFeedAuthEvent`
checks kind, age (5 min) and signature, but **not** the `u` and `method` tags, so an event signed
for another MSP endpoint would be accepted within those 5 minutes. These endpoints must also
require `u` to equal their own URL, `method` to equal the request's, and on `POST`/`PUT` a
`payload` tag equal to the SHA-256 of the body (NIP-98).

`actions` accepts only `boost` and `auto`. `feeds` is at most 100.

**The bot's endpoint:** `GET /api/nostr-optins`.

- `Authorization: Bearer <MSP_BOT_INGEST_TOKEN>`, the token the msp-bot already sends to the
  ingest (`BBN_FORWARD_TOKEN`). No new secret.
- Returns every feed that is on, flattened by feed guid:

```json
{
  "feeds": {
    "c5f25062-40b8-4ac4-88d3-a2c3af1f7310": {
      "npub": "npub1…", "actions": ["boost", "auto"], "showAmount": true, "mention": true
    }
  },
  "generatedAt": "2026-09-27T12:00:00Z"
}
```

- If two artists saved one feed (it names both npubs), it appears once, with the choices that post
  less: only the `actions` both picked, and `showAmount` and `mention` true only if both set them
  true.
- `Cache-Control: private, no-store`, as the chart's 401 is.

## Part 3: the bot (boostbox)

**Config.**

- `BBN_OPTINS_URL`, e.g. `https://musicsideproject.com/api/nostr-optins`.
- The bearer token is `BBN_FORWARD_TOKEN`.
- `BBN_PUBLISH_FEED_GUIDS` is removed. A bot without `BBN_OPTINS_URL` posts every boost, which is
  the Boostr bot, unchanged.

**Reading the list.**

- At most every 5 minutes, before a poll that has a boost to decide.
- A failed read keeps the last good list and logs `::optins-read-failed` once per failure streak.
- **Until the first read succeeds, nothing is posted** (`::optins-not-loaded` on each such boost).
  The list is consent; a bot that cannot read it must not guess.
- The list is held in memory only. A restart reads it again before posting.

**Deciding a boost** (in `publish-boost!`, where #43 put the list check: after the feed reads,
before the store).

1. The feed is the boost's `feed_guid` if it is on the list, else its `remote_feed_guid` if that
   is. Neither: not posted.
2. The boost's action is in that feed's `actions`. Else not posted.
3. **The feed still says so.** The feed read for it -- the host feed for `feed_guid`, the remote
   feed for `remote_feed_guid`, both of which #43 already reads -- has a
   `<podcast:txt purpose="npub">` equal to the list's `npub`. A feed that cannot be read, or no
   longer names the artist, is not posted. `boostbox.feed` returns the txt-tag npubs separately
   from `podcast:person` npubs for this, because MSP's proof uses the txt tag only.

The bot does not re-check the generator or the split: a payment on the MSP 2.0 split is the only
kind it reads.

A boost not posted is still forwarded, and logs `::boost-not-opted-in` once with the guids and the
reason (`not-listed`, `action`, `npub-mismatch`, `feed-unreadable`, `optins-not-loaded`).

**Rendering the choices.**

- `showAmount` off: the body line reads `{sender} boosted → {show}` (or `Boosted → {show}`), the
  banner URL carries no `sats`, and there is no `amount` tag. `note-sats` and `format-sats` are
  untouched.
- `mention` off: the artist's npub gets no `p` tag. The show's people keep theirs.
- Everything else as #43: the MSP 2.0 npub, the NIP-73 ids including `podcast:publisher:guid`, the
  album's cover.

## Security notes

- Every feed MSP reads comes from a URL the artist pasted, so it goes through `safeFetch` (address
  check, no redirects to private space, size cap), as the ingest's lookups do.
- The proof is only as strong as the feed: whoever can edit a feed's RSS can claim it. That is the
  same trust every Podcasting 2.0 client gives the feed, and it is why the bot re-checks the feed at
  post time rather than trusting the page's check forever.
- The generator check is a product rule (the service is for MSP's artists), not a security check:
  anyone can write that tag. The split and npub checks carry the weight.
- The bot's endpoint lists which feeds opted in. It is behind the bot token anyway, since nothing
  else needs it.

## Testing

**MSP-2.0 (vitest):**
- each check: generator missing or other; no MSP 2.0 split, the name at another address, the
  split on an item only; txt npub equal, another npub, none, `podcast:person` only (not proof)
- Longy's real feeds as fixtures: six pass, "Auto Inter Woven Minds" fails on the split, "Into the
  Valueverse 1" fails on the npub
- NIP-98: wrong `u`, wrong `method`, wrong `payload`, stale event
- `PUT` refuses a feed that fails a check, `actions` outside boost/auto, 101 feeds; guid and title
  come from the server's read, not the request
- the flattened list, including the two-artist rule
- the bot endpoint refuses a missing or wrong token

**boostbox (Kaocha):**
- each branch of the decision, and a boost not posted is still forwarded
- no posts until the first successful read, and the last good list survives a failed read
- `showAmount` off and `mention` off, in the body, the banner URL and the tags
- the Boostr bot without `BBN_OPTINS_URL` posts as before

**Live, before switching over:** Longy signs in, saves one feed, and a boost on it posts from the
MSP 2.0 npub with the chosen settings. Then `BBN_PUBLISH_FEED_GUIDS` is removed.

## Open questions (settle before building)

- **Per artist or per feed?** This spec sets payment types, *show the amount* and *mention me* once
  per artist, and only the on/off switch per feed. Chad has not confirmed that.
- **The rest of #43 may have moved on.** Re-read `publish-boost!` and `feed-listed?` before the
  plan: this spec names them as they were on 2026-09-27.

## Rollout

1. Merge boostbox#43; test with Longy on `BBN_PUBLISH_FEED_GUIDS`.
2. Build MSP-2.0 parts 1 and 2 (a session opened in MSP-2.0). Deploy; the page works, nothing
   reads it yet.
3. Build boostbox part 3. Set `BBN_OPTINS_URL` on the msp-bot, in the same staged change that
   removes `BBN_PUBLISH_FEED_GUIDS`.
4. Longy (and each next artist) opts in on the page.
