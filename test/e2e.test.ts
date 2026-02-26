import { describe, it, expect, beforeAll, afterAll } from "vitest";
import type { FastifyInstance } from "fastify";
import { buildServer, type AppConfig } from "../src/index.js";
import { LocalStorage } from "../src/storage.js";
import { mkdtempSync, rmSync } from "node:fs";
import { join } from "node:path";
import { tmpdir } from "node:os";
import { genUlid } from "../src/ulid.js";

function minimalBoostPayload() {
  return {
    action: "boost",
    split: 1.0,
    value_msat: 1000,
    value_msat_total: 1000,
    timestamp: new Date().toISOString(),
    message: "test boost!",
  };
}

function makeTestConfig(port: number, tmpDir: string, baseUrl?: string): AppConfig {
  return {
    env: "DEV",
    storage: "FS",
    port,
    baseUrl: baseUrl ?? `http://localhost:${port}`,
    allowedKeys: new Set(["test-key"]),
    maxBodySize: 1024,
    rootPath: tmpDir,
  };
}

describe("e2e tests", () => {
  let app: FastifyInstance;
  let baseUrl: string;
  let tmpDir: string;

  beforeAll(async () => {
    tmpDir = mkdtempSync(join(tmpdir(), "boostbox-test-"));
    // First pass with placeholder baseUrl; we update after listen
    const storage = new LocalStorage(tmpDir);
    const config = makeTestConfig(0, tmpDir);
    app = await buildServer(config, storage);
    await app.listen({ port: 0, host: "127.0.0.1" });
    const address = app.server.address();
    if (address && typeof address === "object") {
      baseUrl = `http://127.0.0.1:${address.port}`;
    }
  });

  afterAll(async () => {
    await app.close();
    rmSync(tmpDir, { recursive: true, force: true });
  });

  it("GET homepage returns 200", async () => {
    const resp = await fetch(baseUrl);
    expect(resp.status).toBe(200);
    const body = await resp.text();
    expect(body.length).toBeGreaterThan(0);
  });

  it("GET /health returns 200 with status ok", async () => {
    const resp = await fetch(`${baseUrl}/health`);
    expect(resp.status).toBe(200);
    const body = await resp.json();
    expect(body).toEqual({ status: "ok" });
  });

  it("POST then GET boost lifecycle", async () => {
    const payload = minimalBoostPayload();

    // POST
    const postResp = await fetch(`${baseUrl}/boost`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "x-api-key": "test-key",
      },
      body: JSON.stringify(payload),
    });
    expect(postResp.status).toBe(201);

    const postBody = (await postResp.json()) as { id: string; url: string; desc: string };
    expect(typeof postBody.id).toBe("string");
    expect(postBody.url).toContain(`/boost/${postBody.id}`);

    // GET - use baseUrl (actual test server) not postBody.url (config-based)
    const getResp = await fetch(`${baseUrl}/boost/${postBody.id}`);
    expect(getResp.status).toBe(200);

    const rssPayment = getResp.headers.get("x-rss-payment");
    expect(rssPayment).toBeTruthy();

    const decoded = JSON.parse(decodeURIComponent(rssPayment!));
    expect(decoded.id).toBe(postBody.id);
    expect(decoded.action).toBe("boost");
    expect(decoded.message).toBe("test boost!");
  });

  it("Oscar's Fountain boost - extra fields, nulls, case handling", async () => {
    const oscarBoost = {
      action: "BOOST", // uppercase to test normalization
      split: 0.05,
      message: "Test Boost 2",
      link: "https://fountain.fm/episode/test",
      app_name: "Fountain",
      sender_id: "hIWsCYxdBJzlDvu5zpT3",
      sender_name: "merryoscar@fountain.fm",
      sender_npub: "npub1unmftuzmkpdjxyj4en8r63cm34uuvjn9hnxqz3nz6fls7l5jzzfqtvd0j2",
      recipient_address: "ericpp@getalby.com",
      value_msat: 50000,
      value_usd: 0.049998,
      value_msat_total: 1000000,
      timestamp: "2025-11-07T14:36:23.861Z",
      position: 5192,
      feed_guid: "917393e3-1b1e-5cef-ace4-edaa54e1f810",
      feed_title: "Podcasting 2.0",
      item_guid: "PC20-240",
      item_title: "Episode 240: Open Source = People!",
      publisher_guid: null,
      publisher_title: null,
      remote_feed_guid: null,
      remote_item_guid: null,
      remote_publisher_guid: null,
    };

    const postResp = await fetch(`${baseUrl}/boost`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "x-api-key": "test-key",
      },
      body: JSON.stringify(oscarBoost),
    });
    expect(postResp.status).toBe(201);

    const postBody = (await postResp.json()) as { id: string; url: string };

    const getResp = await fetch(`${baseUrl}/boost/${postBody.id}`);
    expect(getResp.status).toBe(200);

    const rssPayment = getResp.headers.get("x-rss-payment");
    const decoded = JSON.parse(decodeURIComponent(rssPayment!));

    // Verify normalization
    expect(decoded.action).toBe("boost");

    // Verify extra fields pass through
    expect(decoded.link).toBe("https://fountain.fm/episode/test");
    expect(decoded.sender_npub).toBe(
      "npub1unmftuzmkpdjxyj4en8r63cm34uuvjn9hnxqz3nz6fls7l5jzzfqtvd0j2"
    );

    // Verify null handling
    expect(decoded.publisher_guid).toBeNull();
    expect(decoded.remote_feed_guid).toBeNull();

    // Verify HTML renders - need a separate GET since body was consumed
    const htmlResp = await fetch(`${baseUrl}/boost/${postBody.id}`);
    const html = await htmlResp.text();
    expect(html).toContain("Boost Details");
    expect(html).toContain("Test Boost 2");
  });

  it("HEAD /boost/:id returns x-rss-payment header", async () => {
    const payload = minimalBoostPayload();
    const postResp = await fetch(`${baseUrl}/boost`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "x-api-key": "test-key",
      },
      body: JSON.stringify(payload),
    });
    const postBody = (await postResp.json()) as { id: string; url: string };

    const headResp = await fetch(`${baseUrl}/boost/${postBody.id}`, { method: "HEAD" });
    expect(headResp.status).toBe(200);
    expect(headResp.headers.get("x-rss-payment")).toBeTruthy();
  });

  it("413 payload too large", async () => {
    const payload = {
      ...minimalBoostPayload(),
      message: "A".repeat(1100),
    };
    const resp = await fetch(`${baseUrl}/boost`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "x-api-key": "test-key",
      },
      body: JSON.stringify(payload),
    });
    expect(resp.status).toBe(413);
    const body = (await resp.json()) as { error: string };
    expect(body.error).toBe("payload too large");
  });

  it("401 unauthorized without api key", async () => {
    const resp = await fetch(`${baseUrl}/boost`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(minimalBoostPayload()),
    });
    expect(resp.status).toBe(401);
    const body = (await resp.json()) as { error: string };
    expect(body.error).toBe("unauthorized");
  });

  it("404 not found for non-existent boost", async () => {
    const fakeId = genUlid();
    const resp = await fetch(`${baseUrl}/boost/${fakeId}`);
    expect(resp.status).toBe(404);
    const body = (await resp.json()) as { error: string; id: string };
    expect(body).toEqual({ error: "unknown boost", id: fakeId });
  });

  it("400 for invalid ULID", async () => {
    for (const badId of ["abc123", "01K9TJFCFENBC87GR7M7CFA8P1"]) {
      const resp = await fetch(`${baseUrl}/boost/${badId}`);
      expect(resp.status).toBe(400);
    }
  });
});
