/**
 * Standalone UMD Entry Point (`sqlflow.widget.3.6.16.js` + `sqlflow.widget.3.6.16.css`).
 *
 * Uses `export default SQLFlow` with `SQLFlow.default = SQLFlow` and `SQLFlow.SQLFlow = SQLFlow`
 * so `window.SQLFlow.init(...)`, CommonJS `require('./sqlflow.widget.3.6.16.js').init(...)`,
 * and interop `require(...)['default'].init(...)` all work directly (`OBL-111`).
 */

import "./styles/widget.css";
import SQLFlow from "./index.js";
import type { SQLFlowGlobalSDK } from "./types.js";

if (typeof globalThis !== "undefined") {
  (globalThis as unknown as { SQLFlow?: SQLFlowGlobalSDK }).SQLFlow = SQLFlow;
}
if (typeof window !== "undefined") {
  (window as unknown as { SQLFlow?: SQLFlowGlobalSDK }).SQLFlow = SQLFlow;
}

export default SQLFlow;
