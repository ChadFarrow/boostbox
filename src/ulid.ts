// Crockford's Base32 ULID encoding/decoding
// Ported from clj-ulid (MIT License)

import { v7 as uuidv7, parse as uuidParse, version as uuidVersion } from "uuid";

const BASE32 = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";

const BASE32_VALUE: Record<string, number> = {};
for (let i = 0; i < BASE32.length; i++) {
  BASE32_VALUE[BASE32[i]] = i;
}

export function encode(n: bigint, length: number): string {
  let result = "";
  let val = n < 0n ? 0n : n;
  for (let i = 0; i < length; i++) {
    const m = val % 32n;
    result = BASE32[Number(m)] + result;
    val = (val - m) / 32n;
  }
  return result;
}

export function decode(s: string): bigint {
  let acc = 0n;
  for (const c of s) {
    const v = BASE32_VALUE[c];
    if (v === undefined) throw new Error(`Invalid Base32 character: ${c}`);
    acc = (acc << 5n) | BigInt(v);
  }
  return acc;
}

export function genUlid(): string {
  const id = uuidv7();
  const bytes = uuidParse(id);
  let n = 0n;
  for (const b of bytes) {
    n = (n << 8n) | BigInt(b);
  }
  return encode(n, 26);
}

export function ulidToUuidBytes(ulid: string): Uint8Array {
  const n = decode(ulid);
  const bytes = new Uint8Array(16);
  let val = n;
  for (let i = 15; i >= 0; i--) {
    bytes[i] = Number(val & 0xffn);
    val >>= 8n;
  }
  return bytes;
}

export function validUlid(ulid: string): boolean {
  try {
    if (ulid.length !== 26) return false;
    const bytes = ulidToUuidBytes(ulid);
    // Check UUIDv7: version nibble is in byte 6, high nibble
    const version = (bytes[6] >> 4) & 0x0f;
    return version === 7;
  } catch {
    return false;
  }
}

export function ulidToTimestamp(ulid: string): number {
  const timeChars = ulid.substring(0, 10).toUpperCase();
  let result = 0n;
  for (const c of timeChars) {
    const v = BASE32_VALUE[c];
    if (v === undefined) throw new Error(`Invalid Base32 character: ${c}`);
    result = (result << 5n) | BigInt(v);
  }
  return Number(result);
}
