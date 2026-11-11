/**
 * `@sqlflow/widget-sdk` (`3.6.16` parity) — Public ESM / TypeScript Entry Point.
 */

import "./styles/widget.css";
import { createWidgetInstance } from "./sdk/createWidgetInstance.js";
import {
  WIDGET_BUILD_DATE,
  WIDGET_RELEASE,
  WIDGET_VERSION,
  type SQLFlowGlobalSDK,
  type SQLFlowInitOptions,
  type SQLFlowWidgetInstance
} from "./types.js";

export async function init(options: SQLFlowInitOptions): Promise<SQLFlowWidgetInstance> {
  return createWidgetInstance(options);
}

export const SQLFlow: SQLFlowGlobalSDK = {
  version: WIDGET_VERSION,
  release: WIDGET_RELEASE,
  buildDate: WIDGET_BUILD_DATE,
  init,
  create: init
};

SQLFlow.default = SQLFlow;
SQLFlow.SQLFlow = SQLFlow;

if (typeof window !== "undefined") {
  (window as unknown as { SQLFlow?: SQLFlowGlobalSDK }).SQLFlow = SQLFlow;
}

export default SQLFlow;

export * from "./types.js";
export type {
  ApiEnvelope,
  CallGraphPayload,
  CanvasNode,
  Coordinate,
  DataFlow,
  DependencyGraphV1,
  JobSummary,
  JoinAnalysisGraph,
  LayoutRankDir,
  LineageError,
  LineageSettings,
  OnFieldClickPayload,
  OnTableNameClickPayload,
  SqlflowJSON
} from "@sqlflow/ui-core";
export { WIDGET_I18N } from "./i18n.js";
export {
  WidgetStore,
  resolveVisibilityFromInit,
  normalizeTheme,
  normalizeLang,
  normalizeOrientation
} from "./sdk/widgetStore.js";
export {
  normalizePrecomputedLineageInput,
  findNodeByNameOrId,
  findColumnByTableAndName,
  searchNodesInModel,
  normalizeStreamTarget,
  sliceCanvasModelDirection,
  buildWidgetTreeNodes,
  serializeModelToXml,
  serializeModelToCsv15,
  buildMermaidErLiveUrl,
  exportDomElementToPngDataUrl
} from "./utils/graphOperations.js";
