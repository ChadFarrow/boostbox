import Fastify from "fastify";
import cors from "@fastify/cors";
import swagger from "@fastify/swagger";
import swaggerUi from "@fastify/swagger-ui";
import { genUlid, validUlid } from "./ulid.js";
import { makeStorage, type IStorage } from "./storage.js";
import { BoostMetadata } from "./schemas.js";
import { bolt11Desc } from "./bolt11.js";
import { renderHomepage, renderBoostView } from "./html.js";

// ~~~~~~~~~~~~~~~~~~~ Config ~~~~~~~~~~~~~~~~~~~

function getEnv(key: string, defaultVal?: string): string {
  const val = process.env[key];
  if (val !== undefined && val !== "") return val;
  if (defaultVal !== undefined) return defaultVal;
  throw new Error(`Missing ENV VAR: ${key}`);
}

interface AppConfig {
  env: string;
  storage: string;
  port: number;
  baseUrl: string;
  allowedKeys: Set<string>;
  maxBodySize: number;
  rootPath: string;
  s3Endpoint?: string;
  s3Region?: string;
  s3AccessKey?: string;
  s3SecretKey?: string;
  s3Bucket?: string;
}

function loadConfig(): AppConfig {
  const env = getEnv("ENV", "PROD");
  if (!["DEV", "STAGING", "PROD"].includes(env)) {
    throw new Error(`Invalid ENV: ${env}`);
  }
  const storage = getEnv("BB_STORAGE", "FS");
  if (!["FS", "S3"].includes(storage)) {
    throw new Error(`Invalid BB_STORAGE: ${storage}`);
  }
  const port = parseInt(getEnv("BB_PORT", "8080"), 10);
  const baseUrl = getEnv("BB_BASE_URL", `http://localhost:${port}`);
  const allowedKeys = new Set(
    getEnv("BB_ALLOWED_KEYS", "v4v4me")
      .split(",")
      .map((k) => k.trim())
      .filter(Boolean)
  );
  if (allowedKeys.size === 0) {
    throw new Error("must specify at least one key in BB_ALLOWED_KEYS");
  }
  const maxBodySize = parseInt(getEnv("BB_MAX_BODY", "102400"), 10);

  const config: AppConfig = {
    env,
    storage,
    port,
    baseUrl,
    allowedKeys,
    maxBodySize,
    rootPath: getEnv("BB_FS_ROOT_PATH", "boosts"),
  };

  if (storage === "S3") {
    config.s3Endpoint = getEnv("BB_S3_ENDPOINT");
    config.s3Region = getEnv("BB_S3_REGION");
    config.s3AccessKey = getEnv("BB_S3_ACCESS_KEY");
    config.s3SecretKey = getEnv("BB_S3_SECRET_KEY");
    config.s3Bucket = getEnv("BB_S3_BUCKET");
  }

  return config;
}

// ~~~~~~~~~~~~~~~~~~~ Server ~~~~~~~~~~~~~~~~~~~

export async function buildServer(config: AppConfig, storageOverride?: IStorage) {
  const storage = storageOverride ?? makeStorage(config);

  const app = Fastify({
    logger: {
      level: config.env === "DEV" ? "debug" : "info",
    },
    bodyLimit: config.maxBodySize,
    genReqId: () => genUlid(),
  });

  // CORS
  await app.register(cors, {
    origin: "*",
    methods: ["GET", "HEAD", "POST", "OPTIONS"],
    allowedHeaders: ["Content-Type", "X-API-Key"],
    exposedHeaders: ["x-rss-payment", "x-correlation-id"],
  });

  // Swagger
  await app.register(swagger, {
    openapi: {
      info: {
        title: "BoostBox API",
        description: "simple API to store boost metadata",
        version: "0.1.0",
      },
      tags: [
        { name: "boosts", description: "boost api" },
        { name: "admin", description: "admin api" },
      ],
      components: {
        securitySchemes: {
          auth: {
            type: "apiKey",
            in: "header",
            name: "x-api-key",
          },
        },
      },
    },
  });

  await app.register(swaggerUi, {
    routePrefix: "/docs",
    uiConfig: {
      urls: [{ name: "openapi", url: "/docs/json" }],
    },
  });

  // Correlation ID
  app.addHook("onRequest", async (request, reply) => {
    const existing = request.headers["x-correlation-id"];
    const correlationId =
      typeof existing === "string" ? existing : genUlid();
    (request as unknown as Record<string, string>).correlationId = correlationId;
    reply.header("x-correlation-id", correlationId);
  });

  // Auth helper
  function checkAuth(request: { headers: Record<string, string | string[] | undefined> }): boolean {
    const apiKey = request.headers["x-api-key"];
    if (typeof apiKey === "string" && config.allowedKeys.has(apiKey)) return true;
    return false;
  }

  // ~~~~~~~~~~~~~~~~~~~ Routes ~~~~~~~~~~~~~~~~~~~

  // Homepage
  app.get("/", { schema: { hide: true } }, async (_request, reply) => {
    try {
      const boosts = await storage.listAll();
      reply.type("text/html; charset=utf-8").send(renderHomepage(boosts));
    } catch {
      reply.type("text/html; charset=utf-8").send(renderHomepage([]));
    }
  });

  // Health
  app.get(
    "/health",
    {
      schema: {
        tags: ["admin"],
        description: "healthcheck",
        response: { 200: { type: "object", properties: { status: { type: "string" } } } },
      },
    },
    async () => ({ status: "ok" })
  );

  // List all boosts
  app.get(
    "/boosts",
    {
      schema: {
        tags: ["boosts"],
        description: "List all boosts",
        security: [{ auth: [] }],
      },
    },
    async (request, reply) => {
      if (!checkAuth(request)) {
        return reply.status(401).send({ error: "unauthorized" });
      }
      const boosts = await storage.listAll();
      return boosts;
    }
  );

  // POST boost
  app.post(
    "/boost",
    {
      schema: {
        tags: ["boosts"],
        description: "Store boost metadata",
        security: [{ auth: [] }],
      },
    },
    async (request, reply) => {
      if (!checkAuth(request)) {
        return reply.status(401).send({ error: "unauthorized" });
      }

      const parsed = BoostMetadata.safeParse(request.body);
      if (!parsed.success) {
        return reply.status(400).send({
          error: "validation failed",
          details: parsed.error.flatten(),
        });
      }

      const id = genUlid();
      const url = `${config.baseUrl}/boost/${id}`;
      const boost = { ...parsed.data, id };
      const desc = bolt11Desc(
        boost.action,
        url,
        boost.message
      );

      try {
        await storage.store(id, boost);
        return reply.status(201).send({ id, url, desc });
      } catch (err) {
        request.log.error(err, "Error storing boost");
        return reply.status(500).send({ error: "error during boost storage" });
      }
    }
  );

  // GET boost by ID
  app.get(
    "/boost/:id",
    {
      schema: {
        tags: ["boosts"],
        description: "lookup boost by id",
        params: {
          type: "object",
          properties: { id: { type: "string" } },
          required: ["id"],
        },
      },
    },
    async (request, reply) => {
      const { id } = request.params as { id: string };

      if (!validUlid(id)) {
        return reply.status(400).send({
          error: "must be valid ULID",
          id,
        });
      }

      try {
        const data = await storage.retrieve(id);
        const encodedHeader = encodeURIComponent(JSON.stringify(data));

        reply
          .header("access-control-expose-headers", "x-rss-payment")
          .header("x-rss-payment", encodedHeader)
          .type("text/html; charset=utf-8")
          .send(renderBoostView(data));
      } catch (err: unknown) {
        if (err instanceof Error && (err as Error & { notFound?: boolean }).notFound) {
          return reply.status(404).send({ error: "unknown boost", id });
        }
        throw err;
      }
    }
  );

  // Fastify auto-creates HEAD handlers for GET routes, so HEAD /boost/:id
  // works automatically - it sends the same headers but strips the body.

  // Custom error handler
  app.setErrorHandler(async (error: { statusCode?: number; validation?: unknown; message?: string }, _request, reply) => {
    if (error.statusCode === 413) {
      return reply.status(413).send({ error: "payload too large" });
    }
    if (error.validation) {
      return reply.status(400).send({ error: "validation failed", details: error.message });
    }
    app.log.error(error);
    return reply.status(500).send({ error: "internal server error" });
  });

  return app;
}

// ~~~~~~~~~~~~~~~~~~~ Main ~~~~~~~~~~~~~~~~~~~

async function main() {
  const config = loadConfig();
  const app = await buildServer(config);

  try {
    await app.listen({ port: config.port, host: "0.0.0.0" });
    app.log.info(`BoostBox starting up on port ${config.port}`);
  } catch (err) {
    app.log.error(err);
    process.exit(1);
  }

  const shutdown = async () => {
    app.log.info("BoostBox shutting down");
    await app.close();
    process.exit(0);
  };

  process.on("SIGINT", shutdown);
  process.on("SIGTERM", shutdown);
}

main();

export { loadConfig, type AppConfig };
