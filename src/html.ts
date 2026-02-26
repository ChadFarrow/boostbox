import { favicon, v4vbox } from "./images.js";
import type { BoostData } from "./storage.js";

function escapeHtml(str: string): string {
  return str
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;");
}

function formatSats(valueMsat: unknown): string | null {
  if (valueMsat == null || typeof valueMsat !== "number") return null;
  const sats = Math.round(valueMsat / 1000);
  return sats.toLocaleString("en-US");
}

function formatTimestamp(ts: unknown): string | null {
  if (ts == null || typeof ts !== "string") return null;
  try {
    const date = new Date(ts);
    return date.toLocaleDateString("en-US", {
      year: "numeric",
      month: "short",
      day: "numeric",
      hour: "numeric",
      minute: "2-digit",
      timeZone: "UTC",
      timeZoneName: "short",
    });
  } catch {
    return ts;
  }
}

function metadataRow(label: string, value: unknown): string {
  if (value == null || value === "") return "";
  return `<div class="boost-field"><strong class="boost-label">${escapeHtml(label)}</strong><span class="boost-value">${escapeHtml(String(value))}</span></div>`;
}

function boostDetailRows(data: BoostData): string {
  const sats = formatSats(data.value_msat_total);
  return [
    metadataRow("ID:", data.id),
    metadataRow("Time:", formatTimestamp(data.timestamp)),
    metadataRow("From:", data.sender_name),
    metadataRow("Amount:", sats ? `${sats} sats` : null),
    metadataRow("Show:", data.feed_title),
    metadataRow("Episode:", data.item_title),
    metadataRow("App:", data.app_name),
    metadataRow("Message:", data.message),
  ].join("\n");
}

const baseBoostCss = `.boost-field { display: grid; align-items: start; }
.boost-field:last-child { border-bottom: none; }
.boost-label { font-weight: 600; white-space: nowrap; }
.boost-value { word-break: break-word; }`;

const homepageCss = `${baseBoostCss}
body { text-align: center; margin: 0; padding: 0; background: #1a130d; }
main { width: 100vw; height: 100vh; background-size: cover; background-position: center; background-repeat: no-repeat; background-attachment: fixed; display: flex; flex-direction: column; align-items: center; overflow-y: auto; }
.overlay-top { margin-top: 2rem; width: 100%; max-width: 600px; padding: 1.5rem; background: rgba(40,30,20,0.75); border-radius: 12px; flex-shrink: 0; }
.overlay-top h1 { margin: 0; color: #fff; font-size: 3rem; }
.overlay-top p { font-size: 1.3rem; color: #ddd; margin: 0.5rem 0 0; }
.overlay-middle { width: 100%; max-width: 600px; padding: 1rem 0; display: flex; flex-direction: column; gap: 1rem; flex-shrink: 0; }
.boost-card-link { text-decoration: none; color: inherit; }
.boost-card { background: rgba(40,30,20,0.92); border-radius: 12px; padding: 1rem 1.5rem; text-align: left; transition: background 0.2s; }
.boost-card:hover { background: rgba(40,30,20,0.97); }
.boost-card .boost-field { grid-template-columns: minmax(80px, max-content) 1fr; gap: 0.5rem; padding: 0.3rem 0; border-bottom: 1px solid rgba(255,255,255,0.1); }
.boost-card .boost-label { color: #aaa; font-size: 0.85rem; }
.boost-card .boost-value { color: #fff; font-size: 0.85rem; }
.empty-state { color: #aaa; font-size: 1.1rem; padding: 2rem; }
.overlay-bottom { margin-bottom: 2rem; width: 100%; max-width: 600px; padding: 1rem; flex-shrink: 0; }
.button-group { display: flex; gap: 1rem; justify-content: center; flex-wrap: wrap; }
.button-group a { margin: 0; }`;

function boostCard(boost: BoostData): string {
  return `<a class="boost-card-link" href="/boost/${escapeHtml(boost.id)}"><div class="boost-card">${boostDetailRows(boost)}</div></a>`;
}

export function renderHomepage(boosts: BoostData[]): string {
  const boostCards = boosts.length > 0
    ? boosts.map(boostCard).join("\n")
    : `<div class="empty-state">No boosts yet</div>`;

  return `<!DOCTYPE html>
<html>
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <meta name="color-scheme" content="light dark">
  <title>TardBox</title>
  <link rel="icon" type="image/png" href="data:image/png;base64,${favicon}">
  <link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/@picocss/pico@2/css/pico.classless.min.css">
  <style>${homepageCss}</style>
</head>
<body>
  <main style="background-image: url('data:image/png;base64,${v4vbox}');">
    <div class="overlay-top">
      <h1>TardBox</h1>
      <p>Store and serve your boostagrams</p>
    </div>
    <div class="overlay-middle">
      ${boostCards}
    </div>
    <div class="overlay-bottom">
      <div class="button-group">
        <a href="https://github.com/ChadFarrow/boostbox" role="button">View on GitHub</a>
      </div>
    </div>
  </main>
</body>
</html>`;
}

const boostViewCss = `${baseBoostCss}
pre { background: var(--form-element-background-color); border: 1px solid var(--form-element-border-color); padding: 1rem; border-radius: 6px; overflow-x: auto; }
code { font-size: 0.9rem; }
.boost-card { border: 1px solid var(--form-element-border-color); padding: 1.5rem; border-radius: 6px; background: var(--card-background-color, transparent); }
.boost-field { grid-template-columns: minmax(100px, max-content) 1fr; gap: 1rem; padding: 0.75rem 0; border-bottom: 1px solid var(--form-element-border-color); }
.boost-field strong { color: var(--muted-color); }`;

export function renderBoostView(data: BoostData): string {
  const jsonPretty = escapeHtml(JSON.stringify(data, null, 2));

  return `<!DOCTYPE html>
<html>
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <meta name="color-scheme" content="light dark">
  <title>BoostBox Boost ${escapeHtml(data.id)}</title>
  <link rel="icon" type="image/png" href="data:image/png;base64,${favicon}">
  <link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/@picocss/pico@2/css/pico.classless.min.css">
  <link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.11.1/styles/atom-one-dark.min.css">
  <script src="https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.11.1/highlight.min.js"></script>
  <style>${boostViewCss}</style>
</head>
<body>
  <main>
    <h1>Boost Viewer</h1>
    <section>
      <article class="boost-card">
        <h3>Boost Details</h3>
        ${boostDetailRows(data)}
      </article>
    </section>
    <section>
      <h3>Metadata</h3>
      <pre><code class="language-json">${jsonPretty}</code></pre>
    </section>
  </main>
  <script>hljs.highlightAll();</script>
</body>
</html>`;
}
