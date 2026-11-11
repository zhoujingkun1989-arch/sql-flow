import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import SQLFlow, {
  WIDGET_BUILD_DATE,
  WIDGET_RELEASE,
  WIDGET_VERSION,
  init,
  type SQLFlowWidgetInstance
} from "../index.js";

describe("SQLFlow Widget SDK — Lifecycle, Sandbox Isolation & Init Options", () => {
  const activeWidgets: SQLFlowWidgetInstance[] = [];
  let hostEl: HTMLDivElement;

  beforeEach(() => {
    document.body.innerHTML = "";
    hostEl = document.createElement("div");
    hostEl.id = "sqlflow-test-host";
    hostEl.style.width = "960px";
    hostEl.style.height = "640px";
    document.body.appendChild(hostEl);
  });

  afterEach(() => {
    while (activeWidgets.length > 0) {
      const w = activeWidgets.pop();
      w?.destroy();
    }
    document.body.innerHTML = "";
    vi.restoreAllMocks();
  });

  it("exposes 3.6.16 global metadata and attaches window.SQLFlow", () => {
    expect(SQLFlow.version).toBe("3.6.16");
    expect(SQLFlow.version).toBe(WIDGET_VERSION);
    expect(SQLFlow.release).toBe(WIDGET_RELEASE);
    expect(SQLFlow.buildDate).toBe(WIDGET_BUILD_DATE);
    expect(typeof SQLFlow.init).toBe("function");
    expect(SQLFlow.init).toBe(init);
    expect((window as unknown as { SQLFlow?: unknown }).SQLFlow).toBe(SQLFlow);
  });

  it("validates container, width, and height options strictly (04 §11.3)", async () => {
    await expect(
      // @ts-expect-error testing missing options
      SQLFlow.init(null)
    ).rejects.toThrow(/options/i);

    await expect(
      SQLFlow.init({ container: "#non-existent-element" })
    ).rejects.toThrow(/not found/i);

    await expect(
      SQLFlow.init({ container: hostEl, width: 0 })
    ).rejects.toThrow("width is invalid");

    await expect(
      SQLFlow.init({ container: hostEl, width: -250 })
    ).rejects.toThrow("width is invalid");

    await expect(
      SQLFlow.init({ container: hostEl, width: "" })
    ).rejects.toThrow("width is invalid");

    await expect(
      SQLFlow.init({ container: hostEl, height: 0 })
    ).rejects.toThrow("height is invalid");

    await expect(
      SQLFlow.init({ container: hostEl, height: "0px" })
    ).rejects.toThrow("height is invalid");
  });

  it("mounts a style-isolated same-origin <iframe> sandbox by selector or HTMLElement and tears down cleanly on destroy()", async () => {
    const observeSpy = vi.fn();
    const disconnectSpy = vi.fn();
    const OriginalRO = globalThis.ResizeObserver;
    class MockResizeObserver {
      private cb: ResizeObserverCallback;
      constructor(cb: ResizeObserverCallback) {
        this.cb = cb;
      }
      observe(target: Element) {
        observeSpy(target);
        this.cb(
          [{ contentRect: { width: 1024, height: 700 } } as unknown as ResizeObserverEntry],
          this as unknown as ResizeObserver
        );
      }
      unobserve() {}
      disconnect() {
        disconnectSpy();
      }
    }
    globalThis.ResizeObserver = MockResizeObserver as unknown as typeof ResizeObserver;

    try {
      let readyFired = false;
      const widget = await SQLFlow.init({
        container: "#sqlflow-test-host",
        width: 900,
        height: 550,
        onReady: (payload) => {
          readyFired = true;
          expect(payload.version).toBe("3.6.16");
          expect(payload.container).toBe(hostEl);
        }
      });
      activeWidgets.push(widget);

      expect(readyFired).toBe(true);
      expect(observeSpy).toHaveBeenCalledWith(hostEl);
      expect(widget.isDestroyed()).toBe(false);
      expect(widget.getContainer()).toBe(hostEl);

      const iframe = widget.getIframe();
      expect(iframe).not.toBeNull();
      expect(iframe?.tagName).toBe("IFRAME");
      expect(iframe?.style.width).toBe("900px");
      expect(iframe?.style.height).toBe("550px");
      expect(hostEl.contains(iframe)).toBe(true);

      // Verify style injection & React shell inside iframe.contentDocument
      const iframeDoc = iframe!.contentDocument!;
      const injectedStyle = iframeDoc.querySelector('style[data-sqlflow-widget-styles="3.6.16"]');
      expect(injectedStyle).not.toBeNull();
      expect(injectedStyle?.textContent).toContain(".sqlflow-widget-root");

      const shell = iframeDoc.querySelector('[data-testid="sqlflow-widget-shell"]');
      expect(shell).not.toBeNull();
      expect((iframe!.contentWindow as unknown as { SQLFlow?: unknown }).SQLFlow).toBe(widget);

      // Destroy and confirm complete DOM + ResizeObserver cleanup
      widget.destroy();
      expect(widget.isDestroyed()).toBe(true);
      expect(disconnectSpy).toHaveBeenCalledTimes(1);
      expect(hostEl.querySelector("iframe")).toBeNull();
      expect(widget.getIframe()).toBeNull();

      // Calling destroy() a second time is a safe no-op
      expect(() => widget.destroy()).not.toThrow();
    } finally {
      globalThis.ResizeObserver = OriginalRO;
    }
  });

  it("uses MemoryRouter so internal view transitions never mutate host window.location or hash (OBL-112)", async () => {
    window.history.replaceState(null, "", "/host-app/dashboard#overview");
    const initialHref = window.location.href;
    const initialHash = window.location.hash;

    const widget = await SQLFlow.init({
      container: hostEl,
      toolbar: true
    });
    activeWidgets.push(widget);

    await widget.visualizeJson({
      mode: "global",
      sqlflow: {
        dbobjs: [{ id: "t1", name: "orders", type: "table", columns: [{ id: "c1", name: "id" }] }],
        relationships: []
      }
    });

    await widget.visualizeERJSON({
      mode: "global",
      sqlflow: {
        dbobjs: [{ id: "t1", name: "orders", type: "table", columns: [{ id: "c1", name: "id", primaryKey: true }] }],
        relationships: []
      }
    });

    await widget.visualizeJoinJSON({
      schemaVersion: "join-analysis.v1",
      queryBlocks: [{ queryBlockId: "qb1", label: "Main", kind: "main" }],
      nodes: [{ id: "n1", type: "table", data: { label: "orders" } }],
      edges: [],
      diagnostics: []
    });

    expect(window.location.href).toBe(initialHref);
    expect(window.location.hash).toBe(initialHash);
  });

  it("resolves defaults, visibility toggles, theme, and live en/cn i18n switching (OBL-113)", async () => {
    const widget = await SQLFlow.init({
      container: hostEl,
      dbvendor: "dbvsnowflake",
      sqltext: "SELECT 1;",
      component: false,
      toolbar: true,
      editor: true,
      minimap: true,
      search: true,
      settings: true,
      export: true,
      sqlList: true
    });
    activeWidgets.push(widget);

    const opts = widget.getOptions();
    expect(opts.apiPrefix).toBe("/gspLive_backend");
    expect(opts.userId).toBe("gudu|0123456789");
    expect(opts.dbvendor).toBe("snowflake");
    expect(opts.sqltext).toBe("SELECT 1;");
    expect(opts.theme).toBe("light");
    expect(opts.lang).toBe("en");
    expect(opts.visibility).toEqual({
      sqlEditor: true,
      graphLocate: true,
      minimap: true,
      toolbar: true,
      settings: true,
      export: true,
      sqlList: true,
      scaleControls: true
    });

    const iframeDoc = widget.getIframe()!.contentDocument!;
    expect(iframeDoc.querySelector('[data-testid="widget-visualize-btn"]')?.textContent).toBe(
      "Visualize"
    );

    // Switch live to Simplified Chinese + Dark theme + hide editor
    widget.setOptions({
      lang: "cn",
      theme: "dark",
      editor: false,
      apiPrefix: "http://localhost:8081/api"
    });

    const updatedOpts = widget.getOptions();
    expect(updatedOpts.lang).toBe("cn");
    expect(updatedOpts.theme).toBe("dark");
    expect(updatedOpts.visibility.sqlEditor).toBe(false);
    expect(updatedOpts.apiPrefix).toBe("http://localhost:8081/api/gspLive_backend");

    const shell = iframeDoc.querySelector('[data-testid="sqlflow-widget-shell"]');
    expect(shell?.getAttribute("data-sqlflow-theme")).toBe("dark");
    expect(shell?.getAttribute("data-sqlflow-lang")).toBe("cn");
    expect(iframeDoc.querySelector('[data-testid="widget-visualize-btn"]')?.textContent).toBe(
      "生成血缘"
    );
    expect(iframeDoc.querySelector('[data-testid="sqlflow-widget-editor-pane"]')).toBeNull();
  });

  it("supports multiple isolated side-by-side widget instances on one page (Demo 8)", async () => {
    const hostA = document.createElement("div");
    const hostB = document.createElement("div");
    document.body.append(hostA, hostB);

    const widgetA = await SQLFlow.init({
      container: hostA,
      theme: "light",
      dbvendor: "oracle",
      orientation: "LR"
    });
    const widgetB = await SQLFlow.init({
      container: hostB,
      theme: "dark",
      dbvendor: "postgresql",
      orientation: "TB"
    });
    activeWidgets.push(widgetA, widgetB);

    await widgetA.visualizeJson({
      mode: "global",
      sqlflow: {
        dbobjs: [{ id: "a_tbl", name: "alpha_orders", type: "table", columns: [{ id: "ac1", name: "id" }] }],
        relationships: []
      }
    });
    await widgetB.visualizeJson({
      mode: "global",
      sqlflow: {
        dbobjs: [{ id: "b_tbl", name: "beta_ledger", type: "table", columns: [{ id: "bc1", name: "amount" }] }],
        relationships: []
      }
    });

    expect(widgetA.getIframe()).not.toBe(widgetB.getIframe());
    expect(widgetA.vendor.get()).toBe("oracle");
    expect(widgetB.vendor.get()).toBe("postgresql");
    expect(widgetA.highlightTable("alpha_orders")).toBe(true);
    expect(widgetA.highlightTable("beta_ledger")).toBe(false);
    expect(widgetB.highlightTable("beta_ledger")).toBe(true);
    expect(widgetB.highlightTable("alpha_orders")).toBe(false);
  });
});
