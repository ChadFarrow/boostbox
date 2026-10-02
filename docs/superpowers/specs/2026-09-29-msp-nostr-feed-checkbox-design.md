# MSP 2.0 Nostr posts: a checkbox on the split

Date: 2026-09-29. Status: draft, for comparison with the parked sign-in page
(`2026-09-27-msp-nostr-optin-design.md`, on branch `docs/msp-nostr-optin`). Not built.

## Context

The `msp-bot` posts a boost to Nostr from MSP 2.0's npub only for albums and artists in
`BBN_PUBLISH_FEED_GUIDS`, a Railway variable Chad edits by hand after asking each artist. On
2026-09-28 it posted its first Fountain and BoostMeBitch boosts on Longy's albums, the only artist
on the list. The same day a boost on Fentlay's "The Singles" carried the MSP 2.0 split and went
unposted, as it should: nobody had asked Fentlay.

The parked spec replaces the variable with a page on MSP where an artist signs in with Nostr,
pastes a feed, and picks what is posted. This spec is the smaller alternative Chad asked for: a
checkbox on the MSP 2.0 split in MSP's feed editor. The artist decides in the editor they already
use, at the moment they choose to keep MSP in their splits, and the answer travels inside the feed.

## The idea

- The MSP editor shows a checkbox on the **MSP 2.0** split row: *"Post boosts on this album to
  Nostr from MSP 2.0"*. **Off by default.**
- Ticked, the editor writes one tag into the channel when it generates the feed:

  ```xml
  <podcast:txt purpose="msp-nostr">allow</podcast:txt>
  ```

- The msp-bot already reads the boosted album's feed for every boost it considers (for the cover,
  the artist and, since 2026-09-28, the split names). It posts only if that feed carries the tag.
- Unticked and republished, the tag is gone and the bot stops posting within the cache window
  (below).

There is no page, no storage, no API and no new secret. The feed is the record of consent.

## Decisions to confirm (Chad)

- **Per album.** The tag lives in one album's feed and covers that album only. An artist ticks it
  on each album they want posted. (An artist-wide switch would need MSP to write into the artist's
  publisher feed, which the bot reads only as a guid today.)
- **The value is `allow`.** It means "MSP 2.0 may post this album's boosts". Anything else, or no tag, means
  off: the bot never guesses consent from a value it does not recognise. Which payment types post
  stays the bot's `BBN_ACTIONS` (`boost,auto` on msp-bot), not something the feed spells out. Finer
  choices later (hide the amount, do not mention the artist) get their own tags, e.g.
  `purpose="msp-nostr-amount"` with `hide`, so `allow` never changes meaning.
- **Streams are never posted,** whatever the tag says: `BBN_ACTIONS` never includes `stream`.
- **The manual list stays, as an override.** `BBN_PUBLISH_FEED_GUIDS` keeps working for feeds not
  made in the editor, or before an artist republishes. A boost posts if its album carries the tag
  **or** it is on the list. Longy's albums keep posting through the switch-over.

## Part 1: the editor (MSP-2.0)

To be built in a session opened in the MSP-2.0 repo. The file names below are from the parked
spec and must be checked there.

1. **The checkbox.** On the MSP 2.0 recipient row (`RecipientsList.tsx`), shown only while that
   split exists. Removing the split removes the tag too, since without the split the bot never sees
   a payment from the album anyway.
2. **The state.** A field on the feed in `feedStore.tsx`, e.g. `mspNostrPosts: boolean`, default
   `false`, saved with the rest of the feed.
3. **The tag.** `xmlGenerator.ts` writes `<podcast:txt purpose="msp-nostr">allow</podcast:txt>`
   in the channel when the field is true, and nothing when false. Channel only, never on an item.
4. **Import.** Loading an existing feed that has the tag ticks the box, so re-editing a feed does
   not silently untick it.
5. **Copy next to the box,** so the artist knows what they are agreeing to: "Boosts on this album,
   with the booster's name, message and amount, are posted as public Nostr notes from MSP 2.0's
   account (npub1lllk57…). Untick and republish to stop; notes already posted stay up."

## Part 2: the bot (boostbox)

**Reading the tag.** `boostbox.feed/read-feed` returns one more key, `:msp-nostr?`, true when the
first channel-level `<podcast:txt purpose="msp-nostr">` holds `allow`, compared trimmed and
case-insensitively. Read from the channel slice only, so an item cannot opt in an album; the
purpose is matched case-insensitively like the existing `nostr`/`npub` purposes. The purpose name
is a constant, not configuration.

**Which feed is the album's.** The same two feeds `publish-boost!` already reads:

- the host feed, for a boost made on the album itself (`feed_guid` is the album);
- the remote feed, for a boost made from a music show (`remote_feed_guid` is the album).

A boost is consented when the album's feed carries the tag with `allow`. The action still has to be
in `BBN_ACTIONS`, as it does today, before the boost reaches this check. The show's own feed does
not count for a remote item: a music show ticking the box must not opt in the artists it plays. So
for a boost with a remote guid, only the remote feed's tag counts.

**Deciding a boost** (in `publish-boost!`, where `bg/feed-listed?` runs today: after the feed
reads, before the store):

```text
post = (the album's feed says msp-nostr: allow)
       OR (feed-listed? against BBN_PUBLISH_FEED_GUIDS)
```

- A feed that cannot be read carries no tag, so the boost is not posted unless it is on the manual
  list. As today, the list check errs towards silence.
- A boost not posted is still forwarded to the chart, as today, and `::boost-not-listed` still
  logs once, now with `:consent-tag` (the value seen, or nil) beside the guids.

**The empty list must stop meaning "every album" on this bot.** Today `feed-listed?` treats an
empty `BBN_PUBLISH_FEED_GUIDS` as "post everything", which is right for Boostr. Once the tag
exists, Chad may want to empty the list after the artists have ticked their boxes, and that must
not open the bot to every album. So a new variable, `BBN_REQUIRE_CONSENT=true` on `msp-bot`, makes
an empty list mean "nothing extra": only tagged albums post. Unset (Boostr), nothing changes.
`bot-starting` logs it beside the list count.

**Freshness: the cache must not outlive an untick.** `feed-cache` keeps a successful read until 32
entries fill it, which on a quiet album could be days. For a consent read that is too long: an
artist who unticks expects it to stop. Found values in `feed-cache` get a lifetime too, 30 minutes,
alongside the 10-minute miss lifetime `memoized!` already has. Hosting CDNs may add their own delay
on top; the editor copy should say "within an hour".

## Compared with the sign-in page

| | Checkbox (this spec) | Sign-in page (parked) |
|---|---|---|
| Where the artist decides | MSP's feed editor, next to the split | A separate page, after signing in |
| What MSP stores | Nothing new; the feed carries it | A blob per artist and three endpoints |
| Proof it is the artist | Whoever edits the feed | The feed names the signed-in npub |
| Feeds not made in the editor | Tag added by hand, or the manual list | Any feed with the split and the npub |
| Hide amount / no mention | Later, as tags of their own | Built in |
| Revoking | Untick, republish; within the cache window | Switch off; within 5 minutes |
| Work | A checkbox, a field, one tag; a small bot change | A page, NIP-98 auth, storage, an API, a bot client |

On security the two are close. Both rest on the feed: in the page design the feed must name the
signed-in npub, and whoever can edit the feed can write that too. The page adds a check that the
person clicking holds the npub the feed names; the checkbox relies on MSP's editor being the
artist's own tool. Neither can be forged by a payer, since both read only the feed.

## Not goals

- Posting boosts the bot cannot tie to an album (a live music show sending no `remote_feed_guid`).
  Same as the parked spec.
- Deleting notes already posted when an artist unticks. `scripts/msp-notes.sh delete` can do that
  by hand from an export; wiring it up is a follow-up.
- Re-posting an album's history when its box is ticked later. Same as the parked spec: by hand.

## Testing

**MSP-2.0:** the checkbox shows only with the MSP 2.0 split; ticking writes exactly the tag in the
channel; unticking and removing the split both remove it; importing a tagged feed ticks the box.

**boostbox (Kaocha):**
- `read-feed`: `allow` on the channel is read, in any case and with spaces around it; on an item
  only it is not; blank, `yes`, or any other value is off; purpose case.
- the decision: host-feed tag, remote-feed tag, a show's tag not counting for a remote item,
  unreadable feed, manual list still working, tag or list either enough.
- `BBN_REQUIRE_CONSENT`: an empty list posts nothing untagged; unset, an empty list posts all
  (Boostr unchanged).
- the cache: a tag removed from a feed stops posts once the found value expires.
- a boost not posted is still forwarded.

**Live:** tick the box on one of Longy's albums, republish, boost it from Fountain, and see the
note; remove that album's guid from the manual list only after the tag is confirmed.

## Rollout

1. boostbox: read the tag and add the `OR` and the found-value lifetime. Deploy with
   `BBN_REQUIRE_CONSENT=true` on `msp-bot`, the manual list unchanged. Nothing posts differently
   until a feed carries the tag.
2. MSP-2.0: the checkbox, the field, the tag. Deploy.
3. Ask Longy (and Fentlay, and each next artist) to tick it and republish.
4. Once an artist's albums carry the tag, their entry can leave `BBN_PUBLISH_FEED_GUIDS`. Keep the
   list for feeds made outside the editor.

## Open questions

- **Per album or per artist?** This spec is per album. Per artist needs MSP to write the tag into
  the artist's publisher feed and the bot to read that feed too.
- **Does every MSP-built feed get republished often enough** for the tag to reach feeds hosted
  elsewhere (Longy's on headstarts.uk)? If an artist uploads the file by hand, they must upload it
  again after ticking.
