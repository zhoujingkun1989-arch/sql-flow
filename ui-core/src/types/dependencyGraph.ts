/**
 * Client-Side DependencyGraph v1.0 Schema for `SQLFlow.visualizeDependencyGraph`
 * Reference: docs/re-spec/04-frontend-ui-and-widget.md §11.6.
 */

export const DEPENDENCY_GRAPH_VERSION = "1.0" as const;
export const DEPENDENCY_GRAPH_KIND = "dependency-graph" as const;

export type DependencyGraphDirection = "upstream" | "downstream";

export interface DependencyGraphColumn {
  id: string;
  name: string;
  dataType?: string;
  primaryKey?: boolean;
  unqiueKey?: boolean;
  foreignKey?: boolean;
}

export interface DependencyGraphNode {
  id: string;
  label: string;
  table: string;
  type: string;
  database?: string;
  schema?: string;
  expandable?: boolean;
  position?: { x: number; y: number };
  columns?: DependencyGraphColumn[];
}

export interface DependencyGraphColumnMapping {
  sourceColumnId: string;
  targetColumnId: string;
  relationType?: "fdd" | "fdr" | "frd" | "fddi" | "join";
  transformCode?: string;
}

export interface DependencyGraphEdge {
  id: string;
  source: string;
  target: string;
  relationType?: "fdd" | "fdr" | "frd" | "fddi" | "join";
  columnMappings?: DependencyGraphColumnMapping[];
}

export interface DependencyGraphV1 {
  version: typeof DEPENDENCY_GRAPH_VERSION;
  kind: typeof DEPENDENCY_GRAPH_KIND;
  direction: DependencyGraphDirection;
  focus: {
    nodeId: string;
  };
  nodes: DependencyGraphNode[];
  edges: DependencyGraphEdge[];
}
