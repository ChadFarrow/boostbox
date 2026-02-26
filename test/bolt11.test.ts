import { describe, it, expect } from "vitest";
import { bolt11Desc } from "../src/bolt11.js";

describe("bolt11Desc", () => {
  it("basic format", () => {
    expect(bolt11Desc("boost", "https://example.com", "msg")).toMatch(
      /^rss::payment::/
    );
  });

  it("empty or null message", () => {
    expect(bolt11Desc("buy", "https://example.com", "")).toBe(
      "rss::payment::buy https://example.com"
    );
    expect(bolt11Desc("buy", "https://example.com", null)).toBe(
      "rss::payment::buy https://example.com"
    );
  });

  it("short message passes through", () => {
    expect(bolt11Desc("buy", "https://example.com", "hello world")).toBe(
      "rss::payment::buy https://example.com hello world"
    );
  });

  it("never exceeds 639 characters with realistic inputs", () => {
    const action = "stream";
    const url = "https://very-long-domain.example.com/path/to/item";
    const hugeMsg = "word ".repeat(1000);
    expect(bolt11Desc(action, url, hugeMsg).length).toBeLessThanOrEqual(639);
  });

  it("no trailing whitespace", () => {
    expect(bolt11Desc("a", "b", "msg")).not.toMatch(/\s$/);
    expect(bolt11Desc("a", "b", "")).not.toMatch(/\s$/);
    expect(bolt11Desc("a", "b", null)).not.toMatch(/\s$/);
  });

  it("truncated messages end with ellipsis", () => {
    const action = "buy";
    const url = "https://example.com";
    const maxDescLen = 623 - action.length - url.length;
    const longMsg = "x".repeat(maxDescLen + 10);
    expect(bolt11Desc(action, url, longMsg)).toMatch(/\.\.\.$/);
  });
});
