import { mkdirSync, writeFileSync, readFileSync, readdirSync, statSync } from "node:fs";
import { join, dirname } from "node:path";
import {
  S3Client,
  PutObjectCommand,
  GetObjectCommand,
  ListObjectsV2Command,
} from "@aws-sdk/client-s3";
import { ulidToTimestamp } from "./ulid.js";

export interface BoostData {
  id: string;
  [key: string]: unknown;
}

function timestampToPrefix(unixMs: number): string {
  const d = new Date(unixMs);
  const year = d.getUTCFullYear();
  const month = String(d.getUTCMonth() + 1).padStart(2, "0");
  const day = String(d.getUTCDate()).padStart(2, "0");
  return `${year}/${month}/${day}`;
}

function idToStorageKey(id: string): string {
  const timestamp = ulidToTimestamp(id);
  const prefix = timestampToPrefix(timestamp);
  return `${prefix}/${id}.json`;
}

export interface IStorage {
  store(id: string, data: BoostData): Promise<void>;
  retrieve(id: string): Promise<BoostData>;
  listAll(): Promise<BoostData[]>;
}

export class LocalStorage implements IStorage {
  constructor(private rootPath: string) {}

  async store(id: string, data: BoostData): Promise<void> {
    const filePath = join(this.rootPath, idToStorageKey(id));
    mkdirSync(dirname(filePath), { recursive: true });
    writeFileSync(filePath, JSON.stringify(data));
  }

  async retrieve(id: string): Promise<BoostData> {
    const filePath = join(this.rootPath, idToStorageKey(id));
    try {
      const content = readFileSync(filePath, "utf-8");
      return JSON.parse(content) as BoostData;
    } catch (err: unknown) {
      if (err instanceof Error && "code" in err && (err as NodeJS.ErrnoException).code === "ENOENT") {
        const e = new Error("Not found");
        (e as Error & { notFound: boolean }).notFound = true;
        throw e;
      }
      throw err;
    }
  }

  async listAll(): Promise<BoostData[]> {
    const results: BoostData[] = [];
    const walk = (dir: string) => {
      try {
        const entries = readdirSync(dir);
        for (const entry of entries) {
          const full = join(dir, entry);
          const stat = statSync(full);
          if (stat.isDirectory()) {
            walk(full);
          } else if (entry.endsWith(".json")) {
            try {
              const content = readFileSync(full, "utf-8");
              results.push(JSON.parse(content) as BoostData);
            } catch {
              // skip malformed files
            }
          }
        }
      } catch {
        // directory doesn't exist yet
      }
    };
    walk(this.rootPath);
    results.sort((a, b) => (b.id as string).localeCompare(a.id as string));
    return results;
  }
}

async function streamToString(stream: NodeJS.ReadableStream): Promise<string> {
  const chunks: Buffer[] = [];
  for await (const chunk of stream) {
    chunks.push(typeof chunk === "string" ? Buffer.from(chunk) : chunk);
  }
  return Buffer.concat(chunks).toString("utf-8");
}

export class S3Storage implements IStorage {
  private client: S3Client;
  private bucket: string;

  constructor(config: {
    endpoint: string;
    region: string;
    accessKey: string;
    secretKey: string;
    bucket: string;
  }) {
    this.bucket = config.bucket;
    this.client = new S3Client({
      endpoint: config.endpoint,
      region: config.region,
      credentials: {
        accessKeyId: config.accessKey,
        secretAccessKey: config.secretKey,
      },
      forcePathStyle: true,
    });
  }

  async store(id: string, data: BoostData): Promise<void> {
    await this.client.send(
      new PutObjectCommand({
        Bucket: this.bucket,
        Key: idToStorageKey(id),
        Body: JSON.stringify(data),
        ContentType: "application/json",
      })
    );
  }

  async retrieve(id: string): Promise<BoostData> {
    try {
      const response = await this.client.send(
        new GetObjectCommand({
          Bucket: this.bucket,
          Key: idToStorageKey(id),
        })
      );
      const body = await streamToString(response.Body as NodeJS.ReadableStream);
      return JSON.parse(body) as BoostData;
    } catch (err: unknown) {
      if (err instanceof Error && err.name === "NoSuchKey") {
        const e = new Error("Not found");
        (e as Error & { notFound: boolean }).notFound = true;
        throw e;
      }
      throw err;
    }
  }

  async listAll(): Promise<BoostData[]> {
    const response = await this.client.send(
      new ListObjectsV2Command({ Bucket: this.bucket })
    );
    const keys = (response.Contents ?? [])
      .map((obj) => obj.Key)
      .filter((k): k is string => !!k && k.endsWith(".json"));

    const results: BoostData[] = [];
    for (const key of keys) {
      try {
        const getResponse = await this.client.send(
          new GetObjectCommand({ Bucket: this.bucket, Key: key })
        );
        const body = await streamToString(getResponse.Body as NodeJS.ReadableStream);
        results.push(JSON.parse(body) as BoostData);
      } catch {
        // skip failures
      }
    }
    results.sort((a, b) => (b.id as string).localeCompare(a.id as string));
    return results;
  }
}

export function makeStorage(config: {
  storage: string;
  rootPath?: string;
  s3Endpoint?: string;
  s3Region?: string;
  s3AccessKey?: string;
  s3SecretKey?: string;
  s3Bucket?: string;
}): IStorage {
  if (config.storage === "S3") {
    return new S3Storage({
      endpoint: config.s3Endpoint!,
      region: config.s3Region!,
      accessKey: config.s3AccessKey!,
      secretKey: config.s3SecretKey!,
      bucket: config.s3Bucket!,
    });
  }
  return new LocalStorage(config.rootPath ?? "boosts");
}
