/**
 * Exact SVG Cubic Bézier (`M...C...S...`) Edge Path Builder,
 * Port Anchor Geometry, `10x7` Arrowhead Calculator, and Edge Visual Style Resolver.
 * Reference: docs/re-spec/04-frontend-ui-and-widget.md §3.4, §6.4.
 */

import type { Point2D } from "../types/coordinates.js";
import { CANVAS_GEOMETRY_TOKENS, SURFACE_AND_EDGE_TOKENS } from "../theme/tokens.js";

export const ARROWHEAD_LENGTH = 10;
export const ARROWHEAD_HALF_HEIGHT = 3.5;
export const ARROWHEAD_HEIGHT = 7;

export interface PortNodeBox {
  x: number;
  y: number;
  width: number;
  headerHeight?: number;
  rowHeight?: number;
}

/**
 * Computes the right-edge outgoing port anchor `(x1, y1)` for a source node or column row (`04 §6.4`):
 * - `x1 = sourceNode.x + sourceNode.width`
 * - Column row center: `y1 = sourceNode.y + 22 + colIndex * 16 + 8`
 * - Node header center (when `columnIndex` is null/undefined or `< 0`): `y1 = sourceNode.y + 11` (or `headerHeight / 2`)
 */
export function computeSourcePortAnchor(
  node: PortNodeBox,
  columnIndex?: number | null
): Point2D {
  const headerHeight = node.headerHeight ?? CANVAS_GEOMETRY_TOKENS.nodeHeaderHeight;
  const rowHeight = node.rowHeight ?? CANVAS_GEOMETRY_TOKENS.fieldRowHeight;
  const x = node.x + node.width;
  const y =
    columnIndex !== undefined && columnIndex !== null && columnIndex >= 0
      ? node.y + headerHeight + columnIndex * rowHeight + rowHeight / 2
      : node.y + headerHeight / 2;
  return { x, y };
}

/**
 * Computes the left-edge incoming target entry `(x2, y2)` for a target node or column row (`04 §6.4`):
 * - `x2 = targetNode.x` (the arrowhead tip touches `x2`; the Bézier curve stops at `x2 - 10`)
 * - Column row center: `y2 = targetNode.y + 22 + colIndex * 16 + 8`
 * - Node header center: `y2 = targetNode.y + 11` (or `headerHeight / 2`)
 */
export function computeTargetPortAnchor(
  node: PortNodeBox,
  columnIndex?: number | null
): Point2D {
  const headerHeight = node.headerHeight ?? CANVAS_GEOMETRY_TOKENS.nodeHeaderHeight;
  const rowHeight = node.rowHeight ?? CANVAS_GEOMETRY_TOKENS.fieldRowHeight;
  const x = node.x;
  const y =
    columnIndex !== undefined && columnIndex !== null && columnIndex >= 0
      ? node.y + headerHeight + columnIndex * rowHeight + rowHeight / 2
      : node.y + headerHeight / 2;
  return { x, y };
}

export interface CubicBezierEdgeGeometry {
  /** Exact SVG path `d` string: `M x1,y1 C ... S ...` */
  d: string;
  /** Source port X (`sourceNode.x + sourceNode.width`) */
  x1: number;
  /** Source port Y */
  y1: number;
  /** Target entry X (`targetNode.x`, tip of `10x7` arrowhead) */
  x2: number;
  /** Target entry Y */
  y2: number;
  /** Curve end X (`x2 - 10`, base of `10x7` arrowhead) */
  endX: number;
  /** Midpoint X (`(x1 + endX) / 2`) where transform `<circle>` & join label sit */
  mx: number;
  /** Midpoint Y (`(y1 + y2) / 2`) */
  my: number;
  /** Primary horizontal control offset */
  dx: number;
  /** Midpoint tangent control offset */
  dx2: number;
  /** True when `endX < x1` (backward/self-looping edge) */
  isBackward: boolean;
}

function roundCoord(n: number): number {
  return Math.round(n * 100) / 100;
}

/**
 * Builds the exact SQLFlow `M...C...S...` smooth cubic Bézier path between `(x1, y1)` and `(x2, y2)` (`04 §6.4`):
 *
 * - Curve terminates at `endX = x2 - 10` (leaving `10px` for the `10x7` arrowhead polygon).
 * - Midpoint: `mx = (x1 + endX) / 2`, `my = (y1 + y2) / 2`.
 * - Forward (`endX >= x1`): `dx = max(20, min(80, |endX - x1| / 2))`, `dx2 = dx / 2`.
 * - Backward (`endX < x1`): loops smoothly outward with `dx = max(60, min(120, |endX - x1| / 2 + 40))`, `dx2 = -dx / 2`
 *   so `C (x1 + dx),y1 (mx - dx2),my mx,my S (endX - dx),y2 endX,y2` maintains C1 continuity across `(mx, my)`.
 */
export function buildCubicBezierEdgePath(
  x1Input: number,
  y1Input: number,
  x2Input: number,
  y2Input: number
): CubicBezierEdgeGeometry {
  const x1 = roundCoord(x1Input);
  const y1 = roundCoord(y1Input);
  const x2 = roundCoord(x2Input);
  const y2 = roundCoord(y2Input);
  const endX = roundCoord(x2 - ARROWHEAD_LENGTH);

  const mx = roundCoord((x1 + endX) / 2);
  const my = roundCoord((y1 + y2) / 2);
  const spanX = endX - x1;
  const isBackward = spanX < 0;

  let dx: number;
  let dx2: number;
  if (!isBackward) {
    dx = roundCoord(Math.max(20, Math.min(80, Math.abs(spanX) / 2)));
    dx2 = roundCoord(dx / 2);
  } else {
    dx = roundCoord(Math.max(60, Math.min(120, Math.abs(spanX) / 2 + 40)));
    dx2 = roundCoord(-dx / 2);
  }

  const c1x = roundCoord(x1 + dx);
  const c2x = roundCoord(mx - dx2);
  const s2x = roundCoord(endX - dx);

  const d = `M ${x1},${y1} C ${c1x},${y1} ${c2x},${my} ${mx},${my} S ${s2x},${y2} ${endX},${y2}`;

  return {
    d,
    x1,
    y1,
    x2,
    y2,
    endX,
    mx,
    my,
    dx,
    dx2,
    isBackward
  };
}

/**
 * Builds the exact `10 × 7` SVG `<polygon points="...">` string anchored at `(x2, y2)` (`04 §6.4 #3`):
 * `points="${x2},${y2} ${x2 - 10},${y2 - 3.5} ${x2 - 10},${y2 + 3.5}"`
 */
export function buildArrowheadPoints(x2Input: number, y2Input: number): string {
  const x2 = roundCoord(x2Input);
  const y2 = roundCoord(y2Input);
  const baseX = roundCoord(x2 - ARROWHEAD_LENGTH);
  const topY = roundCoord(y2 - ARROWHEAD_HALF_HEIGHT);
  const bottomY = roundCoord(y2 + ARROWHEAD_HALF_HEIGHT);
  return `${x2},${y2} ${baseX},${topY} ${baseX},${bottomY}`;
}

/**
 * Builds the equivalent `10 × 7` closed SVG `<path d="...">` arrowhead string anchored at `(x2, y2)`.
 */
export function buildArrowheadPath(x2Input: number, y2Input: number): string {
  const x2 = roundCoord(x2Input);
  const y2 = roundCoord(y2Input);
  const baseX = roundCoord(x2 - ARROWHEAD_LENGTH);
  const topY = roundCoord(y2 - ARROWHEAD_HALF_HEIGHT);
  const bottomY = roundCoord(y2 + ARROWHEAD_HALF_HEIGHT);
  return `M ${x2},${y2} L ${baseX},${topY} L ${baseX},${bottomY} Z`;
}

export interface EdgeVisualStyleOptions {
  highlighted?: boolean;
  treatAggregateAsDirect?: boolean;
}

export interface EdgeVisualStyle {
  stroke: string;
  strokeWidth: number;
  strokeDasharray?: string;
  isDashed: boolean;
  isJoin: boolean;
  arrowFill: string;
}

/**
 * Maps `relationship.type` (`fdd`, `fdr`, `frd`, `fddi`, `join`, `er`, `call`) to exact SVG stroke attributes (`04 §6.4 #2`).
 */
export function resolveEdgeVisualStyle(
  relationType: string | undefined,
  options?: EdgeVisualStyleOptions
): EdgeVisualStyle {
  const highlighted = Boolean(options?.highlighted);
  const normalized = (relationType ?? "fdd").toLowerCase();

  if (normalized === "join") {
    const stroke = highlighted
      ? SURFACE_AND_EDGE_TOKENS.edgeJoinHighlightStroke
      : SURFACE_AND_EDGE_TOKENS.edgeJoinDefaultStroke;
    return {
      stroke,
      strokeWidth: highlighted
        ? SURFACE_AND_EDGE_TOKENS.edgeHighlightWidth
        : SURFACE_AND_EDGE_TOKENS.edgeDefaultWidth,
      isDashed: false,
      isJoin: true,
      arrowFill: stroke
    };
  }

  const isIndirect =
    normalized === "fdr" ||
    normalized === "frd" ||
    (normalized === "fddi" && !options?.treatAggregateAsDirect);

  const stroke = highlighted
    ? SURFACE_AND_EDGE_TOKENS.edgeHighlightStroke
    : SURFACE_AND_EDGE_TOKENS.edgeDefaultStroke;

  return {
    stroke,
    strokeWidth: highlighted
      ? SURFACE_AND_EDGE_TOKENS.edgeHighlightWidth
      : SURFACE_AND_EDGE_TOKENS.edgeDefaultWidth,
    strokeDasharray: isIndirect ? SURFACE_AND_EDGE_TOKENS.edgeDashArray : undefined,
    isDashed: isIndirect,
    isJoin: false,
    arrowFill: stroke
  };
}
