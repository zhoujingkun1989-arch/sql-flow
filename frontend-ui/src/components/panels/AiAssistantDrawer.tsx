import React, { useCallback, useEffect, useState } from "react";
import {
  type AiConfidenceLevel,
  type AiNodeCitation,
  type AiSuggestedQuestion,
  layoutSqlflowGraph
} from "@sqlflow/ui-core";
import { Bot, DatabaseZap, Send, Sparkles } from "lucide-react";
import { useI18n } from "../../i18n/I18nContext";
import { useApp } from "../../state/AppContext";

interface ChatMessage {
  id: string;
  role: "user" | "assistant";
  text: string;
  streaming?: boolean;
  confidence?: AiConfidenceLevel;
  referencedNodes?: string[];
  citations?: AiNodeCitation[];
  metaSummary?: string;
}

export const AiAssistantDrawer: React.FC = () => {
  const { t } = useI18n();
  const {
    api,
    dialect,
    combinedSqlText,
    sessionId,
    lineageData,
    setLocatedNodeId,
    setCanvasPinnedTarget,
    setActiveRightTab,
    showToast
  } = useApp();

  const [aiEnabled, setAiEnabled] = useState<boolean | null>(null);
  const [aiModel, setAiModel] = useState<string | undefined>(undefined);
  const [suggestions, setSuggestions] = useState<AiSuggestedQuestion[]>([]);
  const [questionInput, setQuestionInput] = useState("");
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [streaming, setStreaming] = useState(false);
  const [indexing, setIndexing] = useState(false);

  const effectiveSessionId = sessionId ?? "default-session";

  // Check unwrapped `GET /sqlflow/generation/ai/status` (`00 §3.3 #16`)
  useEffect(() => {
    let active = true;
    const checkStatus = async () => {
      try {
        const status = await api.client.getAiStatus();
        if (!active) return;
        setAiEnabled(Boolean(status?.enabled));
        setAiModel(status?.model);
        if (status?.enabled) {
          const items = await api.fetchAiSuggestionsWithFallback({
            sessionId: effectiveSessionId,
            dbvendor: dialect
          });
          if (active) {
            setSuggestions(items);
          }
        }
      } catch {
        if (active) {
          setAiEnabled(false);
        }
      }
    };
    void checkStatus();
    return () => {
      active = false;
    };
  }, [api, effectiveSessionId, dialect]);

  const handleBuildIndex = async () => {
    setIndexing(true);
    try {
      await api.buildAiLineageIndex({
        sessionId: effectiveSessionId,
        dbvendor: dialect,
        sqltext: combinedSqlText
      });
      showToast("AI lineage index updated");
      const items = await api.fetchAiSuggestionsWithFallback({
        sessionId: effectiveSessionId,
        dbvendor: dialect
      });
      setSuggestions(items);
    } catch {
      showToast("Failed to build AI lineage index");
    } finally {
      setIndexing(false);
    }
  };

  const handleCitationClick = useCallback(
    (labelOrId: string, citation?: AiNodeCitation) => {
      setActiveRightTab("columnLineage");
      if (!lineageData) {
        setLocatedNodeId(labelOrId);
        return;
      }
      const model = layoutSqlflowGraph(lineageData);
      const targetId = citation?.nodeId ?? labelOrId;
      const directNode = model.nodeById.get(targetId);
      if (directNode) {
        setLocatedNodeId(directNode.id);
        setCanvasPinnedTarget({ kind: "node", nodeId: directNode.id });
        return;
      }
      const searchName = (citation?.table ?? labelOrId.split(".")[0] ?? labelOrId).toLowerCase();
      const matched = model.nodes.find(
        (n) =>
          n.name.toLowerCase() === searchName ||
          n.displayTitle.toLowerCase().includes(searchName)
      );
      if (matched) {
        setLocatedNodeId(matched.id);
        if (citation?.column) {
          const col = matched.columns.find(
            (c) => c.name.toLowerCase() === citation.column!.toLowerCase()
          );
          if (col) {
            setCanvasPinnedTarget({ kind: "column", columnId: col.id });
            return;
          }
        }
        setCanvasPinnedTarget({ kind: "node", nodeId: matched.id });
      }
    },
    [lineageData, setActiveRightTab, setLocatedNodeId, setCanvasPinnedTarget]
  );

  const askQuestion = async (rawQuestion: string) => {
    const q = rawQuestion.trim();
    if (!q || streaming || aiEnabled === false) return;

    const userMsgId = `u-${Date.now()}`;
    const assistantMsgId = `a-${Date.now() + 1}`;
    setQuestionInput("");
    setStreaming(true);

    setMessages((prev) => [
      ...prev,
      { id: userMsgId, role: "user", text: q },
      { id: assistantMsgId, role: "assistant", text: "", streaming: true }
    ]);

    try {
      await api.client.streamAiQuery(
        {
          sessionId: effectiveSessionId,
          question: q,
          dbvendor: dialect
        },
        {
          onEvent: (event) => {
            if (event.type === "metadata") {
              setMessages((prev) =>
                prev.map((m) =>
                  m.id === assistantMsgId
                    ? {
                        ...m,
                        metaSummary: `${event.nodes} nodes, ${event.edges} edges${
                          event.truncated ? " (truncated)" : ""
                        }`
                      }
                    : m
                )
              );
            } else if (event.type === "chunk") {
              setMessages((prev) =>
                prev.map((m) =>
                  m.id === assistantMsgId ? { ...m, text: m.text + event.text } : m
                )
              );
            } else if (event.type === "complete") {
              setMessages((prev) =>
                prev.map((m) =>
                  m.id === assistantMsgId
                    ? {
                        ...m,
                        text: event.response.answer || m.text,
                        streaming: false,
                        confidence: event.response.confidence,
                        referencedNodes: event.response.referencedNodes,
                        citations: event.response.citations
                      }
                    : m
                )
              );
            } else if (event.type === "no_match" || event.type === "error") {
              setMessages((prev) =>
                prev.map((m) =>
                  m.id === assistantMsgId
                    ? { ...m, text: event.message, streaming: false }
                    : m
                )
              );
            }
          }
        }
      );
    } catch (err) {
      setMessages((prev) =>
        prev.map((m) =>
          m.id === assistantMsgId
            ? {
                ...m,
                text: err instanceof Error ? err.message : "AI stream interrupted",
                streaming: false
              }
            : m
        )
      );
    } finally {
      setStreaming(false);
    }
  };

  return (
    <div
      data-testid="ai-assistant-drawer"
      style={{
        display: "flex",
        flexDirection: "column",
        width: "100%",
        height: "100%",
        background: "#ffffff",
        borderRight: "1px solid #e2e8f0"
      }}
    >
      {/* Header */}
      <div
        style={{
          padding: "10px 12px",
          borderBottom: "1px solid #e2e8f0",
          background: "#f8fafc",
          display: "flex",
          alignItems: "center",
          justifyContent: "space-between"
        }}
      >
        <div style={{ display: "flex", alignItems: "center", gap: 6, fontWeight: 700, fontSize: 13 }}>
          <Bot size={16} />
          <span>{t("ai.title")}</span>
          {aiModel && (
            <span
              style={{
                fontSize: 10,
                padding: "1px 6px",
                borderRadius: 999,
                background: "#dcfce7",
                color: "#166534"
              }}
            >
              {aiModel}
            </span>
          )}
        </div>
        {aiEnabled && (
          <button
            type="button"
            data-testid="ai-index-btn"
            className="sf-btn sf-btn--light"
            style={{ height: 26, fontSize: 11 }}
            disabled={indexing}
            onClick={() => void handleBuildIndex()}
          >
            <DatabaseZap size={12} />
            {indexing ? "Indexing..." : "Index Graph"}
          </button>
        )}
      </div>

      {aiEnabled === false ? (
        <div
          data-testid="ai-disabled-banner"
          style={{
            margin: 14,
            padding: 14,
            borderRadius: 8,
            background: "#f8fafc",
            border: "1px solid #cbd5e1",
            color: "#475569",
            fontSize: 12
          }}
        >
          <strong>{t("ai.disabled")}</strong>
          <div style={{ marginTop: 6, fontSize: 11, color: "#64748b" }}>
            Configure an LLM API key on the SQLFlow backend to enable streaming lineage Q&amp;A.
          </div>
        </div>
      ) : (
        <>
          {/* Suggested Questions */}
          {suggestions.length > 0 && (
            <div
              style={{
                padding: "8px 12px",
                borderBottom: "1px solid #e2e8f0",
                display: "flex",
                flexWrap: "wrap",
                gap: 5
              }}
            >
              {suggestions.map((sug, idx) => (
                <button
                  key={`${sug.question}-${idx}`}
                  type="button"
                  data-testid={`ai-suggestion-${idx}`}
                  onClick={() => {
                    void api.client
                      .recordAiSuggestionClick({
                        question: sug.question,
                        category: sug.category,
                        targetNode: sug.targetNode
                      })
                      .catch(() => undefined);
                    void askQuestion(sug.question);
                  }}
                  style={{
                    border: "1px solid #cbd5e1",
                    background: "#f8fafc",
                    borderRadius: 999,
                    padding: "3px 9px",
                    fontSize: 11,
                    color: "#334155",
                    cursor: "pointer",
                    display: "inline-flex",
                    alignItems: "center",
                    gap: 4
                  }}
                >
                  <Sparkles size={11} color="#2563eb" />
                  {sug.question}
                </button>
              ))}
            </div>
          )}

          {/* Chat Feed */}
          <div
            data-testid="ai-chat-messages"
            style={{
              flex: 1,
              overflowY: "auto",
              padding: 12,
              display: "flex",
              flexDirection: "column",
              gap: 10,
              fontSize: 12
            }}
          >
            {messages.length === 0 ? (
              <div style={{ color: "#64748b", fontSize: 12 }}>
                Ask any natural-language question about upstream tables, column derivations, or
                downstream impact.
              </div>
            ) : (
              messages.map((msg) => (
                <div
                  key={msg.id}
                  data-testid={`ai-msg-${msg.role}`}
                  style={{
                    alignSelf: msg.role === "user" ? "flex-end" : "flex-start",
                    maxWidth: "92%",
                    padding: "8px 11px",
                    borderRadius: 8,
                    background: msg.role === "user" ? "#2563eb" : "#f1f5f9",
                    color: msg.role === "user" ? "#ffffff" : "#0f172a"
                  }}
                >
                  {msg.metaSummary && (
                    <div style={{ fontSize: 10, color: "#64748b", marginBottom: 4 }}>
                      Context: {msg.metaSummary}
                      {msg.confidence ? ` • confidence: ${msg.confidence}` : ""}
                    </div>
                  )}
                  <div style={{ whiteSpace: "pre-wrap" }}>
                    {msg.text || (msg.streaming ? "Thinking..." : "")}
                  </div>

                  {/* Clickable Referenced Nodes & Citations */}
                  {((msg.referencedNodes && msg.referencedNodes.length > 0) ||
                    (msg.citations && msg.citations.length > 0)) && (
                    <div style={{ display: "flex", flexWrap: "wrap", gap: 5, marginTop: 8 }}>
                      {(msg.referencedNodes ?? []).map((nodeRef) => (
                        <button
                          key={nodeRef}
                          type="button"
                          data-testid={`ai-citation-${nodeRef}`}
                          onClick={() => handleCitationClick(nodeRef)}
                          style={{
                            border: "1px solid #93c5fd",
                            background: "#eff6ff",
                            color: "#1d4ed8",
                            borderRadius: 4,
                            padding: "1px 6px",
                            fontSize: 10,
                            fontWeight: 600,
                            cursor: "pointer"
                          }}
                        >
                          {nodeRef}
                        </button>
                      ))}
                      {(msg.citations ?? []).map((cit, cIdx) => {
                        const lbl = [cit.table, cit.column].filter(Boolean).join(".") || cit.nodeId || "node";
                        return (
                          <button
                            key={`cit-${cIdx}`}
                            type="button"
                            data-testid={`ai-citation-${lbl}`}
                            onClick={() => handleCitationClick(lbl, cit)}
                            style={{
                              border: "1px solid #86efac",
                              background: "#f0fdf4",
                              color: "#166534",
                              borderRadius: 4,
                              padding: "1px 6px",
                              fontSize: 10,
                              fontWeight: 600,
                              cursor: "pointer"
                            }}
                          >
                            {lbl}
                          </button>
                        );
                      })}
                    </div>
                  )}
                </div>
              ))
            )}
          </div>

          {/* Input Box */}
          <form
            onSubmit={(e) => {
              e.preventDefault();
              void askQuestion(questionInput);
            }}
            style={{
              padding: "8px 12px",
              borderTop: "1px solid #e2e8f0",
              display: "flex",
              gap: 6
            }}
          >
            <input
              type="text"
              data-testid="ai-question-input"
              className="sf-input"
              placeholder={t("ai.askPlaceholder")}
              value={questionInput}
              onChange={(e) => setQuestionInput(e.target.value)}
              style={{ flex: 1 }}
            />
            <button
              type="submit"
              data-testid="ai-send-btn"
              className="sf-btn sf-btn--primary"
              disabled={streaming || !questionInput.trim()}
            >
              <Send size={13} />
            </button>
          </form>
        </>
      )}
    </div>
  );
};
