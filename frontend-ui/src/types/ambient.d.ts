declare module "elkjs/lib/elk.bundled.js" {
  import ElkConstructor from "elkjs";
  export * from "elkjs";
  export default ElkConstructor;
}

interface JoinIslandMountOptions {
  model: import("@sqlflow/ui-core").JoinAnalysisGraph;
  highlightedNodeIds?: string[];
  highlightedEdgeIds?: string[];
  onSelect?: (selection: {
    nodeId?: string;
    edgeId?: string;
    fragmentId?: string;
    sourceRange?: import("@sqlflow/ui-core").SourceRange;
  }) => void;
}

interface JoinIslandHandle {
  update: (opts: Partial<JoinIslandMountOptions>) => void;
  unmount: () => void;
}

interface JoinIslandGlobal {
  version: string;
  mount: (container: HTMLElement, options: JoinIslandMountOptions) => JoinIslandHandle;
}

interface Window {
  __JoinIsland?: JoinIslandGlobal;
}
