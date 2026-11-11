import { execSync } from "node:child_process";
import { existsSync, readFileSync, statSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import vm from "node:vm";
import { JSDOM } from "jsdom";
import { beforeAll, describe, expect, it } from "vitest";

const __dirname = dirname(fileURLToPath(import.meta.url));
const pkgRoot = resolve(__dirname, "../..");
const distDir = resolve(pkgRoot, "dist");

describe("Built UMD Bundle (sqlflow.widget.3.6.16.{js,css}) & All 16 Official Demos (OBL-111, OBL-118..OBL-120)", () => {
  beforeAll(() => {
    const umdPath = resolve(distDir, "sqlflow.widget.3.6.16.js");
    const demo16Path = resolve(distDir, "16/index.html");
    if (!existsSync(umdPath) || !existsSync(demo16Path)) {
      execSync("pnpm build", { cwd: pkgRoot, stdio: "inherit" });
    }
  }, 60_000);

  it("emits sqlflow.widget.3.6.16.js, sqlflow.widget.3.6.16.css, index.js, and index.html in dist/", () => {
    const umdJs = resolve(distDir, "sqlflow.widget.3.6.16.js");
    const umdCss = resolve(distDir, "sqlflow.widget.3.6.16.css");
    const esmJs = resolve(distDir, "index.js");
    const portalHtml = resolve(distDir, "index.html");

    expect(existsSync(umdJs)).toBe(true);
    expect(statSync(umdJs).size).toBeGreaterThan(50_000);

    expect(existsSync(umdCss)).toBe(true);
    const cssText = readFileSync(umdCss, "utf-8");
    expect(cssText).toContain(".sqlflow-widget-root");
    expect(cssText).toMatch(/\[data-sqlflow-theme=["']?dark["']?\]/);
    expect(cssText).toContain(".sf-toolbar");
    expect(cssText).toContain(".sf-er-card");

    expect(existsSync(esmJs)).toBe(true);
    expect(statSync(esmJs).size).toBeGreaterThan(50_000);

    expect(existsSync(portalHtml)).toBe(true);
    const portalText = readFileSync(portalHtml, "utf-8");
    for (let i = 1; i <= 16; i++) {
      expect(portalText).toContain(`./${i}/index.html`);
    }
  });

  it("evaluates UMD sqlflow.widget.3.6.16.js in both browser <script> global mode (window.SQLFlow) and CommonJS mode", async () => {
    const umdCode = readFileSync(resolve(distDir, "sqlflow.widget.3.6.16.js"), "utf-8");
    expect(umdCode).not.toContain("process.env.NODE_ENV");

    // 1. Browser global (<script src="sqlflow.widget.3.6.16.js">) evaluation inside fresh JSDOM
    const dom = new JSDOM('<!DOCTYPE html><html><body><div id="umd-host" style="width:800px;height:500px"></div></body></html>', {
      url: "http://localhost:8165/widget/2/index.html",
      runScripts: "outside-only"
    });
    dom.window.eval(umdCode);

    const winSQLFlow = (dom.window as unknown as {
      SQLFlow?: {
        version: string;
        init: (opts: Record<string, unknown>) => Promise<{
          visualizeJson: (j: unknown) => Promise<void>;
          getIframe: () => HTMLIFrameElement | null;
          destroy: () => void;
        }>;
      };
    }).SQLFlow;

    expect(winSQLFlow).toBeDefined();
    expect(winSQLFlow?.version).toBe("3.6.16");
    expect(typeof winSQLFlow?.init).toBe("function");

    const widget = await winSQLFlow!.init({ container: "#umd-host" });
    await widget.visualizeJson({
      mode: "global",
      sqlflow: {
        dbobjs: [{ id: "u1", name: "umd_table", type: "table", columns: [{ id: "uc1", name: "col1" }] }],
        relationships: []
      }
    });
    const iframeDoc = widget.getIframe()?.contentDocument;
    expect(iframeDoc?.querySelector('[data-node-id="u1"]')).not.toBeNull();
    widget.destroy();
    dom.window.close();

    // 2. CommonJS (`module.exports`) compatibility
    const cjsModule: { exports: Record<string, unknown> } = { exports: {} };
    const sandbox = vm.createContext({
      module: cjsModule,
      exports: cjsModule.exports,
      globalThis: {},
      TextEncoder,
      TextDecoder,
      URL,
      URLSearchParams,
      setTimeout,
      clearTimeout
    });
    vm.runInContext(umdCode, sandbox);
    expect(cjsModule.exports.version).toBe("3.6.16");
    expect(typeof cjsModule.exports.init).toBe("function");
  });

  it("packages all 16 official demos (dist/1/index.html .. dist/16/index.html) with valid HTML, UMD script tags, and interactive JS", () => {
    const expectedKeywords: Record<number, RegExp> = {
      1: /visualizeSQL/,
      2: /visualizeJson/,
      3: /columnClick/,
      4: /zoomIn/,
      5: /visualizeER/,
      6: /tableLevel|visualizeTableLevel/,
      7: /highlightColumn/,
      8: /sqlflow-left[\s\S]*sqlflow-right/,
      9: /visualizeJob/,
      10: /upstream[\s\S]*downstream/,
      11: /visualizeDependencyGraph/,
      12: /setOptions/,
      13: /240|bypassSummaryGate/,
      14: /visualizeJoin/,
      15: /lang/,
      16: /destroy/
    };

    for (let demoNum = 1; demoNum <= 16; demoNum++) {
      const htmlPath = resolve(distDir, `${demoNum}/index.html`);
      const jsPath = resolve(distDir, `${demoNum}/index.js`);

      expect(existsSync(htmlPath), `Demo ${demoNum} index.html missing`).toBe(true);
      expect(existsSync(jsPath), `Demo ${demoNum} index.js missing`).toBe(true);

      const html = readFileSync(htmlPath, "utf-8");
      const js = readFileSync(jsPath, "utf-8");

      expect(html).toContain("../sqlflow.widget.3.6.16.js");
      expect(html).toContain("../sqlflow.widget.3.6.16.css");
      expect(html).toContain("./index.js");
      expect(js).toContain("SQLFlow.init");
      expect(`${html}\n${js}`).toMatch(expectedKeywords[demoNum]!);
    }

    // Also verify auxiliary shipped widget pages (`share`, `login`, `upstream-json`)
    expect(existsSync(resolve(distDir, "share/index.html"))).toBe(true);
    expect(existsSync(resolve(distDir, "login/index.html"))).toBe(true);
    expect(existsSync(resolve(distDir, "upstream-json/index.html"))).toBe(true);
    expect(existsSync(resolve(distDir, "fixtures/shared-demo-data.js"))).toBe(true);
  });
});
