/**
 * 1-based [line, col] and 3-tuple [line, col, hashCode] token coordinates
 * per docs/re-spec/03-lineage-engine-and-graph-model.md §1.2 and 04 §5.4.
 */

export interface Point2D {
  x: number;
  y: number;
}

/**
 * Wire coordinate object.
 * - Backend pair format (03 §1.2.2): alternating [start, end, ...] items with { x: line, y: col, hashCode? }.
 * - Frontend compact span format (04 §5.4): { x: startLine, y: startCol, x1: endLine, y1: endCol, hashCode? }.
 */
export interface Coordinate {
  /** 1-based line number (-1 when synthetic) */
  x: number;
  /** 1-based column number (-1 when synthetic) */
  y: number;
  /** Optional statement/file hash identifier */
  hashCode?: string;
  /** Optional inline 1-based end line number */
  x1?: number;
  /** Optional inline 1-based end column number */
  y1?: number;
}

/**
 * Bidirectional SQL coordinate lookup response item from
 * POST /sqlflow/generation/sqlflow/getSelectedDbObjectInfo (03 §1.2.3).
 */
export interface DbObjectPosition {
  file: string;
  sql: string;
  index: number;
  /** Alternating [startPoint, endPoint, ...] 1-based { x: line, y: col } pairs */
  positions: Point2D[];
}

/**
 * Normalized 1-based inclusive-start / end span for editor & canvas sync.
 */
export interface CoordinateSpan {
  startLine: number;
  startCol: number;
  endLine: number;
  endCol: number;
  hashCode?: string;
}

/**
 * 0-based CodeMirror 5 doc.markText range ({ line: x - 1, ch: y - 1 }).
 */
export interface CodeMirrorRange {
  from: { line: number; ch: number };
  to: { line: number; ch: number };
}

/**
 * Parses XML/wire bracketed coordinate strings per PositionUtil (03 §1.2.1):
 * - 2-tuple pairs: "[12,8],[12,19]"
 * - 3-tuple pairs: "[12,8,0],[12,19,0],[20,4,1],[20,15,1]"
 */
export function parseCoordinateString(coordStr?: string | null): Coordinate[] {
  if (!coordStr || typeof coordStr !== "string") {
    return [];
  }
  const trimmed = coordStr.trim();
  if (!trimmed) {
    return [];
  }
  const firstOpen = trimmed.indexOf("[");
  const firstClose = trimmed.indexOf("]");
  if (firstOpen === -1 || firstClose <= firstOpen) {
    return [];
  }
  const firstInner = trimmed.slice(firstOpen + 1, firstClose);
  const arity = firstInner.split(",").length;
  if (arity !== 2 && arity !== 3) {
    return [];
  }
  const flatTokens = trimmed
    .replace(/[\[\]]/g, "")
    .split(",")
    .map((s) => s.trim())
    .filter((s) => s.length > 0);

  if (flatTokens.length === 0 || flatTokens.length % (2 * arity) !== 0) {
    return [];
  }

  const occurrences = flatTokens.length / (2 * arity);
  const result: Coordinate[] = [];
  for (let i = 0; i < occurrences; i++) {
    const baseStart = 2 * arity * i;
    const baseEnd = baseStart + arity;
    const sx = Number.parseInt(flatTokens[baseStart], 10);
    const sy = Number.parseInt(flatTokens[baseStart + 1], 10);
    const ex = Number.parseInt(flatTokens[baseEnd], 10);
    const ey = Number.parseInt(flatTokens[baseEnd + 1], 10);
    if (Number.isNaN(sx) || Number.isNaN(sy) || Number.isNaN(ex) || Number.isNaN(ey)) {
      return [];
    }
    const startHash = arity === 3 ? flatTokens[baseStart + 2] : undefined;
    const endHash = arity === 3 ? flatTokens[baseEnd + 2] : undefined;
    result.push(
      startHash !== undefined ? { x: sx, y: sy, hashCode: startHash } : { x: sx, y: sy },
      endHash !== undefined ? { x: ex, y: ey, hashCode: endHash } : { x: ex, y: ey }
    );
  }
  return result;
}

/**
 * Formats an alternating [start, end, ...] Coordinate[] array into wire bracket notation.
 */
export function formatCoordinateString(coords: readonly Coordinate[], includeHash = true): string {
  if (!coords || coords.length === 0) {
    return "";
  }
  const parts: string[] = [];
  for (let i = 0; i < coords.length; i++) {
    const c = coords[i];
    if (typeof c.x1 === "number" && typeof c.y1 === "number") {
      if (includeHash && c.hashCode !== undefined) {
        parts.push(`[${c.x},${c.y},${c.hashCode}]`, `[${c.x1},${c.y1},${c.hashCode}]`);
      } else {
        parts.push(`[${c.x},${c.y}]`, `[${c.x1},${c.y1}]`);
      }
    } else if (includeHash && c.hashCode !== undefined) {
      parts.push(`[${c.x},${c.y},${c.hashCode}]`);
    } else {
      parts.push(`[${c.x},${c.y}]`);
    }
  }
  return parts.join(",");
}

/**
 * Normalizes any supported coordinate representation (string, alternating pair array,
 * or inline {x, y, x1, y1} objects) into valid positive 1-based CoordinateSpan items.
 */
export function normalizeCoordinateSpans(
  input?: readonly Coordinate[] | string | null
): CoordinateSpan[] {
  if (!input) {
    return [];
  }
  const rawList: readonly Coordinate[] =
    typeof input === "string" ? parseCoordinateString(input) : input;
  if (!Array.isArray(rawList) || rawList.length === 0) {
    return [];
  }

  const spans: CoordinateSpan[] = [];
  let idx = 0;
  while (idx < rawList.length) {
    const cur = rawList[idx];
    if (!cur) {
      idx += 1;
      continue;
    }
    if (typeof cur.x1 === "number" && typeof cur.y1 === "number") {
      if (cur.x >= 1 && cur.y >= 1 && cur.x1 >= 1 && cur.y1 >= 1) {
        spans.push({
          startLine: cur.x,
          startCol: cur.y,
          endLine: cur.x1,
          endCol: cur.y1,
          ...(cur.hashCode !== undefined ? { hashCode: cur.hashCode } : {})
        });
      }
      idx += 1;
    } else if (idx + 1 < rawList.length) {
      const next = rawList[idx + 1];
      if (next && cur.x >= 1 && cur.y >= 1 && next.x >= 1 && next.y >= 1) {
        spans.push({
          startLine: cur.x,
          startCol: cur.y,
          endLine: next.x,
          endCol: next.y,
          ...(cur.hashCode !== undefined ? { hashCode: cur.hashCode } : {})
        });
      }
      idx += 2;
    } else {
      idx += 1;
    }
  }
  return spans;
}

/**
 * Converts a 1-based CoordinateSpan to a 0-based CodeMirror range (00 §3.3 #5, 04 §5.4).
 */
export function toCodeMirrorRange(span: CoordinateSpan): CodeMirrorRange {
  return {
    from: {
      line: Math.max(0, span.startLine - 1),
      ch: Math.max(0, span.startCol - 1)
    },
    to: {
      line: Math.max(0, span.endLine - 1),
      ch: Math.max(0, span.endCol - 1)
    }
  };
}

/**
 * Converts a 0-based CodeMirror cursor/token position to 1-based { line, column }.
 */
export function fromCodeMirrorPosition(pos: { line: number; ch: number }): {
  line: number;
  column: number;
} {
  return {
    line: pos.line + 1,
    column: pos.ch + 1
  };
}

/**
 * Checks whether a 1-based (line, column) position falls inside a 1-based CoordinateSpan
 * (where endCol is exclusive when > startCol on the same line, matching 03 §1.2.1).
 */
export function isPositionInSpan(
  line1Based: number,
  col1Based: number,
  span: CoordinateSpan
): boolean {
  if (line1Based < span.startLine || line1Based > span.endLine) {
    return false;
  }
  if (span.startLine === span.endLine) {
    const effectiveEnd = span.endCol > span.startCol ? span.endCol - 1 : span.endCol;
    return col1Based >= span.startCol && col1Based <= effectiveEnd;
  }
  if (line1Based === span.startLine) {
    return col1Based >= span.startCol;
  }
  if (line1Based === span.endLine) {
    return col1Based <= Math.max(1, span.endCol - 1);
  }
  return true;
}
