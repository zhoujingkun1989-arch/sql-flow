import { resolve } from "node:path";
import { build, type Plugin } from "vite";
import { defineConfig } from "vitest/config";
import react from "@vitejs/plugin-react";

function joinFlowIslandBundlePlugin(): Plugin {
  let isBuildingIsland = false;
  return {
    name: "sqlflow-joinflow-island-bundle",
    apply: "build",
    async closeBundle() {
      if (isBuildingIsland || process.env.SQLFLOW_SKIP_ISLAND === "1") {
        return;
      }
      isBuildingIsland = true;
      process.env.SQLFLOW_SKIP_ISLAND = "1";
      try {
        await build({
          configFile: false,
          publicDir: false,
          plugins: [react()],
          define: {
            "process.env.NODE_ENV": JSON.stringify("production")
          },
          build: {
            outDir: resolve(__dirname, "dist"),
            emptyOutDir: false,
            cssCodeSplit: false,
            sourcemap: false,
            lib: {
              entry: resolve(__dirname, "src/joinflow/islandEntry.tsx"),
              name: "SQLFlowJoinIsland",
              formats: ["iife"],
              fileName: () => "joinflow-island.js"
            },
            rollupOptions: {
              output: {
                assetFileNames: (assetInfo) => {
                  if (assetInfo.name && assetInfo.name.endsWith(".css")) {
                    return "joinflow-island.css";
                  }
                  return "assets/[name]-[hash][extname]";
                }
              }
            }
          }
        });
      } finally {
        delete process.env.SQLFLOW_SKIP_ISLAND;
        isBuildingIsland = false;
      }
    }
  };
}

export default defineConfig({
  plugins: [react(), joinFlowIslandBundlePlugin()],
  resolve: {
    alias: {
      "@": resolve(__dirname, "src")
    }
  },
  server: {
    port: 3000,
    proxy: {
      "/api": {
        target: "http://127.0.0.1:8081",
        changeOrigin: true
      }
    }
  },
  build: {
    outDir: "dist",
    emptyOutDir: true,
    sourcemap: false
  },
  test: {
    environment: "jsdom",
    setupFiles: ["./src/__tests__/setup.ts"],
    globals: true,
    css: false,
    testTimeout: 20000
  }
});
