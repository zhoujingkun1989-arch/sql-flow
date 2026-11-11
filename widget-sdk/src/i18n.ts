/**
 * Bilingual (`en` / `cn`) Internationalization Dictionary for `@sqlflow/widget-sdk`.
 */

import type { WidgetLang } from "./types.js";

export interface WidgetTranslations {
  visualize: string;
  visualizing: string;
  viewColumn: string;
  viewTable: string;
  viewEr: string;
  viewJoin: string;
  viewCallGraph: string;
  dialect: string;
  popularDialects: string;
  allDialects: string;
  sampleSql: string;
  loadSample: string;
  formatSql: string;
  clearSql: string;
  orientationLr: string;
  orientationTb: string;
  settings: string;
  exportMenu: string;
  exportPng: string;
  exportJson: string;
  exportXml: string;
  exportCsv: string;
  directDataflow: string;
  indirectDataflow: string;
  aggregateArgs: string;
  showIntermediateRecordset: string;
  showFunction: string;
  showConstant: string;
  showTransform: string;
  defaultDistance: string;
  searchPlaceholder: string;
  clearHighlight: string;
  resetSlice: string;
  emptyLineage: string;
  emptyEr: string;
  emptyJoin: string;
  emptyCallGraph: string;
  noErRelationsBanner: string;
  openMermaidLive: string;
  joinDiagnostics: string;
  noDiagnostics: string;
  sqlDrawerTitle: string;
  close: string;
  contextUpstream: string;
  contextDownstream: string;
  contextShowFull: string;
  contextHighlight: string;
  contextShowSql: string;
  contextDownloadPng: string;
  contextDownloadJson: string;
  summaryWarning: string;
  renderFullGraph: string;
  objectsLabel: string;
  tablesCount: string;
  columnsCount: string;
  edgesCount: string;
}

export const WIDGET_I18N: Readonly<Record<WidgetLang, WidgetTranslations>> = {
  en: {
    visualize: "Visualize",
    visualizing: "Analyzing...",
    viewColumn: "Column Lineage",
    viewTable: "Table Lineage",
    viewEr: "ER Diagram",
    viewJoin: "Join Analysis",
    viewCallGraph: "Call Graph",
    dialect: "Dialect",
    popularDialects: "Popular",
    allDialects: "All dialects",
    sampleSql: "Sample SQL",
    loadSample: "Load Dialect Sample",
    formatSql: "Format",
    clearSql: "Clear",
    orientationLr: "LR (Left → Right)",
    orientationTb: "TB (Top → Bottom)",
    settings: "Settings",
    exportMenu: "Export",
    exportPng: "Export PNG Image",
    exportJson: "Export Lineage JSON",
    exportXml: "Export <dlineage> XML",
    exportCsv: "Export 15-Col CSV",
    directDataflow: "Direct dataflow (fdd)",
    indirectDataflow: "Indirect dataflow (fdr)",
    aggregateArgs: "Args in COUNT/aggregates",
    showIntermediateRecordset: "Show intermediate recordset",
    showFunction: "Show functions",
    showConstant: "Show constants",
    showTransform: "Show transforms f(x)",
    defaultDistance: "Default hop distance",
    searchPlaceholder: "Search table or column...",
    clearHighlight: "Clear Pin",
    resetSlice: "Full Graph",
    emptyLineage: "No lineage graph loaded. Enter SQL and click Visualize or pass data via SDK.",
    emptyEr: "No ER diagram loaded. Run visualizeER() with CREATE TABLE / FOREIGN KEY DDL.",
    emptyJoin: "No Join Analysis graph loaded. Run visualizeJoin() on a multi-table SELECT query.",
    emptyCallGraph: "No Call Graph loaded. Run visualizeCGJSON() or analyze stored procedures.",
    noErRelationsBanner:
      "ER relationships require PRIMARY KEY / FOREIGN KEY constraints in DDL.",
    openMermaidLive: "Open in Mermaid Live",
    joinDiagnostics: "Join Diagnostics",
    noDiagnostics: "No join risk diagnostics detected",
    sqlDrawerTitle: "SQL Source & Coordinate Inspector",
    close: "Close",
    contextUpstream: "Trace Upstream",
    contextDownstream: "Trace Downstream",
    contextShowFull: "Reset Full Graph",
    contextHighlight: "Pin Highlight",
    contextShowSql: "Inspect SQL Coordinates",
    contextDownloadPng: "Download as PNG",
    contextDownloadJson: "Download as JSON",
    summaryWarning:
      "Graph exceeds 200 database objects. Summary mode protects browser responsiveness.",
    renderFullGraph: "Render Full Graph",
    objectsLabel: "objects",
    tablesCount: "Nodes",
    columnsCount: "Columns",
    edgesCount: "Edges"
  },
  cn: {
    visualize: "生成血缘",
    visualizing: "解析中...",
    viewColumn: "字段血缘",
    viewTable: "表级血缘",
    viewEr: "ER 实体图",
    viewJoin: "连接分析",
    viewCallGraph: "调用关系",
    dialect: "数据库方言",
    popularDialects: "常用方言",
    allDialects: "全部方言",
    sampleSql: "示例 SQL",
    loadSample: "加载当前方言示例",
    formatSql: "格式化",
    clearSql: "清空",
    orientationLr: "从左到右 (LR)",
    orientationTb: "从上到下 (TB)",
    settings: "血缘设置",
    exportMenu: "导出",
    exportPng: "导出 PNG 图片",
    exportJson: "导出 JSON 数据",
    exportXml: "导出 <dlineage> XML",
    exportCsv: "导出 15 列血缘 CSV",
    directDataflow: "直接数据流 (fdd)",
    indirectDataflow: "间接影响流 (fdr)",
    aggregateArgs: "聚合函数参数流向",
    showIntermediateRecordset: "显示中间结果集",
    showFunction: "显示函数节点",
    showConstant: "显示常量表",
    showTransform: "显示转换表达式 f(x)",
    defaultDistance: "默认追踪层级",
    searchPlaceholder: "搜索表名或字段名...",
    clearHighlight: "清除高亮",
    resetSlice: "完整血缘",
    emptyLineage: "暂无血缘关系图，请输入 SQL 点击“生成血缘”或通过 SDK 传入数据。",
    emptyEr: "暂无 ER 实体关系图，请通过 DDL 语句调用 visualizeER()。",
    emptyJoin: "暂无表连接分析图，请对多表 JOIN 查询执行 visualizeJoin()。",
    emptyCallGraph: "暂无存储过程调用图，请调用 visualizeCGJSON()。",
    noErRelationsBanner: "提示：ER 实体连线需要 DDL 中包含 PRIMARY KEY / FOREIGN KEY 约束。",
    openMermaidLive: "在 Mermaid 编辑器中打开",
    joinDiagnostics: "连接风险诊断",
    noDiagnostics: "未检测到连接风险项",
    sqlDrawerTitle: "SQL 源码与行列坐标定位",
    close: "关闭",
    contextUpstream: "追踪上游血缘",
    contextDownstream: "追踪下游血缘",
    contextShowFull: "恢复完整血缘图",
    contextHighlight: "固定路径高亮",
    contextShowSql: "查看对应 SQL 语句",
    contextDownloadPng: "下载 PNG 图片",
    contextDownloadJson: "下载 JSON 文件",
    summaryWarning: "当前血缘对象超过 200 个，已启用概览保护模式。",
    renderFullGraph: "展开完整血缘图",
    objectsLabel: "个对象",
    tablesCount: "节点数",
    columnsCount: "字段数",
    edgesCount: "关系数"
  }
};
