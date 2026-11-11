import React from "react";
import { createRoot, type Root } from "react-dom/client";
import type { JoinAnalysisGraph, SourceRange } from "@sqlflow/ui-core";
import { JoinFlowCanvas } from "./JoinFlowCanvas";
import "@xyflow/react/dist/style.css";

export interface MountJoinIslandOptions {
  model: JoinAnalysisGraph;
  highlightedNodeIds?: string[];
  highlightedEdgeIds?: string[];
  onSelect?: (selection: {
    nodeId?: string;
    edgeId?: string;
    fragmentId?: string;
    sourceRange?: SourceRange;
  }) => void;
}

export function registerJoinIslandGlobal(): void {
  if (typeof window === "undefined") {
    return;
  }
  window.__JoinIsland = {
    version: "3.6.16",
    mount(container: HTMLElement, initialOptions: MountJoinIslandOptions) {
      let current = { ...initialOptions };
      const root: Root = createRoot(container);

      const renderCurrent = () => {
        root.render(
          <React.StrictMode>
            <JoinFlowCanvas
              model={current.model}
              highlightedNodeIds={new Set(current.highlightedNodeIds ?? [])}
              highlightedEdgeIds={new Set(current.highlightedEdgeIds ?? [])}
              onSelect={current.onSelect}
            />
          </React.StrictMode>
        );
      };

      renderCurrent();

      return {
        update(patch: Partial<MountJoinIslandOptions>) {
          current = { ...current, ...patch };
          renderCurrent();
        },
        unmount() {
          root.unmount();
        }
      };
    }
  };
}

registerJoinIslandGlobal();
