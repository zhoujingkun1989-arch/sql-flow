/**
 * Incremental Server-Sent Events (SSE) Frame Parser & Streaming Client
 * for `POST /sqlflow/generation/ai/query` (`text/event-stream`).
 *
 * Parses all 5 wire event types (`00 §3.3 #16`, `02 §4.5`, `04 §12`):
 * - `metadata`: `{ type: "metadata", nodes, edges, truncated }`
 * - `chunk`:    `{ type: "chunk", text }`
 * - `complete`: `{ type: "complete", response }`
 * - `no_match`: `{ type: "no_match", message }`
 * - `error`:    `{ type: "error", message }`
 */

import type { AiQueryStreamParams, AiSseEvent } from "../types/api.js";
import { toApiDbVendor } from "../types/vendor.js";

const VALID_AI_SSE_TYPES = new Set(["metadata", "chunk", "complete", "no_match", "error"]);

/**
 * Stateful SSE frame decoder that buffers arbitrary byte/string chunks
 * across `\n\n` or `\r\n\r\n` boundaries and emits typed `AiSseEvent` frames.
 */
export class SseFrameParser {
  private buffer = "";

  public feed(chunk: string): AiSseEvent[] {
    if (!chunk) {
      return [];
    }
    this.buffer += chunk.replace(/\r\n/g, "\n").replace(/\r/g, "\n");
    const events: AiSseEvent[] = [];

    let delimiterIdx = this.buffer.indexOf("\n\n");
    while (delimiterIdx !== -1) {
      const rawBlock = this.buffer.slice(0, delimiterIdx);
      this.buffer = this.buffer.slice(delimiterIdx + 2);
      const parsed = this.parseBlock(rawBlock);
      if (parsed) {
        events.push(parsed);
      }
      delimiterIdx = this.buffer.indexOf("\n\n");
    }

    return events;
  }

  public flush(): AiSseEvent[] {
    const remaining = this.buffer.trim();
    this.buffer = "";
    if (!remaining) {
      return [];
    }
    const parsed = this.parseBlock(remaining);
    return parsed ? [parsed] : [];
  }

  private parseBlock(block: string): AiSseEvent | null {
    const lines = block.split("\n");
    let eventHeaderType: string | undefined;
    const dataLines: string[] = [];

    for (const rawLine of lines) {
      if (!rawLine || rawLine.startsWith(":")) {
        continue;
      }
      const colonIdx = rawLine.indexOf(":");
      let field: string;
      let value: string;
      if (colonIdx === -1) {
        field = rawLine;
        value = "";
      } else {
        field = rawLine.slice(0, colonIdx);
        value = rawLine.slice(colonIdx + 1);
        if (value.startsWith(" ")) {
          value = value.slice(1);
        }
      }

      if (field === "event") {
        eventHeaderType = value.trim();
      } else if (field === "data") {
        dataLines.push(value);
      }
    }

    if (dataLines.length === 0) {
      return null;
    }

    const rawData = dataLines.join("\n").trim();
    if (!rawData || rawData === "[DONE]") {
      return null;
    }

    try {
      const parsed = JSON.parse(rawData) as Record<string, unknown>;
      const resolvedType =
        typeof parsed.type === "string"
          ? parsed.type
          : eventHeaderType;

      if (!resolvedType || !VALID_AI_SSE_TYPES.has(resolvedType)) {
        return {
          type: "error",
          message: `Unrecognized SSE event type: ${String(resolvedType ?? "unknown")}`
        };
      }

      switch (resolvedType) {
        case "metadata":
          return {
            type: "metadata",
            nodes: Number(parsed.nodes ?? 0),
            edges: Number(parsed.edges ?? 0),
            truncated: Boolean(parsed.truncated)
          };
        case "chunk":
          return {
            type: "chunk",
            text: typeof parsed.text === "string" ? parsed.text : String(parsed.text ?? "")
          };
        case "complete":
          return {
            type: "complete",
            response:
              parsed.response && typeof parsed.response === "object"
                ? (parsed.response as Extract<AiSseEvent, { type: "complete" }>["response"])
                : {
                    answer: typeof parsed.answer === "string" ? parsed.answer : ""
                  }
          };
        case "no_match":
          return {
            type: "no_match",
            message:
              typeof parsed.message === "string"
                ? parsed.message
                : "No matching objects found in lineage graph."
          };
        case "error":
          return {
            type: "error",
            message:
              typeof parsed.message === "string"
                ? parsed.message
                : typeof parsed.error === "string"
                  ? parsed.error
                  : "AI lineage query error"
          };
        default:
          return null;
      }
    } catch {
      if (eventHeaderType === "chunk") {
        return { type: "chunk", text: rawData };
      }
      if (eventHeaderType === "error") {
        return { type: "error", message: rawData };
      }
      if (eventHeaderType === "no_match") {
        return { type: "no_match", message: rawData };
      }
      return {
        type: "error",
        message: `Malformed SSE JSON frame: ${rawData}`
      };
    }
  }
}

/**
 * Async generator decoding a `ReadableStream<Uint8Array>` into typed `AiSseEvent` frames.
 */
export async function* parseSseByteStream(
  stream: ReadableStream<Uint8Array>
): AsyncGenerator<AiSseEvent, void, undefined> {
  const reader = stream.getReader();
  const decoder = new TextDecoder("utf-8");
  const parser = new SseFrameParser();

  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) {
        break;
      }
      const chunkText = decoder.decode(value, { stream: true });
      for (const evt of parser.feed(chunkText)) {
        yield evt;
      }
    }
    const tail = decoder.decode();
    for (const evt of parser.feed(tail)) {
      yield evt;
    }
    for (const evt of parser.flush()) {
      yield evt;
    }
  } finally {
    reader.releaseLock();
  }
}

export interface StreamAiQueryOptions {
  url: string;
  params: AiQueryStreamParams;
  token?: string;
  userId?: string;
  fetchImpl?: typeof fetch;
  signal?: AbortSignal;
  onEvent?: (event: AiSseEvent) => void;
}

/**
 * Streams `POST /sqlflow/generation/ai/query` SSE frames (`02 §4.5`),
 * invoking `onEvent` on each frame and returning the collected array of events.
 */
export async function streamAiLineageQuery(
  options: StreamAiQueryOptions
): Promise<AiSseEvent[]> {
  const fetchFn = options.fetchImpl ?? globalThis.fetch;
  const headers: Record<string, string> = {
    "Content-Type": "application/x-www-form-urlencoded;charset=UTF-8",
    Accept: "text/event-stream"
  };
  if (options.token !== undefined) {
    headers.Authorization = `Token ${options.token}`;
  }

  const formBody = new URLSearchParams();
  const resolvedUserId = options.params.userId ?? options.userId;
  if (resolvedUserId !== undefined) {
    formBody.set("userId", String(resolvedUserId));
  }
  formBody.set("sessionId", String(options.params.sessionId));
  formBody.set("question", String(options.params.question));
  if (options.params.dbvendor !== undefined) {
    formBody.set("dbvendor", toApiDbVendor(options.params.dbvendor));
  }
  if (options.params.hopDepth !== undefined) {
    formBody.set("hopDepth", String(options.params.hopDepth));
  }

  const response = await fetchFn(options.url, {
    method: "POST",
    headers,
    body: formBody.toString(),
    signal: options.signal
  });

  if (!response.ok) {
    const errText = await response.text().catch(() => response.statusText);
    const errEvent: AiSseEvent = {
      type: "error",
      message: errText || `HTTP ${response.status}`
    };
    options.onEvent?.(errEvent);
    return [errEvent];
  }

  const collected: AiSseEvent[] = [];
  if (response.body) {
    for await (const evt of parseSseByteStream(response.body)) {
      collected.push(evt);
      options.onEvent?.(evt);
    }
  } else {
    const fullText = await response.text();
    const parser = new SseFrameParser();
    for (const evt of [...parser.feed(fullText), ...parser.flush()]) {
      collected.push(evt);
      options.onEvent?.(evt);
    }
  }

  return collected;
}
