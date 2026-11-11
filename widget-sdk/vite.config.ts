import { copyFileSync, cpSync, existsSync, mkdirSync, rmSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import react from "@vitejs/plugin-react";
import { build as viteBuild, defineConfig, type Plugin } from "vite";

const __dirname = dirname(fileURLToPath(import.meta.url));

function widgetPackagingPlugin(): Plugin {
  let hasRunPostBuild = false;

  return {
    name: "sqlflow-widget-packaging",
    apply: "build",
    async closeBundle() {
      if (hasRunPostBuild) {
        return;
      }
      hasRunPostBuild = true;

      const distDir = resolve(__dirname, "dist");
      mkdirSync(distDir, { recursive: true });

      // 1. Ensure canonical `sqlflow.widget.3.6.16.css` exists in `dist/`
      const targetCss = resolve(distDir, "sqlflow.widget.3.6.16.css");
      const defaultViteCss = resolve(distDir, "style.css");
      const srcCss = resolve(__dirname, "src/styles/widget.css");
      if (existsSync(defaultViteCss) && !existsSync(targetCss)) {
        copyFileSync(defaultViteCss, targetCss);
        rmSync(defaultViteCss, { force: true });
      } else if (!existsSync(targetCss) && existsSync(srcCss)) {
        copyFileSync(srcCss, targetCss);
      }
      if (existsSync(defaultViteCss) && existsSync(targetCss)) {
        rmSync(defaultViteCss, { force: true });
      }

      // 2. Emit standalone ES module entry `dist/index.js` alongside UMD `sqlflow.widget.3.6.16.js`
      await viteBuild({
        configFile: false,
        root: __dirname,
        logLevel: "warn",
        plugins: [react()],
        define: {
          "process.env.NODE_ENV": JSON.stringify("production")
        },
        build: {
          emptyOutDir: false,
          outDir: "dist",
          sourcemap: false,
          cssCodeSplit: false,
          lib: {
            entry: resolve(__dirname, "src/index.ts"),
            name: "SQLFlowESM",
            formats: ["es"],
            fileName: () => "index.js"
          },
          rollupOptions: {
            output: {
              inlineDynamicImports: true,
              assetFileNames: (assetInfo) => {
                if (assetInfo.name?.endsWith(".css")) {
                  return "sqlflow.widget.3.6.16.css";
                }
                return "assets/[name]-[hash][extname]";
              }
            }
          }
        }
      });

      // Clean up any duplicate `style.css` created by secondary pass
      if (existsSync(defaultViteCss)) {
        rmSync(defaultViteCss, { force: true });
      }

      // 3. Copy `demos/` (`index.html`, `1/index.html`..`16/index.html`, `fixtures/`, `share/`, `login/`, `upstream-json/`) into `dist/`
      const demosDir = resolve(__dirname, "demos");
      if (existsSync(demosDir)) {
        cpSync(demosDir, distDir, { recursive: true, force: true });
      }
    }
  };
}

export default defineConfig(({ command }) => ({
  root: __dirname,
  plugins: [
    react(),
    ...(command === "build" ? [widgetPackagingPlugin()] : [])
  ],
  define:
    command === "build"
      ? {
          "process.env.NODE_ENV": JSON.stringify("production"),
          global: "globalThis"
        }
      : undefined,
  build: {
    outDir: "dist",
    emptyOutDir: true,
    sourcemap: false,
    cssCodeSplit: false,
    chunkSizeWarningLimit: 3500,
    lib: {
      entry: resolve(__dirname, "src/umd-entry.ts"),
      name: "SQLFlow",
      formats: ["umd"],
      fileName: () => "sqlflow.widget.3.6.16.js"
    },
    rollupOptions: {
      output: {
        exports: "default",
        inlineDynamicImports: true,
        assetFileNames: (assetInfo) => {
          if (assetInfo.name?.endsWith(".css")) {
            return "sqlflow.widget.3.6.16.css";
          }
          return "assets/[name]-[hash][extname]";
        }
      }
    }
  },
  test: {
    environment: "jsdom",
    globals: true,
    css: true,
    include: ["src/__tests__/**/*.test.{ts,tsx}"]
  }
}));
