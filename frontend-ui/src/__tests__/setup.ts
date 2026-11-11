import "@testing-library/jest-dom/vitest";

function createMemoryStorage(): Storage {
  const store = new Map<string, string>();
  return {
    get length(): number {
      return store.size;
    },
    clear(): void {
      store.clear();
    },
    getItem(key: string): string | null {
      return store.has(String(key)) ? (store.get(String(key)) ?? null) : null;
    },
    key(index: number): string | null {
      return Array.from(store.keys())[index] ?? null;
    },
    removeItem(key: string): void {
      store.delete(String(key));
    },
    setItem(key: string, value: string): void {
      store.set(String(key), String(value));
    }
  };
}

try {
  if (
    typeof globalThis.localStorage === "undefined" ||
    typeof globalThis.localStorage?.clear !== "function"
  ) {
    const memLocal = createMemoryStorage();
    Object.defineProperty(globalThis, "localStorage", {
      value: memLocal,
      configurable: true,
      writable: true
    });
    if (typeof window !== "undefined") {
      Object.defineProperty(window, "localStorage", {
        value: memLocal,
        configurable: true,
        writable: true
      });
    }
  }
} catch {
  const memLocal = createMemoryStorage();
  Object.defineProperty(globalThis, "localStorage", {
    value: memLocal,
    configurable: true,
    writable: true
  });
}

class MockResizeObserver {
  public observe(): void {}
  public unobserve(): void {}
  public disconnect(): void {}
}

if (typeof globalThis.ResizeObserver === "undefined") {
  globalThis.ResizeObserver = MockResizeObserver as unknown as typeof ResizeObserver;
}

if (typeof globalThis.DOMMatrixReadOnly === "undefined") {
  class MockDOMMatrixReadOnly {
    public m22 = 1;
    public a = 1;
    public d = 1;
  }
  globalThis.DOMMatrixReadOnly =
    MockDOMMatrixReadOnly as unknown as typeof DOMMatrixReadOnly;
}

if (typeof Element !== "undefined" && !Element.prototype.scrollIntoView) {
  Element.prototype.scrollIntoView = () => undefined;
}

if (typeof URL !== "undefined") {
  if (typeof URL.createObjectURL !== "function") {
    URL.createObjectURL = () => "blob:sqlflow-mock-url";
  }
  if (typeof URL.revokeObjectURL !== "function") {
    URL.revokeObjectURL = () => undefined;
  }
}
