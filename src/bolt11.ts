export function bolt11Desc(action: string, url: string, message?: string | null): string {
  if (!message || message.length === 0) {
    return `rss::payment::${action} ${url}`;
  }

  const actionLen = action.length;
  const urlLen = url.length;
  // BOLT11 max is 639, "rss::payment::" + two spaces = 16; 639 - 16 = 623
  const maxDescLen = Math.max(0, 623 - urlLen - actionLen);

  let truncated = message.substring(0, maxDescLen);

  if (truncated.length <= 2) {
    return `rss::payment::${action} ${url}`;
  }

  if (truncated.length < message.length) {
    truncated = truncated.substring(0, truncated.length - 3) + "...";
  }

  if (truncated.length === 0) {
    return `rss::payment::${action} ${url}`;
  }

  return `rss::payment::${action} ${url} ${truncated}`;
}
