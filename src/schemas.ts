import { z } from "zod";

function isValidIso8601(s: string): boolean {
  const d = new Date(s);
  return !isNaN(d.getTime());
}

export const BoostMetadata = z
  .object({
    action: z
      .string()
      .transform((s) => s.toLowerCase())
      .pipe(z.enum(["boost", "stream"])),
    split: z.number().min(0),
    value_msat: z.number().int().min(1),
    value_msat_total: z.number().int().min(1),
    timestamp: z.string().refine(isValidIso8601, { message: "must be ISO-8601" }),
    // optional fields
    group: z.string().nullish(),
    message: z.string().nullish(),
    app_name: z.string().nullish(),
    app_version: z.string().nullish(),
    sender_id: z.string().nullish(),
    sender_name: z.string().nullish(),
    recipient_name: z.string().nullish(),
    recipient_address: z.string().nullish(),
    value_usd: z.number().min(0).nullish(),
    position: z.number().int().nullish(),
    feed_guid: z.string().nullish(),
    feed_title: z.string().nullish(),
    item_guid: z.string().nullish(),
    item_title: z.string().nullish(),
    publisher_guid: z.string().nullish(),
    publisher_title: z.string().nullish(),
    remote_feed_guid: z.string().nullish(),
    remote_item_guid: z.string().nullish(),
    remote_publisher_guid: z.string().nullish(),
  })
  .passthrough(); // allow extra fields to pass through

export type BoostMetadataInput = z.input<typeof BoostMetadata>;
