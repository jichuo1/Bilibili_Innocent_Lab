const encoder = new TextEncoder();

function hex(bytes: ArrayBuffer): string {
  return Array.from(new Uint8Array(bytes), (value) => value.toString(16).padStart(2, "0")).join("");
}

export async function hmacSha256Hex(secret: string, value: string): Promise<string> {
  if (encoder.encode(secret).byteLength < 32) throw new Error("HMAC_KEY_TOO_SHORT");
  const key = await crypto.subtle.importKey(
    "raw",
    encoder.encode(secret),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"],
  );
  return hex(await crypto.subtle.sign("HMAC", key, encoder.encode(value)));
}

export async function sha256Hex(value: string): Promise<string> {
  return hex(await crypto.subtle.digest("SHA-256", encoder.encode(value)));
}
