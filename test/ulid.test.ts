import { describe, it, expect } from "vitest";
import { encode, decode, genUlid, validUlid, ulidToTimestamp } from "../src/ulid.js";

describe("ULID", () => {
  it("encodes and decodes round-trip", () => {
    const n = 123456789012345678901234567890n;
    const encoded = encode(n, 26);
    const decoded = decode(encoded);
    expect(decoded).toBe(n);
  });

  it("generates valid ULIDs", () => {
    const ulid = genUlid();
    expect(ulid).toHaveLength(26);
    expect(validUlid(ulid)).toBe(true);
  });

  it("rejects invalid ULIDs", () => {
    expect(validUlid("abc123")).toBe(false);
    expect(validUlid("")).toBe(false);
    // Valid ULID format but not UUIDv7 based
    expect(validUlid("01K9TJFCFENBC87GR7M7CFA8P1")).toBe(false);
  });

  it("extracts timestamp from ULID", () => {
    const ulid = genUlid();
    const ts = ulidToTimestamp(ulid);
    const now = Date.now();
    // Timestamp should be within a few seconds of now
    expect(Math.abs(ts - now)).toBeLessThan(5000);
  });
});
