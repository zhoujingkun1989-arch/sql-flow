/**
 * Reusable SQL Code Editor & Read-Only Drawer Viewer (`<SqlCodeEditor />`)
 * shared 1:1 between `frontend-ui` and `widget-sdk`.
 *
 * Implements (`04 §5.4`, `§6.10`, `§7.5`):
 * - 1-based line numbers + red error gutter markers (`LineageError[]`)
 * - Deterministic SQL syntax highlighting (keywords, functions/types, strings, numbers, comments)
 * - Bidirectional 1-based `[line, col]` coordinate range highlighting:
 *   - `.mouseover` (`#dadada` background) for hovered graph elements
 *   - `.highlight` (`#443b3b` background, `#ffffff` text) for pinned graph elements
 * - Automatic `scrollIntoView` to the active highlighted coordinate span
 * - Editor -> Canvas hover/click 1-based `(line, col)` lookup against `TokenCoordinateIndex`
 */

import React, { useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  type Coordinate,
  type CoordinateSpan,
  normalizeCoordinateSpans
} from "../types/coordinates.js";
import type { LineageError } from "../types/lineage.js";
import { SURFACE_AND_EDGE_TOKENS } from "../theme/tokens.js";
import {
  type TokenCoordinateHit,
  type TokenCoordinateIndex,
  lookupTokenAtPosition
} from "../geometry/layoutAdapter.js";

export type SqlSyntaxTokenKind =
  | "keyword"
  | "function"
  | "string"
  | "number"
  | "comment"
  | "plain";

export interface SqlLineToken {
  text: string;
  kind: SqlSyntaxTokenKind;
  /** 1-based inclusive start column */
  startCol: number;
  /** 1-based exclusive end column */
  endCol: number;
  highlightState: "none" | "hover" | "pinned";
}

const SQL_KEYWORDS = new Set([
  "SELECT",
  "FROM",
  "WHERE",
  "JOIN",
  "INNER",
  "LEFT",
  "RIGHT",
  "FULL",
  "OUTER",
  "CROSS",
  "ON",
  "USING",
  "AS",
  "WITH",
  "RECURSIVE",
  "GROUP",
  "BY",
  "HAVING",
  "ORDER",
  "ASC",
  "DESC",
  "LIMIT",
  "OFFSET",
  "FETCH",
  "FIRST",
  "ROWS",
  "ONLY",
  "UNION",
  "ALL",
  "INTERSECT",
  "EXCEPT",
  "INSERT",
  "INTO",
  "OVERWRITE",
  "VALUES",
  "UPDATE",
  "SET",
  "DELETE",
  "MERGE",
  "WHEN",
  "MATCHED",
  "NOT",
  "THEN",
  "CREATE",
  "OR",
  "REPLACE",
  "ALTER",
  "DROP",
  "TRUNCATE",
  "TABLE",
  "VIEW",
  "MATERIALIZED",
  "DATABASE",
  "SCHEMA",
  "INDEX",
  "UNIQUE",
  "PRIMARY",
  "FOREIGN",
  "KEY",
  "REFERENCES",
  "CONSTRAINT",
  "PROCEDURE",
  "FUNCTION",
  "PACKAGE",
  "BODY",
  "TRIGGER",
  "BEGIN",
  "END",
  "DECLARE",
  "RETURN",
  "RETURNS",
  "LANGUAGE",
  "CASE",
  "ELSE",
  "AND",
  "IN",
  "EXISTS",
  "BETWEEN",
  "LIKE",
  "IS",
  "NULL",
  "TRUE",
  "FALSE",
  "DISTINCT",
  "OVER",
  "PARTITION",
  "QUALIFY",
  "LATERAL",
  "PIVOT",
  "UNPIVOT",
  "TOP",
  "GO",
  "EXEC",
  "CALL"
]);

const SQL_FUNCTIONS_AND_TYPES = new Set([
  "COUNT",
  "SUM",
  "AVG",
  "MIN",
  "MAX",
  "COALESCE",
  "NVL",
  "ROUND",
  "CAST",
  "CONVERT",
  "CONCAT",
  "CONCAT_WS",
  "SUBSTR",
  "SUBSTRING",
  "TRIM",
  "DATE_TRUNC",
  "DATE_FORMAT",
  "TO_CHAR",
  "TO_DATE",
  "NOW",
  "GETDATE",
  "SYSDATE",
  "CURRENT_DATE",
  "CURRENT_TIMESTAMP",
  "ROW_NUMBER",
  "RANK",
  "DENSE_RANK",
  "LISTAGG",
  "STRING_AGG",
  "GROUP_CONCAT",
  "ARRAY_AGG",
  "COLLECT_LIST",
  "INT",
  "INTEGER",
  "BIGINT",
  "DECIMAL",
  "NUMERIC",
  "FLOAT",
  "REAL",
  "NUMBER",
  "VARCHAR",
  "VARCHAR2",
  "CHAR",
  "TEXT",
  "STRING",
  "DATE",
  "DATETIME",
  "TIMESTAMP",
  "BOOLEAN"
]);

const TOKEN_COLORS: Readonly<Record<SqlSyntaxTokenKind, string>> = {
  keyword: "#7c3aed",
  function: "#0369a1",
  string: "#b91c1c",
  number: "#047857",
  comment: "#6b7280",
  plain: "#1f2937"
};

interface RawLexToken {
  text: string;
  kind: SqlSyntaxTokenKind;
  startCol: number;
  endCol: number;
}

/**
 * Tokenizes SQL text line-by-line while tracking multi-line `/* ... *\/` block comments.
 */
export function tokenizeSqlLines(sql: string): RawLexToken[][] {
  const lines = sql.split("\n");
  const out: RawLexToken[][] = [];
  let inBlockComment = false;

  for (const line of lines) {
    const lineTokens: RawLexToken[] = [];
    let i = 0;
    const len = line.length;

    while (i < len) {
      if (inBlockComment) {
        const closeIdx = line.indexOf("*/", i);
        if (closeIdx === -1) {
          lineTokens.push({
            text: line.slice(i),
            kind: "comment",
            startCol: i + 1,
            endCol: len + 1
          });
          i = len;
        } else {
          const end = closeIdx + 2;
          lineTokens.push({
            text: line.slice(i, end),
            kind: "comment",
            startCol: i + 1,
            endCol: end + 1
          });
          i = end;
          inBlockComment = false;
        }
        continue;
      }

      if (line.startsWith("/*", i)) {
        const closeIdx = line.indexOf("*/", i + 2);
        if (closeIdx === -1) {
          inBlockComment = true;
          lineTokens.push({
            text: line.slice(i),
            kind: "comment",
            startCol: i + 1,
            endCol: len + 1
          });
          i = len;
        } else {
          const end = closeIdx + 2;
          lineTokens.push({
            text: line.slice(i, end),
            kind: "comment",
            startCol: i + 1,
            endCol: end + 1
          });
          i = end;
        }
        continue;
      }

      if (line.startsWith("--", i)) {
        lineTokens.push({
          text: line.slice(i),
          kind: "comment",
          startCol: i + 1,
          endCol: len + 1
        });
        break;
      }

      const ch = line[i]!;
      if (ch === "'" || ch === '"') {
        let j = i + 1;
        while (j < len) {
          if (line[j] === ch) {
            if (line[j + 1] === ch) {
              j += 2;
              continue;
            }
            j += 1;
            break;
          }
          j += 1;
        }
        lineTokens.push({
          text: line.slice(i, j),
          kind: ch === "'" ? "string" : "plain",
          startCol: i + 1,
          endCol: j + 1
        });
        i = j;
        continue;
      }

      if (/[0-9]/.test(ch)) {
        let j = i + 1;
        while (j < len && /[0-9.]/.test(line[j]!)) {
          j += 1;
        }
        lineTokens.push({
          text: line.slice(i, j),
          kind: "number",
          startCol: i + 1,
          endCol: j + 1
        });
        i = j;
        continue;
      }

      if (/[A-Za-z_$@#]/.test(ch)) {
        let j = i + 1;
        while (j < len && /[A-Za-z0-9_$#]/.test(line[j]!)) {
          j += 1;
        }
        const word = line.slice(i, j);
        const upper = word.toUpperCase();
        const kind: SqlSyntaxTokenKind = SQL_KEYWORDS.has(upper)
          ? "keyword"
          : SQL_FUNCTIONS_AND_TYPES.has(upper)
            ? "function"
            : "plain";
        lineTokens.push({
          text: word,
          kind,
          startCol: i + 1,
          endCol: j + 1
        });
        i = j;
        continue;
      }

      let j = i + 1;
      while (
        j < len &&
        !/[A-Za-z0-9_$@#'"]/.test(line[j]!) &&
        !line.startsWith("--", j) &&
        !line.startsWith("/*", j)
      ) {
        j += 1;
      }
      lineTokens.push({
        text: line.slice(i, j),
        kind: "plain",
        startCol: i + 1,
        endCol: j + 1
      });
      i = j;
    }

    out.push(lineTokens);
  }

  return out;
}

function getLineColRangeForSpan(
  line1Based: number,
  lineLength: number,
  span: CoordinateSpan
): [number, number] | null {
  if (line1Based < span.startLine || line1Based > span.endLine) {
    return null;
  }
  const startCol = line1Based === span.startLine ? Math.max(1, span.startCol) : 1;
  // Note: backend coordinates use 1-based inclusive/exclusive endCol; ensure at least 1 char width
  const rawEndCol =
    line1Based === span.endLine ? Math.max(startCol + 1, span.endCol) : lineLength + 1;
  const endCol = Math.min(lineLength + 1, rawEndCol);
  if (endCol <= startCol) {
    return null;
  }
  return [startCol, endCol];
}

/**
 * Splits a line's syntax tokens along active 1-based `hoverSpans` and `pinnedSpans`
 * so character-exact SQL substrings receive `.mouseover` (`#dadada`) or `.highlight` (`#443b3b`).
 */
function applyCoordinateHighlightsToLine(
  line1Based: number,
  lineText: string,
  lexTokens: RawLexToken[],
  hoverSpans: CoordinateSpan[],
  pinnedSpans: CoordinateSpan[]
): SqlLineToken[] {
  const len = lineText.length;
  if (len === 0) {
    return [];
  }

  // Per-character highlight state (1..len)
  const charState = new Array<"none" | "hover" | "pinned">(len + 1).fill("none");

  for (const hs of hoverSpans) {
    const range = getLineColRangeForSpan(line1Based, len, hs);
    if (!range) {
      continue;
    }
    for (let c = range[0]; c < range[1] && c <= len; c++) {
      charState[c] = "hover";
    }
  }

  for (const ps of pinnedSpans) {
    const range = getLineColRangeForSpan(line1Based, len, ps);
    if (!range) {
      continue;
    }
    for (let c = range[0]; c < range[1] && c <= len; c++) {
      charState[c] = "pinned";
    }
  }

  const result: SqlLineToken[] = [];
  for (const tok of lexTokens) {
    let segStart = tok.startCol;
    let currentState = charState[segStart] ?? "none";

    for (let col = tok.startCol + 1; col < tok.endCol; col++) {
      const st = charState[col] ?? "none";
      if (st !== currentState) {
        result.push({
          text: lineText.slice(segStart - 1, col - 1),
          kind: tok.kind,
          startCol: segStart,
          endCol: col,
          highlightState: currentState
        });
        segStart = col;
        currentState = st;
      }
    }

    result.push({
      text: lineText.slice(segStart - 1, tok.endCol - 1),
      kind: tok.kind,
      startCol: segStart,
      endCol: tok.endCol,
      highlightState: currentState
    });
  }

  return result;
}

function resolveErrorLineNumber(err: LineageError): number {
  if (err.coordinates && err.coordinates.length > 0) {
    const spans = normalizeCoordinateSpans(err.coordinates);
    if (spans[0] && spans[0].startLine >= 1) {
      return spans[0].startLine;
    }
  }
  const match = /\bline\s+(\d+)/i.exec(err.errorMessage);
  if (match?.[1]) {
    const parsed = Number.parseInt(match[1], 10);
    if (Number.isFinite(parsed) && parsed >= 1) {
      return parsed;
    }
  }
  return 1;
}

export interface SqlCodeEditorProps {
  value: string;
  onChange?: (nextValue: string) => void;
  readOnly?: boolean;
  lineNumbers?: boolean;
  hoverCoordinates?: Coordinate[];
  highlightCoordinates?: Coordinate[];
  activeOccurrenceIndex?: number;
  errors?: LineageError[];
  tokenIndex?: TokenCoordinateIndex;
  activeLine1Based?: number;
  height?: number | string;
  className?: string;
  style?: React.CSSProperties;
  placeholder?: string;
  onTokenHover?: (
    line1Based: number,
    col1Based: number,
    hit?: TokenCoordinateHit
  ) => void;
  onTokenClick?: (
    line1Based: number,
    col1Based: number,
    hit?: TokenCoordinateHit
  ) => void;
  onCursorPositionChange?: (line1Based: number, col1Based: number) => void;
  onErrorMarkerClick?: (error: LineageError, line1Based: number) => void;
}

export const SqlCodeEditor: React.FC<SqlCodeEditorProps> = ({
  value,
  onChange,
  readOnly = false,
  lineNumbers = true,
  hoverCoordinates,
  highlightCoordinates,
  activeOccurrenceIndex = 0,
  errors,
  tokenIndex,
  activeLine1Based: externalActiveLine,
  height = "100%",
  className,
  style,
  placeholder = "-- Enter SQL query to analyze lineage...",
  onTokenHover,
  onTokenClick,
  onCursorPositionChange,
  onErrorMarkerClick
}) => {
  const [cursorLine, setCursorLine] = useState<number>(1);
  const textareaRef = useRef<HTMLTextAreaElement | null>(null);
  const backdropScrollRef = useRef<HTMLDivElement | null>(null);
  const gutterScrollRef = useRef<HTMLDivElement | null>(null);
  const lineElementRefs = useRef<Map<number, HTMLDivElement>>(new Map());

  const lines = useMemo(() => value.split("\n"), [value]);
  const lexedLines = useMemo(() => tokenizeSqlLines(value), [value]);

  const hoverSpans = useMemo(
    () => normalizeCoordinateSpans(hoverCoordinates),
    [hoverCoordinates]
  );
  const pinnedSpans = useMemo(
    () => normalizeCoordinateSpans(highlightCoordinates),
    [highlightCoordinates]
  );

  const errorsByLine = useMemo(() => {
    const map = new Map<number, LineageError[]>();
    for (const err of errors ?? []) {
      const ln = Math.min(Math.max(1, resolveErrorLineNumber(err)), Math.max(1, lines.length));
      const existing = map.get(ln) ?? [];
      existing.push(err);
      map.set(ln, existing);
    }
    return map;
  }, [errors, lines.length]);

  // Automatically scroll target highlighted line into view (`04 §5.4`)
  useEffect(() => {
    if (pinnedSpans.length === 0) {
      return;
    }
    const clampedIdx =
      ((activeOccurrenceIndex % pinnedSpans.length) + pinnedSpans.length) %
      pinnedSpans.length;
    const targetSpan = pinnedSpans[clampedIdx];
    if (!targetSpan) {
      return;
    }
    const lineEl = lineElementRefs.current.get(targetSpan.startLine);
    lineEl?.scrollIntoView?.({ block: "nearest" });

    const lineHeight = 20;
    const paddingTop = 8;
    const scrollContainer = textareaRef.current ?? backdropScrollRef.current;
    const containerHeight = scrollContainer?.clientHeight || 180;
    const targetScrollTop = Math.max(
      0,
      paddingTop +
        (targetSpan.startLine - 1) * lineHeight -
        containerHeight / 2 +
        lineHeight / 2
    );
    if (backdropScrollRef.current) {
      backdropScrollRef.current.scrollTop = targetScrollTop;
    }
    if (textareaRef.current) {
      textareaRef.current.scrollTop = targetScrollTop;
    }
    if (gutterScrollRef.current) {
      gutterScrollRef.current.scrollTop = targetScrollTop;
    }
  }, [pinnedSpans, activeOccurrenceIndex]);

  const compute1BasedLineColFromOffset = useCallback(
    (charOffset: number): { line: number; col: number } => {
      const clamped = Math.max(0, Math.min(value.length, charOffset));
      const prefix = value.slice(0, clamped);
      const lastNewline = prefix.lastIndexOf("\n");
      if (lastNewline === -1) {
        return { line: 1, col: clamped + 1 };
      }
      let lineCount = 1;
      for (let i = 0; i < prefix.length; i++) {
        if (prefix[i] === "\n") {
          lineCount += 1;
        }
      }
      return {
        line: lineCount,
        col: clamped - lastNewline
      };
    },
    [value]
  );

  const handleTextareaCursorMove = useCallback(
    (el: HTMLTextAreaElement) => {
      const pos = compute1BasedLineColFromOffset(el.selectionStart ?? 0);
      setCursorLine(pos.line);
      onCursorPositionChange?.(pos.line, pos.col);
      return pos;
    },
    [compute1BasedLineColFromOffset, onCursorPositionChange]
  );

  const handleTextareaClick = useCallback(
    (el: HTMLTextAreaElement) => {
      const pos = handleTextareaCursorMove(el);
      if (tokenIndex || onTokenClick) {
        const hit = tokenIndex
          ? lookupTokenAtPosition(tokenIndex, pos.line, pos.col)
          : undefined;
        onTokenClick?.(pos.line, pos.col, hit);
      }
    },
    [handleTextareaCursorMove, onTokenClick, tokenIndex]
  );

  const syncScrollFromTextarea = useCallback(() => {
    const ta = textareaRef.current;
    if (!ta) {
      return;
    }
    if (backdropScrollRef.current) {
      backdropScrollRef.current.scrollTop = ta.scrollTop;
      backdropScrollRef.current.scrollLeft = ta.scrollLeft;
    }
    if (gutterScrollRef.current) {
      gutterScrollRef.current.scrollTop = ta.scrollTop;
    }
  }, []);

  const syncScrollFromReadOnlyBody = useCallback(() => {
    const bd = backdropScrollRef.current;
    if (!bd || !gutterScrollRef.current) {
      return;
    }
    gutterScrollRef.current.scrollTop = bd.scrollTop;
  }, []);

  const effectiveActiveLine = externalActiveLine ?? cursorLine;

  const fontStyle: React.CSSProperties = {
    fontFamily: "Consolas, Menlo, Monaco, monospace",
    fontSize: 13,
    lineHeight: "20px",
    tabSize: 4
  };

  return (
    <div
      data-testid="sqlflow-code-editor"
      data-readonly={readOnly ? "true" : "false"}
      className={className}
      style={{
        display: "flex",
        position: "relative",
        width: "100%",
        height,
        backgroundColor: "#ffffff",
        border: "1px solid #d9d9d9",
        overflow: "hidden",
        boxSizing: "border-box",
        ...fontStyle,
        ...style
      }}
    >
      {/* 1-Based Line Numbers + Error Gutter Markers (`04 §5.4`, `§7.5`) */}
      {lineNumbers && (
        <div
          ref={gutterScrollRef}
          data-testid="sql-editor-gutter"
          style={{
            width: 48,
            flexShrink: 0,
            backgroundColor: "#f7f7f7",
            borderRight: "1px solid #e5e7eb",
            overflow: "hidden",
            padding: "8px 0",
            userSelect: "none",
            textAlign: "right",
            color: "#9ca3af"
          }}
        >
          {lines.map((_, idx) => {
            const lineNum = idx + 1;
            const lineErrors = errorsByLine.get(lineNum);
            const hasError = Boolean(lineErrors && lineErrors.length > 0);
            const isActive = lineNum === effectiveActiveLine;

            return (
              <div
                key={lineNum}
                data-testid={`gutter-line-${lineNum}`}
                style={{
                  height: 20,
                  lineHeight: "20px",
                  padding: "0 6px 0 4px",
                  display: "flex",
                  alignItems: "center",
                  justifyContent: "space-between",
                  backgroundColor: hasError
                    ? "#fef2f2"
                    : isActive
                      ? "#eef2ff"
                      : "transparent",
                  color: hasError ? "#dc2626" : isActive ? "#111827" : "#9ca3af",
                  fontWeight: isActive || hasError ? 600 : 400
                }}
              >
                <span style={{ width: 14, textAlign: "center" }}>
                  {hasError && lineErrors?.[0] && (
                    <button
                      type="button"
                      data-testid={`error-gutter-marker-${lineNum}`}
                      title={lineErrors
                        .map((e) => `${e.errorType}: ${e.errorMessage}`)
                        .join("\n")}
                      onClick={() => onErrorMarkerClick?.(lineErrors[0]!, lineNum)}
                      style={{
                        width: 13,
                        height: 13,
                        padding: 0,
                        border: "none",
                        borderRadius: "50%",
                        backgroundColor: "#ef4444",
                        color: "#ffffff",
                        fontSize: 9,
                        fontWeight: 700,
                        lineHeight: "13px",
                        cursor: "pointer"
                      }}
                    >
                      !
                    </button>
                  )}
                </span>
                <span>{lineNum}</span>
              </div>
            );
          })}
        </div>
      )}

      {/* Code Viewport (Synchronized Highlighted Layer + Editable Textarea when !readOnly) */}
      <div style={{ position: "relative", flex: 1, height: "100%", overflow: "hidden" }}>
        <div
          ref={backdropScrollRef}
          data-testid="sql-editor-highlight-layer"
          onScroll={readOnly ? syncScrollFromReadOnlyBody : undefined}
          onMouseLeave={readOnly ? () => onTokenHover?.(0, 0, undefined) : undefined}
          style={{
            position: "absolute",
            inset: 0,
            padding: "8px 10px",
            margin: 0,
            overflow: readOnly ? "auto" : "hidden",
            whiteSpace: "pre",
            pointerEvents: readOnly ? "auto" : "none",
            boxSizing: "border-box",
            ...fontStyle
          }}
        >
          {lines.map((lineText, idx) => {
            const lineNum = idx + 1;
            const rawTokens = lexedLines[idx] ?? [];
            const decoratedTokens = applyCoordinateHighlightsToLine(
              lineNum,
              lineText,
              rawTokens,
              hoverSpans,
              pinnedSpans
            );
            const hasError = errorsByLine.has(lineNum);

            return (
              <div
                key={lineNum}
                ref={(el) => {
                  if (el) {
                    lineElementRefs.current.set(lineNum, el);
                  } else {
                    lineElementRefs.current.delete(lineNum);
                  }
                }}
                data-testid={`sql-line-${lineNum}`}
                style={{
                  minHeight: 20,
                  height: 20,
                  lineHeight: "20px",
                  backgroundColor:
                    hasError
                      ? "rgba(254, 226, 226, 0.45)"
                      : lineNum === effectiveActiveLine && !readOnly
                        ? "rgba(243, 244, 246, 0.7)"
                        : "transparent"
                }}
              >
                {decoratedTokens.length === 0 ? (
                  <span>{"\u200B"}</span>
                ) : (
                  decoratedTokens.map((tok, tokIdx) => {
                    const isPinned = tok.highlightState === "pinned";
                    const isHover = tok.highlightState === "hover";
                    const bg = isPinned
                      ? SURFACE_AND_EDGE_TOKENS.codeTokenHighlightBg
                      : isHover
                        ? SURFACE_AND_EDGE_TOKENS.codeTokenHoverBg
                        : "transparent";
                    const fg = isPinned
                      ? SURFACE_AND_EDGE_TOKENS.codeTokenHighlightText
                      : TOKEN_COLORS[tok.kind];

                    return (
                      <span
                        key={`${lineNum}-${tok.startCol}-${tokIdx}`}
                        data-token-kind={tok.kind}
                        data-highlight-state={tok.highlightState}
                        className={
                          isPinned
                            ? "highlight"
                            : isHover
                              ? "mouseover"
                              : undefined
                        }
                        onMouseEnter={
                          readOnly
                            ? () => {
                                const hit = tokenIndex
                                  ? lookupTokenAtPosition(
                                      tokenIndex,
                                      lineNum,
                                      tok.startCol
                                    )
                                  : undefined;
                                onTokenHover?.(lineNum, tok.startCol, hit);
                              }
                            : undefined
                        }
                        onClick={
                          readOnly
                            ? () => {
                                setCursorLine(lineNum);
                                onCursorPositionChange?.(lineNum, tok.startCol);
                                const hit = tokenIndex
                                  ? lookupTokenAtPosition(
                                      tokenIndex,
                                      lineNum,
                                      tok.startCol
                                    )
                                  : undefined;
                                onTokenClick?.(lineNum, tok.startCol, hit);
                              }
                            : undefined
                        }
                        style={{
                          backgroundColor: bg,
                          color: fg,
                          fontWeight: tok.kind === "keyword" ? 600 : 400,
                          borderRadius: isPinned || isHover ? 2 : 0,
                          cursor: readOnly ? "pointer" : undefined
                        }}
                      >
                        {tok.text}
                      </span>
                    );
                  })
                )}
              </div>
            );
          })}
        </div>

        {!readOnly && (
          <textarea
            ref={textareaRef}
            data-testid="sql-editor-textarea"
            aria-label="SQL Editor"
            spellCheck={false}
            autoCapitalize="off"
            autoComplete="off"
            autoCorrect="off"
            placeholder={placeholder}
            value={value}
            onChange={(e) => {
              onChange?.(e.target.value);
              handleTextareaCursorMove(e.target);
            }}
            onClick={(e) => handleTextareaClick(e.currentTarget)}
            onKeyUp={(e) => handleTextareaCursorMove(e.currentTarget)}
            onScroll={syncScrollFromTextarea}
            onMouseLeave={() => onTokenHover?.(0, 0, undefined)}
            onMouseMove={(e) => {
              if (!onTokenHover) {
                return;
              }
              const rect = e.currentTarget.getBoundingClientRect();
              const relY = e.clientY - rect.top + e.currentTarget.scrollTop - 8;
              const relX = e.clientX - rect.left + e.currentTarget.scrollLeft - 10;
              const approxLine = Math.max(1, Math.min(lines.length, Math.floor(relY / 20) + 1));
              const approxCol = Math.max(1, Math.floor(relX / 7.8) + 1);
              const hit = tokenIndex
                ? lookupTokenAtPosition(tokenIndex, approxLine, approxCol)
                : undefined;
              onTokenHover(approxLine, approxCol, hit);
            }}
            style={{
              position: "absolute",
              inset: 0,
              width: "100%",
              height: "100%",
              padding: "8px 10px",
              margin: 0,
              border: "none",
              outline: "none",
              resize: "none",
              overflow: "auto",
              whiteSpace: "pre",
              background: "transparent",
              color: "transparent",
              caretColor: "#111827",
              boxSizing: "border-box",
              ...fontStyle
            }}
          />
        )}
      </div>
    </div>
  );
};
