import { MAX_PURGE_BYTES, MAX_REPORT_BYTES } from "./constants";
import { InputError } from "./validation";

const BASE_HEADERS = {
  "cache-control": "no-store",
  "x-content-type-options": "nosniff",
} as const;

export function emptyResponse(status: number, extraHeaders?: HeadersInit): Response {
  return new Response(null, { status, headers: { ...BASE_HEADERS, ...extraHeaders } });
}

export function errorResponse(status: number, code: string, extraHeaders?: HeadersInit): Response {
  return Response.json(
    { error: code },
    {
      status,
      headers: { ...BASE_HEADERS, ...extraHeaders },
    },
  );
}

export function healthResponse(): Response {
  return Response.json({ status: "ok" }, { headers: BASE_HEADERS });
}

export function methodNotAllowed(allow: string): Response {
  return errorResponse(405, "METHOD_NOT_ALLOWED", { allow });
}

function requireJsonContentType(request: Request): void {
  const raw = request.headers.get("content-type") ?? "";
  const mediaType = raw.split(";", 1)[0]?.trim().toLowerCase();
  if (mediaType !== "application/json") throw new InputError("UNSUPPORTED_MEDIA_TYPE", 415);

  const encoding = (request.headers.get("content-encoding") ?? "identity").trim().toLowerCase();
  if (encoding !== "" && encoding !== "identity") {
    throw new InputError("UNSUPPORTED_CONTENT_ENCODING", 415);
  }
}

async function readUtf8Body(request: Request, maxBytes: number): Promise<string> {
  const declaredLength = request.headers.get("content-length");
  if (declaredLength !== null) {
    const parsed = Number(declaredLength);
    if (Number.isFinite(parsed) && parsed > maxBytes) throw new InputError("PAYLOAD_TOO_LARGE", 413);
  }
  if (request.body === null) throw new InputError("EMPTY_BODY");

  const reader = request.body.getReader();
  const decoder = new TextDecoder("utf-8", { fatal: true });
  let timeout: ReturnType<typeof setTimeout> | undefined;
  const deadline = new Promise<never>((_, reject) => {
    timeout = setTimeout(() => {
      reject(new InputError("BODY_TIMEOUT", 408));
      void reader.cancel("body timeout").catch(() => {});
    }, 5000);
  });
  let total = 0;
  let text = "";

  try {
    while (true) {
      const next = await Promise.race([reader.read(), deadline]);
      if (next.done) break;
      total += next.value.byteLength;
      if (total > maxBytes) {
        void reader.cancel("payload too large").catch(() => {});
        throw new InputError("PAYLOAD_TOO_LARGE", 413);
      }
      text += decoder.decode(next.value, { stream: true });
    }
    text += decoder.decode();
  } catch (error) {
    if (error instanceof InputError) throw error;
    throw new InputError("INVALID_UTF8");
  } finally {
    clearTimeout(timeout);
    void reader.cancel().catch(() => {});
  }

  if (text.length === 0) throw new InputError("EMPTY_BODY");
  return text;
}

async function readJson(request: Request, maxBytes: number): Promise<unknown> {
  requireJsonContentType(request);
  const text = await readUtf8Body(request, maxBytes);
  try {
    return JSON.parse(text) as unknown;
  } catch {
    throw new InputError("INVALID_JSON");
  }
}

export function readReportJson(request: Request): Promise<unknown> {
  return readJson(request, MAX_REPORT_BYTES);
}

export function readPurgeJson(request: Request): Promise<unknown> {
  return readJson(request, MAX_PURGE_BYTES);
}
