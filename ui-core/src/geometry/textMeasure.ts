/**
 * Verdana 12px (`scale(0.916)`) Text Width Estimator, Hidden DOM Font Probe,
 * Label Truncation, and Node Card Dimension Calculator.
 * Reference: docs/re-spec/04-frontend-ui-and-widget.md §3.1, §6.2, §6.3;
 *            docs/re-spec/03-lineage-engine-and-graph-model.md §4.4.2.
 */

import type { DbObject } from "../types/lineage.js";
import { CANVAS_GEOMETRY_TOKENS } from "../theme/tokens.js";

export const FONT_PROBE_SCALE = 0.916;
export const FONT_PROBE_FALLBACK_WIDTH = 160;
export const MAX_DISPLAY_LABEL_CHARS = 53;

/**
 * Calibrated Verdana 12px advance widths (in CSS px before `scale(0.916)`).
 * Multiplying by `0.916` matches the exact hidden `<span style="font-family:verdana; font-size:12px; transform:scale(0.916)">`
 * probe in headless environments (Node / jsdom) where `getBoundingClientRect().width === 0`.
 */
const VERDANA_12PX_NARROW_CHARS = new Set(["i", "j", "l", "I", "!", "|", ".", ",", ":", ";", "'", "`"]);
const VERDANA_12PX_SEMI_NARROW_CHARS = new Set(["f", "r", "t", "(", ")", "[", "]", "{", "}", "-", " "]);
const VERDANA_12PX_WIDE_CHARS = new Set(["m", "w", "M", "W", "@", "%", "&", "_"]);
const VERDANA_12PX_UPPER_CHARS = new Set("ABCDEFGHJKLNOPQRSTUVXYZ".split(""));

export function estimateVerdana12Width(text: string, scaled = true): number {
  if (!text) {
    return 0;
  }
  let width = 0;
  for (let i = 0; i < text.length; i++) {
    const ch = text[i]!;
    const code = text.charCodeAt(i);
    if (code > 0x7f) {
      // CJK / full-width glyph in 12px fallback font
      width += 12.0;
    } else if (VERDANA_12PX_NARROW_CHARS.has(ch)) {
      width += 3.6;
    } else if (VERDANA_12PX_SEMI_NARROW_CHARS.has(ch)) {
      width += 4.8;
    } else if (VERDANA_12PX_WIDE_CHARS.has(ch)) {
      width += 10.6;
    } else if (VERDANA_12PX_UPPER_CHARS.has(ch)) {
      width += 8.4;
    } else {
      width += 7.1;
    }
  }
  return scaled ? width * FONT_PROBE_SCALE : width;
}

let probeSpan: HTMLSpanElement | null = null;

/**
 * Appends the hidden `verdana 12px scale(0.916)` measurement probe to `document.body` (`04 §3.1`).
 * Safe to call multiple times or in non-DOM environments.
 */
export function insertFontProbe(doc?: Document): HTMLSpanElement | null {
  const targetDoc = doc ?? (typeof document !== "undefined" ? document : undefined);
  if (!targetDoc || !targetDoc.body) {
    return null;
  }
  if (probeSpan && probeSpan.isConnected) {
    return probeSpan;
  }

  const existing = targetDoc.querySelector<HTMLSpanElement>("[data-sqlflow-font-probe='1'] > span");
  if (existing) {
    probeSpan = existing;
    return probeSpan;
  }

  const wrapper = targetDoc.createElement("div");
  wrapper.setAttribute("aria-hidden", "true");
  wrapper.setAttribute("data-sqlflow-font-probe", "1");
  wrapper.style.position = "absolute";
  wrapper.style.visibility = "hidden";
  wrapper.style.pointerEvents = "none";
  wrapper.style.left = "0px";
  wrapper.style.top = "0px";
  wrapper.style.height = "16px";
  wrapper.style.zIndex = "100";
  wrapper.style.backgroundColor = "white";
  wrapper.style.whiteSpace = "pre";

  const span = targetDoc.createElement("span");
  span.style.fontFamily = "verdana, sans-serif";
  span.style.fontSize = "12px";
  span.style.transform = `scale(${FONT_PROBE_SCALE})`;
  span.style.display = "inline-block";
  span.style.height = "100%";

  wrapper.appendChild(span);
  targetDoc.body.appendChild(wrapper);
  probeSpan = span;
  return probeSpan;
}

/**
 * Middle-truncates labels exceeding `53` characters as `str.slice(0, 26) + "..." + str.slice(-26)` (`04 §3.1, §6.3`).
 */
export function truncateMiddle53(str: string): string {
  if (!str || str.length <= MAX_DISPLAY_LABEL_CHARS) {
    return str;
  }
  return `${str.slice(0, 26)}...${str.slice(-26)}`;
}

/**
 * Measures the pixel width of `str` using the live DOM font probe when layout metrics are available,
 * falling back seamlessly to the calibrated `verdana 12px` width estimator in headless / jsdom environments.
 */
export function getStringWidth(str: string): number {
  if (!str) {
    return 0;
  }
  const span = probeSpan ?? insertFontProbe();
  if (span) {
    span.textContent = str;
    const rectWidth = span.getBoundingClientRect().width;
    if (rectWidth > 0) {
      return rectWidth;
    }
  }
  return estimateVerdana12Width(str, true);
}

/**
 * Exact `get50CharWidth(str)` implementation (`04 §3.1`):
 * - If `str.length > 53`: measures `str.slice(0, 26) + "..." + str.slice(-26)`
 * - Else: measures `str + "AAA"` (3-character right safety pad)
 */
export function get50CharWidth(str: string): number {
  if (!str) {
    return getStringWidth("AAA");
  }
  const probeText = str.length > MAX_DISPLAY_LABEL_CHARS ? truncateMiddle53(str) : `${str}AAA`;
  const measured = getStringWidth(probeText);
  return measured > 0 ? measured : FONT_PROBE_FALLBACK_WIDTH;
}

/**
 * Strips `"DEFAULT"` database and schema placeholders (`03 §4.4.2`, `04 §11.5`)
 * to construct clean qualified display titles for nodes and tooltips.
 */
export function stripDefaultQualifier(value?: string | null): string | undefined {
  if (!value) {
    return undefined;
  }
  const trimmed = value.trim();
  if (!trimmed || trimmed.toUpperCase() === "DEFAULT") {
    return undefined;
  }
  return trimmed;
}

export function formatDbObjectDisplayTitle(
  obj: Pick<DbObject, "name" | "database" | "schema" | "type">,
  options?: { includeDatabase?: boolean }
): string {
  const rawName = obj.name?.trim() || "";
  if (!rawName) {
    return "UNNAMED";
  }

  // Strip leading DEFAULT. qualifiers if embedded in name
  const cleanedName = rawName.replace(/^(?:DEFAULT\.)+/gi, "");
  const db = options?.includeDatabase ? stripDefaultQualifier(obj.database) : undefined;
  const schema = stripDefaultQualifier(obj.schema);

  // Avoid duplicating schema/database if already present in `obj.name`
  const parts = cleanedName.split(".");
  if (parts.length >= 2) {
    if (db && parts.length === 2 && parts[0]?.toLowerCase() !== db.toLowerCase()) {
      return `${db}.${cleanedName}`;
    }
    return cleanedName;
  }

  return [db, schema, cleanedName].filter(Boolean).join(".");
}

export interface NodeDimensionsInput {
  title: string;
  columnLabels?: readonly string[];
  tableLevel?: boolean;
}

export interface NodeDimensionsResult {
  width: number;
  height: number;
  headerHeight: number;
  rowHeight: number;
}

/**
 * Computes exact node card dimensions (`04 §6.2`):
 * - Header height: `22px` (`26px` single-row pill in table-level mode)
 * - Column row height: `16px`
 * - Total height: `22 + columns.length * 16 + 2` px (`26px` in table-level mode)
 * - Total width: `max(get50CharWidth(title), max_col(get50CharWidth(col))) + 24` px (min `120px`)
 */
export function computeNodeDimensions(input: NodeDimensionsInput): NodeDimensionsResult {
  const headerHeight = input.tableLevel
    ? CANVAS_GEOMETRY_TOKENS.tableLevelNodeHeight
    : CANVAS_GEOMETRY_TOKENS.nodeHeaderHeight;
  const rowHeight = CANVAS_GEOMETRY_TOKENS.fieldRowHeight;
  const columns = input.tableLevel ? [] : input.columnLabels ?? [];

  let maxContentWidth = get50CharWidth(input.title);
  for (const colLabel of columns) {
    const w = get50CharWidth(colLabel);
    if (w > maxContentWidth) {
      maxContentWidth = w;
    }
  }

  const width = Math.max(
    CANVAS_GEOMETRY_TOKENS.minNodeWidth,
    Math.ceil(maxContentWidth + CANVAS_GEOMETRY_TOKENS.nodeHorizontalPadding)
  );
  const height = input.tableLevel
    ? headerHeight
    : headerHeight + columns.length * rowHeight + CANVAS_GEOMETRY_TOKENS.nodeBorderVerticalPx;

  return {
    width,
    height,
    headerHeight,
    rowHeight
  };
}
