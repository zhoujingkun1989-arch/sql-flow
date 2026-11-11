import { describe, expect, it } from "vitest";
import {
  DBO_COLORS,
  EXPLICIT_DBO_COLOR_KEYS,
  LOCAL_STORAGE_KEYS,
  Z_INDEX,
  buildArrowheadPath,
  buildArrowheadPoints,
  buildCubicBezierEdgePath,
  computeNodeDimensions,
  computeSourcePortAnchor,
  computeTargetPortAnchor,
  estimateVerdana12Width,
  formatDbObjectDisplayTitle,
  get50CharWidth,
  getDboHeaderColor,
  insertFontProbe,
  resolveEdgeVisualStyle,
  truncateMiddle53
} from "../index.js";

describe("17-Entry dboColor Palette, Verdana 12px Width Estimator & Cubic Bézier Math", () => {
  it("defines all 16 explicit dboColor entries plus the 17th default_gray fallback", () => {
    expect(Object.keys(DBO_COLORS)).toHaveLength(17);
    expect(EXPLICIT_DBO_COLOR_KEYS).toHaveLength(16);

    expect(getDboHeaderColor("table")).toBe("#91c051");
    expect(getDboHeaderColor("view")).toBe("#419a5f");
    expect(getDboHeaderColor("resultset")).toBe("#d26b58");
    expect(getDboHeaderColor("select_list")).toBe("#d26b58");
    expect(getDboHeaderColor("insert-select")).toBe("#b24b78");
    expect(getDboHeaderColor("update-set")).toBe("#d29059");
    expect(getDboHeaderColor("merge-insert")).toBe("#893a89");
    expect(getDboHeaderColor("merge-update")).toBe("#e46b0a");
    expect(getDboHeaderColor("select_union")).toBe("#357e7e");
    expect(getDboHeaderColor("with_cte")).toBe("#ff6e97");
    expect(getDboHeaderColor("update-select")).toBe("#5583d8");
    expect(getDboHeaderColor("function")).toBe("#a0a0a0");
    expect(getDboHeaderColor("stage")).toBe("#6d30ea");
    expect(getDboHeaderColor("variable")).toBe("#00a4eb");
    expect(getDboHeaderColor("schema")).toBe("#1accb3");
    expect(getDboHeaderColor("unnest")).toBe("#d2d430");
    expect(getDboHeaderColor("procedure")).toBe("#dbdada");
    expect(getDboHeaderColor("unknown_custom_dbo")).toBe("#dbdada");
    expect(getDboHeaderColor("unknown_custom_dbo", { fallbackToTableGreen: true })).toBe(
      "#91c051"
    );

    expect(Z_INDEX.header).toBe(50);
    expect(Z_INDEX.summaryInterstitial).toBe(20);
    expect(Object.values(LOCAL_STORAGE_KEYS)).toEqual([
      "user",
      "sqlflow.actionsBar.v1",
      "sqlflow.tryThis.dismissed.v1",
      "joinAnalysisV2"
    ]);
  });

  it("estimates verdana 12px widths, truncates >53 char labels, and computes node geometry", () => {
    const probe = insertFontProbe();
    expect(probe).not.toBeNull();

    const narrowWidth = estimateVerdana12Width("iiiiii");
    const wideWidth = estimateVerdana12Width("WWWWWW");
    expect(wideWidth).toBeGreaterThan(narrowWidth * 2);

    const longIdent = "VERY_LONG_ENTERPRISE_DATABASE.ANALYTICS_SCHEMA.CUSTOMER_LIFETIME_REVENUE_FACT";
    const truncated = truncateMiddle53(longIdent);
    expect(truncated).toHaveLength(55);
    expect(truncated).toContain("...");
    expect(get50CharWidth(longIdent)).toBeGreaterThan(100);

    expect(
      formatDbObjectDisplayTitle({
        database: "DEFAULT",
        schema: "SCOTT",
        name: "EMP",
        type: "table"
      })
    ).toBe("SCOTT.EMP");

    const dims = computeNodeDimensions({
      title: "SCOTT.EMP",
      columnLabels: ["EMPNO", "ENAME", "SAL"]
    });
    expect(dims.width).toBeGreaterThanOrEqual(120);
    expect(dims.headerHeight).toBe(22);
    expect(dims.rowHeight).toBe(16);
    expect(dims.height).toBe(22 + 3 * 16 + 2);
  });

  it("builds exact M...C...S... cubic Bézier curves and 10x7 arrowheads for forward & backward edges", () => {
    const srcNode = { x: 40, y: 80, width: 150 };
    const tgtNode = { x: 320, y: 120, width: 160 };

    const srcAnchor = computeSourcePortAnchor(srcNode, 1);
    const tgtAnchor = computeTargetPortAnchor(tgtNode, 2);
    expect(srcAnchor).toEqual({ x: 190, y: 80 + 22 + 1 * 16 + 8 });
    expect(tgtAnchor).toEqual({ x: 320, y: 120 + 22 + 2 * 16 + 8 });

    const forward = buildCubicBezierEdgePath(
      srcAnchor.x,
      srcAnchor.y,
      tgtAnchor.x,
      tgtAnchor.y
    );
    expect(forward.isBackward).toBe(false);
    expect(forward.endX).toBe(310);
    expect(forward.d).toMatch(/^M 190,126 C [\d.-]+,126 [\d.-]+,154 250,154 S [\d.-]+,182 310,182$/);

    const backward = buildCubicBezierEdgePath(350, 100, 120, 200);
    expect(backward.isBackward).toBe(true);
    expect(backward.dx).toBeGreaterThanOrEqual(60);
    expect(backward.d).toMatch(/^M 350,100 C .* S .* 110,200$/);

    expect(buildArrowheadPoints(320, 182)).toBe("320,182 310,178.5 310,185.5");
    expect(buildArrowheadPath(320, 182)).toBe("M 320,182 L 310,178.5 L 310,185.5 Z");

    expect(resolveEdgeVisualStyle("fdd").isDashed).toBe(false);
    expect(resolveEdgeVisualStyle("fdr").strokeDasharray).toBe("4 2");
    expect(resolveEdgeVisualStyle("join").stroke).toBe("#a9ce76");
    expect(resolveEdgeVisualStyle("fdd", { highlighted: true }).stroke).toBe("#000000");
    expect(resolveEdgeVisualStyle("fdd", { highlighted: true }).strokeWidth).toBe(1.4);
  });
});
