import { readFileSync } from "node:fs";
import { createRequire } from "node:module";
import { dirname, join } from "node:path";
import { constants as zlibConstants, inflateRawSync } from "node:zlib";
import { createPublicKey, publicEncrypt, constants as cryptoConstants } from "node:crypto";
import type { APIRequestContext, APIResponse } from "@playwright/test";
import { expect } from "@playwright/test";
import {
  DEFAULT_GUEST_USER_ID,
  type DbObject,
  type GenerateSecretKeyResponse,
  type GenerateTokenResponse,
  type SchemaCatalog,
  type SqlflowCatalog,
  type UserJobSummary,
  type VersionEnvelope
} from "@sqlflow/ui-core/types";

export interface ConfigPrivateJson {
  Product?: string;
  ApiPrefix?: string;
  Authorization?: {
    base64token?: string;
  };
  userId?: string;
  trialVersion?: boolean;
  homepage?: "editor" | "job";
}

/**
 * Loads `CLEAN_ROOM_DEFAULT_RSA_1024_PUBLIC_KEY` from `@sqlflow/ui-core/api` (`ui-core/src/api/rsa.ts`)
 * without importing `jsencrypt`'s extensionless ESM bundle under Node's native ESM resolver.
 */
function loadCleanRoomDefaultRsaPublicKey(): string {
  const req = createRequire(import.meta.url);
  const apiIndexPath = req.resolve("@sqlflow/ui-core/api");
  const rsaModuleSource = readFileSync(join(dirname(apiIndexPath), "rsa.js"), "utf8");
  const match = rsaModuleSource.match(
    /CLEAN_ROOM_DEFAULT_RSA_1024_PUBLIC_KEY\s*=\s*["']([^"']+)["']/
  );
  if (!match?.[1]) {
    throw new Error(
      "Failed to locate CLEAN_ROOM_DEFAULT_RSA_1024_PUBLIC_KEY in @sqlflow/ui-core/api"
    );
  }
  return match[1];
}

export const CLEAN_ROOM_DEFAULT_RSA_1024_PUBLIC_KEY: string = loadCleanRoomDefaultRsaPublicKey();

export interface BootstrappedAuthSession {
  guestUserId: string;
  guestToken: string;
  userId: string;
  userEmail: string;
  loginToken: string;
  secretKey: string;
  jwtToken: string;
  version: VersionEnvelope["version"];
}

export interface MultipartFileUpload {
  name: string;
  mimeType?: string;
  buffer: Buffer;
}

export interface SseFrame {
  event?: string;
  data: string;
  parsed?: Record<string, unknown>;
}

/**
 * Encrypts a plaintext DataSource password using the clean-room 1024-bit RSA SPKI public key
 * with RFC 8017 PKCS#1 v1.5 padding (`RSA/ECB/PKCS1Padding` Base64 output, matching `encryptRsaPassword`
 * in `@sqlflow/ui-core/api` and `CryptoService` in `backend-service`, `00 §3.3 #7`).
 */
export function encryptRsaPassword(
  plainPassword: string,
  publicKeyOverride: string = CLEAN_ROOM_DEFAULT_RSA_1024_PUBLIC_KEY
): string {
  if (!plainPassword) {
    return "";
  }
  const trimmed = publicKeyOverride.trim();
  const pem = trimmed.includes("BEGIN PUBLIC KEY")
    ? trimmed
    : `-----BEGIN PUBLIC KEY-----\n${trimmed}\n-----END PUBLIC KEY-----`;
  const keyObj = createPublicKey(pem);
  const encrypted = publicEncrypt(
    {
      key: keyObj,
      padding: cryptoConstants.RSA_PKCS1_PADDING
    },
    Buffer.from(plainPassword, "utf8")
  );
  return encrypted.toString("base64");
}

export class SqlFlowApiClient {
  public readonly request: APIRequestContext;
  public readonly baseUrl: string;

  public constructor(
    request: APIRequestContext,
    baseUrl: string = process.env.SQLFLOW_BASE_URL || "http://localhost:8165"
  ) {
    this.request = request;
    this.baseUrl = baseUrl.replace(/\/+$/, "");
  }

  public url(path: string): string {
    if (path.startsWith("http://") || path.startsWith("https://")) {
      return path;
    }
    return `${this.baseUrl}${path.startsWith("/") ? path : `/${path}`}`;
  }

  public async postForm<T = Record<string, unknown>>(
    path: string,
    form: Record<string, string | number | boolean | undefined | null>,
    headers: Record<string, string> = {}
  ): Promise<{ response: APIResponse; status: number; body: T; rawText: string }> {
    const cleanedForm: Record<string, string> = {};
    for (const [k, v] of Object.entries(form)) {
      if (v !== undefined && v !== null) {
        cleanedForm[k] = String(v);
      }
    }
    const response = await this.request.post(this.url(path), {
      form: cleanedForm,
      headers
    });
    const rawText = await response.text();
    let body: T;
    try {
      body = JSON.parse(rawText) as T;
    } catch {
      body = rawText as unknown as T;
    }
    return { response, status: response.status(), body, rawText };
  }

  public async postJson<T = Record<string, unknown>>(
    path: string,
    payload?: unknown,
    headers: Record<string, string> = {}
  ): Promise<{ response: APIResponse; status: number; body: T; rawText: string }> {
    const response = await this.request.post(this.url(path), {
      data: payload ?? {},
      headers: {
        "Content-Type": "application/json",
        ...headers
      }
    });
    const rawText = await response.text();
    let body: T;
    try {
      body = JSON.parse(rawText) as T;
    } catch {
      body = rawText as unknown as T;
    }
    return { response, status: response.status(), body, rawText };
  }

  public async getJson<T = Record<string, unknown>>(
    path: string,
    params?: Record<string, string | number | boolean | undefined>,
    headers: Record<string, string> = {}
  ): Promise<{ response: APIResponse; status: number; body: T; rawText: string }> {
    const cleanedParams: Record<string, string> = {};
    if (params) {
      for (const [k, v] of Object.entries(params)) {
        if (v !== undefined) {
          cleanedParams[k] = String(v);
        }
      }
    }
    const response = await this.request.get(this.url(path), {
      params: cleanedParams,
      headers
    });
    const rawText = await response.text();
    let body: T;
    try {
      body = JSON.parse(rawText) as T;
    } catch {
      body = rawText as unknown as T;
    }
    return { response, status: response.status(), body, rawText };
  }

  /**
   * Builds an RFC 7578 multipart/form-data payload supporting multiple `sqlfiles` parts
   * under the exact same field name (`@RequestParam("sqlfiles") MultipartFile[]`).
   */
  public async postMultipart<T = Record<string, unknown>>(
    path: string,
    fields: Record<string, string | number | boolean | undefined | null>,
    filesField: string,
    files: MultipartFileUpload[],
    headers: Record<string, string> = {}
  ): Promise<{ response: APIResponse; status: number; body: T; rawText: string }> {
    const boundary = `----SqlFlowE2EBoundary${Date.now().toString(16)}${Math.random().toString(16).slice(2)}`;
    const chunks: Buffer[] = [];
    const crlf = "\r\n";

    for (const [key, val] of Object.entries(fields)) {
      if (val === undefined || val === null) {
        continue;
      }
      chunks.push(
        Buffer.from(
          `--${boundary}${crlf}Content-Disposition: form-data; name="${key}"${crlf}${crlf}${String(val)}${crlf}`,
          "utf8"
        )
      );
    }

    for (const file of files) {
      const mime = file.mimeType || "application/sql";
      chunks.push(
        Buffer.from(
          `--${boundary}${crlf}Content-Disposition: form-data; name="${filesField}"; filename="${file.name}"${crlf}Content-Type: ${mime}${crlf}${crlf}`,
          "utf8"
        )
      );
      chunks.push(file.buffer);
      chunks.push(Buffer.from(crlf, "utf8"));
    }

    chunks.push(Buffer.from(`--${boundary}--${crlf}`, "utf8"));
    const bodyBuffer = Buffer.concat(chunks);

    const response = await this.request.post(this.url(path), {
      data: bodyBuffer,
      headers: {
        ...headers,
        "Content-Type": `multipart/form-data; boundary=${boundary}`
      }
    });
    const rawText = await response.text();
    let body: T;
    try {
      body = JSON.parse(rawText) as T;
    } catch {
      body = rawText as unknown as T;
    }
    return { response, status: response.status(), body, rawText };
  }

  /**
   * Bootstraps both default guest session (`config.private.json` + `/version`) and an
   * authenticated premium on-premise user session (`/user/register` -> `generateSecretKey` -> `generateToken`).
   */
  public async bootstrapSession(customEmail?: string): Promise<BootstrappedAuthSession> {
    const cfgRes = await this.getJson<ConfigPrivateJson>("/config.private.json");
    expect(cfgRes.status).toBe(200);
    const guestUserId = cfgRes.body?.userId || DEFAULT_GUEST_USER_ID;
    const guestToken = cfgRes.body?.Authorization?.base64token || "";

    const verRes = await this.getJson<VersionEnvelope>("/version", { userId: guestUserId });
    expect(verRes.status).toBe(200);
    expect(verRes.body?.version).toBeDefined();

    const email = customEmail || "admin@local.gudusoft.com";
    const password = customEmail ? "SqlFlow#2026!" : "admin";

    const regRes = await this.postForm<{
      code: string | number;
      token?: string;
      first_login?: boolean;
      data?: { userId?: string; id?: string; userEmail?: string };
      userId?: string;
    }>("/user/register", {
      userEmail: email,
      userName: email.split("@")[0] || "admin",
      password
    });
    expect(regRes.status).toBe(200);
    expect(String(regRes.body.code)).toBe("200");

    const userId =
      regRes.body.data?.userId ||
      regRes.body.userId ||
      "c427e50ca7acbf0fe86cc7edfbf1097c";
    const loginToken = regRes.body.token || guestToken;

    const authHeaders: Record<string, string> = loginToken
      ? { Authorization: `Token ${loginToken}` }
      : {};

    const skRes = await this.postForm<GenerateSecretKeyResponse>(
      "/user/generateSecretKey",
      { userId },
      authHeaders
    );
    expect(skRes.status).toBe(200);
    expect(skRes.body.code).toBe("200");
    expect(skRes.body.secretKey).toBeTruthy();

    const tokRes = await this.postForm<GenerateTokenResponse>("/user/generateToken", {
      userId,
      secretKey: skRes.body.secretKey
    });
    expect(tokRes.status).toBe(200);
    expect(tokRes.body.code).toBe("200");
    expect(tokRes.body.token).toBeTruthy();

    return {
      guestUserId,
      guestToken,
      userId,
      userEmail: email,
      loginToken,
      secretKey: skRes.body.secretKey,
      jwtToken: tokRes.body.token,
      version: verRes.body.version
    };
  }

  public authHeaders(session: Pick<BootstrappedAuthSession, "loginToken" | "secretKey">): Record<string, string> {
    const tok = session.loginToken || session.secretKey;
    return tok ? { Authorization: `Token ${tok}` } : {};
  }

  public bearerHeaders(secretKey: string): Record<string, string> {
    return { Authorization: `Bearer ${secretKey}` };
  }

  /**
   * Polls `displayUserJobSummary` until the job reaches a terminal state (`success` or `partial_success`).
   */
  public async waitForJobCompletion(
    userId: string,
    token: string,
    jobId: string,
    timeoutMs: number = 45_000
  ): Promise<Record<string, unknown>> {
    const deadline = Date.now() + timeoutMs;
    let lastJob: Record<string, unknown> | undefined;

    while (Date.now() < deadline) {
      const summaryRes = await this.postForm<{
        code: number | string;
        data?: Record<string, unknown>;
        error?: string;
      }>(
        "/sqlflow/job/displayUserJobSummary",
        { userId, token, jobId },
        { Authorization: `Token ${token}` }
      );

      if (String(summaryRes.body?.code) === "200" && summaryRes.body.data) {
        lastJob = summaryRes.body.data;
        const status = String(lastJob.status || "");
        if (status === "success" || status === "partial_success") {
          return lastJob;
        }
        if (status === "fail") {
          throw new Error(
            `Job ${jobId} failed: ${String(lastJob.errorMessage || JSON.stringify(lastJob))}`
          );
        }
      }

      // Also verify job appears in UserJobSummary list while polling
      const listRes = await this.postForm<{ code: number | string; data?: UserJobSummary }>(
        "/sqlflow/job/displayUserJobsSummary",
        { userId, token },
        { Authorization: `Token ${token}` }
      );
      expect(String(listRes.body?.code)).toBe("200");

      await new Promise((resolve) => setTimeout(resolve, 750));
    }

    throw new Error(
      `Timed out after ${timeoutMs}ms waiting for job ${jobId} to finish (last state: ${JSON.stringify(lastJob)})`
    );
  }
}

/**
 * Parses a raw `text/event-stream` HTTP response body into structured SSE frames.
 */
export function parseSseStream(rawBody: string): SseFrame[] {
  const frames: SseFrame[] = [];
  const blocks = rawBody.split(/\r?\n\r?\n/);

  for (const block of blocks) {
    const trimmed = block.trim();
    if (!trimmed) {
      continue;
    }
    let eventName: string | undefined;
    const dataLines: string[] = [];

    for (const line of trimmed.split(/\r?\n/)) {
      if (line.startsWith("event:")) {
        eventName = line.slice("event:".length).trim();
      } else if (line.startsWith("data:")) {
        dataLines.push(line.slice("data:".length).trim());
      }
    }

    if (dataLines.length > 0) {
      const joined = dataLines.join("\n");
      let parsed: Record<string, unknown> | undefined;
      try {
        parsed = JSON.parse(joined) as Record<string, unknown>;
      } catch {
        parsed = undefined;
      }
      frames.push({
        ...(eventName ? { event: eventName } : {}),
        data: joined,
        ...(parsed ? { parsed } : {})
      });
    }
  }
  return frames;
}

/**
 * Extracts entry names and UTF-8 text contents from an in-memory ZIP archive (`PK\x03\x04` / `PK\x01\x02`).
 */
export function readZipArchiveEntries(zipBytes: Buffer): Map<string, Buffer> {
  const entries = new Map<string, Buffer>();
  if (zipBytes.length < 4 || zipBytes.readUInt32LE(0) !== 0x04034b50) {
    throw new Error("Buffer does not start with ZIP local file header signature (PK\\x03\\x04)");
  }

  // Locate End of Central Directory (EOCD: 0x06054b50) from end of buffer
  let eocdOffset = -1;
  for (let i = zipBytes.length - 22; i >= Math.max(0, zipBytes.length - 65_557); i--) {
    if (zipBytes.readUInt32LE(i) === 0x06054b50) {
      eocdOffset = i;
      break;
    }
  }

  if (eocdOffset === -1) {
    throw new Error("ZIP End of Central Directory (EOCD) record not found");
  }

  const totalEntries = zipBytes.readUInt16LE(eocdOffset + 10);
  const centralDirOffset = zipBytes.readUInt32LE(eocdOffset + 16);

  let cdPos = centralDirOffset;
  for (let idx = 0; idx < totalEntries && cdPos + 46 <= zipBytes.length; idx++) {
    if (zipBytes.readUInt32LE(cdPos) !== 0x02014b50) {
      break;
    }
    const compressionMethod = zipBytes.readUInt16LE(cdPos + 10);
    const compressedSize = zipBytes.readUInt32LE(cdPos + 20);
    const fileNameLen = zipBytes.readUInt16LE(cdPos + 28);
    const extraLen = zipBytes.readUInt16LE(cdPos + 30);
    const commentLen = zipBytes.readUInt16LE(cdPos + 32);
    const localHeaderOffset = zipBytes.readUInt32LE(cdPos + 42);

    const fileName = zipBytes
      .subarray(cdPos + 46, cdPos + 46 + fileNameLen)
      .toString("utf8");

    const localNameLen = zipBytes.readUInt16LE(localHeaderOffset + 26);
    const localExtraLen = zipBytes.readUInt16LE(localHeaderOffset + 28);
    const dataStart = localHeaderOffset + 30 + localNameLen + localExtraLen;
    const rawSlice = zipBytes.subarray(dataStart, dataStart + compressedSize);

    if (!fileName.endsWith("/")) {
      if (compressionMethod === 0) {
        entries.set(fileName, Buffer.from(rawSlice));
      } else if (compressionMethod === 8) {
        entries.set(
          fileName,
          inflateRawSync(rawSlice, { finishFlush: zlibConstants.Z_SYNC_FLUSH })
        );
      }
    }

    cdPos += 46 + fileNameLen + extraLen + commentLen;
  }

  return entries;
}

/**
 * Validates PNG signature (`\x89PNG\r\n\x1a\n`) + `IHDR` chunk and returns `(width, height)`.
 */
export function inspectPngBytes(rawBytes: Buffer): { width: number; height: number } {
  let pngBuffer = rawBytes;

  // Some endpoints wrap PNG bytes inside `{"code":200,"data":"<base64>"}` while gateway image routes return raw bytes
  if (pngBuffer.length > 0 && pngBuffer[0] === 0x7b /* '{' */) {
    const parsed = JSON.parse(pngBuffer.toString("utf8")) as {
      code?: number | string;
      data?: string | number[];
    };
    if (typeof parsed.data === "string") {
      pngBuffer = Buffer.from(parsed.data, "base64");
    } else if (Array.isArray(parsed.data)) {
      pngBuffer = Buffer.from(parsed.data);
    }
  }

  const expectedSig = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);
  if (pngBuffer.length < 24 || !pngBuffer.subarray(0, 8).equals(expectedSig)) {
    throw new Error(`Invalid PNG signature (first 8 bytes: ${pngBuffer.subarray(0, 8).toString("hex")})`);
  }

  const ihdrTag = pngBuffer.subarray(12, 16).toString("ascii");
  if (ihdrTag !== "IHDR") {
    throw new Error(`Expected IHDR chunk at byte 12, found "${ihdrTag}"`);
  }

  const width = pngBuffer.readUInt32BE(16);
  const height = pngBuffer.readUInt32BE(20);
  return { width, height };
}

/**
 * Validates Apache Parquet magic header & trailer (`PAR1`).
 */
export function isValidParquetBuffer(buf: Buffer): boolean {
  if (buf.length < 12) {
    return false;
  }
  const magic = Buffer.from("PAR1", "ascii");
  return (
    buf.subarray(0, 4).equals(magic) &&
    buf.subarray(buf.length - 4, buf.length).equals(magic)
  );
}

/**
 * Normalizes `sqlflow.dbobjs` whether returned as a flat `DbObject[]` array or a hierarchical `SqlflowCatalog`.
 */
export function flattenDbObjects(dbobjs?: SqlflowCatalog | DbObject[] | null): DbObject[] {
  if (!dbobjs) {
    return [];
  }
  if (Array.isArray(dbobjs)) {
    return dbobjs;
  }
  const out: DbObject[] = [];
  const collectSchema = (sc: SchemaCatalog, dbName?: string, serverName?: string) => {
    for (const tbl of [...(sc.tables ?? []), ...(sc.views ?? []), ...(sc.others ?? [])]) {
      out.push({
        id: String(tbl.id ?? tbl.name),
        name: tbl.name,
        type: tbl.type ?? "table",
        database: dbName,
        schema: sc.name,
        server: serverName,
        columns: tbl.columns,
        coordinates: tbl.coordinates
      });
    }
  };
  const servers = "servers" in dbobjs && Array.isArray(dbobjs.servers) ? dbobjs.servers : [];
  for (const srv of servers) {
    for (const sc of srv.schemas ?? []) {
      collectSchema(sc, undefined, srv.name);
    }
    for (const db of srv.databases ?? []) {
      collectSchema(db, db.name, srv.name);
      for (const sc of db.schemas ?? []) {
        collectSchema(sc, db.name, srv.name);
      }
    }
  }
  return out;
}
