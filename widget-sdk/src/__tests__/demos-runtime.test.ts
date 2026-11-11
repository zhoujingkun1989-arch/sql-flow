import { readFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { JSDOM } from "jsdom";
import { describe, expect, it } from "vitest";

const __dirname = dirname(fileURLToPath(import.meta.url));
const distDir = resolve(__dirname, "../../dist");

async function bootBuiltDemoPage(demoId: number): Promise<JSDOM> {
  const html = readFileSync(resolve(distDir, `${demoId}/index.html`), "utf-8");
  const umdCode = readFileSync(resolve(distDir, "sqlflow.widget.3.6.16.js"), "utf-8");
  const fixturesCode = readFileSync(
    resolve(distDir, "fixtures/shared-demo-data.js"),
    "utf-8"
  );
  const demoJs = readFileSync(resolve(distDir, `${demoId}/index.js`), "utf-8");

  // Strip external <script> tags so we evaluate them deterministically in order
  const cleanHtml = html.replace(/<script\b[^>]*><\/script>/gi, "");
  const dom = new JSDOM(cleanHtml, {
    url: `http://localhost:8165/widget/${demoId}/index.html`,
    runScripts: "outside-only"
  });

  // Provide deterministic offline fetch rejector for live-backend demos so fallback executes immediately
  (dom.window as unknown as { fetch: typeof fetch }).fetch = async () => {
    throw new Error("Offline test mode");
  };

  dom.window.eval(umdCode);
  dom.window.eval(fixturesCode);
  dom.window.eval(demoJs);

  // Flush microtasks for async IIFE initialization inside demo index.js
  for (let i = 0; i < 12; i++) {
    await new Promise((r) => setTimeout(r, 15));
  }
  return dom;
}

describe("All 16 Official Demo Pages — Live End-to-End JSDOM Execution Against Built UMD Bundle", () => {
  it.each([1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16])(
    "boots Demo %i cleanly using built sqlflow.widget.3.6.16.js and mounts window.sqlflowWidget",
    async (demoNum) => {
      const dom = await bootBuiltDemoPage(demoNum);
      try {
        const win = dom.window as unknown as {
          sqlflowWidget?: {
            version: string;
            isDestroyed: () => boolean;
            getIframe: () => HTMLIFrameElement | null;
            destroy: () => void;
          };
          sqlflowLeft?: { destroy: () => void };
          sqlflowRight?: { destroy: () => void };
        };

        expect(win.sqlflowWidget, `Demo ${demoNum} did not expose window.sqlflowWidget`).toBeDefined();
        expect(win.sqlflowWidget?.version).toBe("3.6.16");
        expect(win.sqlflowWidget?.isDestroyed()).toBe(false);

        const iframe = win.sqlflowWidget?.getIframe();
        expect(iframe).not.toBeNull();
        const shell = iframe?.contentDocument?.querySelector(
          '[data-testid="sqlflow-widget-shell"]'
        );
        expect(shell).not.toBeNull();

        if (demoNum === 8) {
          expect(win.sqlflowLeft).toBeDefined();
          expect(win.sqlflowRight).toBeDefined();
          win.sqlflowRight?.destroy();
        }
        win.sqlflowWidget?.destroy();
      } finally {
        dom.window.close();
      }
    }
  );
});
