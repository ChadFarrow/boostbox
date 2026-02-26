import { readFileSync } from "node:fs";
import { resolve, dirname } from "node:path";
import { fileURLToPath } from "node:url";

const __dirname = dirname(fileURLToPath(import.meta.url));
const resourcesDir = resolve(__dirname, "..", "resources");

export const v4vbox = readFileSync(resolve(resourcesDir, "v4vbox.b64"), "utf-8").trim();
export const favicon = readFileSync(resolve(resourcesDir, "favicon.b64"), "utf-8").trim();
