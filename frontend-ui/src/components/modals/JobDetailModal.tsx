import React, { useEffect, useState } from "react";
import {
  JOB_STATUS_COLORS,
  type JobSummary,
  LineageCanvas,
  SqlCodeEditor,
  type SqlflowJSON,
  type SummaryInfo
} from "@sqlflow/ui-core";
import { Download } from "lucide-react";
import { useApp } from "../../state/AppContext";
import { buildDlineageXml, triggerBrowserDownload } from "../../utils/exporters";

export interface JobDetailModalProps {
  job: JobSummary;
  onClose: () => void;
}

type JobDetailTab = "overview" | "lineage" | "databases" | "files" | "errors";

export const JobDetailModal: React.FC<JobDetailModalProps> = ({ job, onClose }) => {
  const { api, showToast } = useApp();
  const [activeTab, setActiveTab] = useState<JobDetailTab>("overview");
  const [summaryInfo, setSummaryInfo] = useState<SummaryInfo | null>(null);
  const [jobGraph, setJobGraph] = useState<SqlflowJSON | null>(null);
  const [selectedFileSql, setSelectedFileSql] = useState<string>("");
  const [schedulePaused, setSchedulePaused] = useState<boolean>(Boolean(job.cancelSchedule));

  useEffect(() => {
    let mounted = true;
    const loadSummary = async () => {
      try {
        const sumRes = await api.client.getJobGraphSummary({
          jobId: job.jobId,
          dbvendor: job.dbVendor || "dbvoracle"
        });
        if (mounted && sumRes?.data) {
          setSummaryInfo(sumRes.data);
        }
      } catch {
        // Optional summary
      }
      try {
        const latest = await api.client.displayUserLatestJobGraph();
        if (mounted && latest?.data && (!latest.jobId || latest.jobId === job.jobId)) {
          setJobGraph(latest.data);
        }
      } catch {
        // Optional graph preview
      }
    };
    void loadSummary();
    return () => {
      mounted = false;
    };
  }, [api, job.jobId]);

  const handlePreviewFile = async (fileName: string) => {
    const content = await api.getJobSqlFileContent(job.jobId, fileName);
    setSelectedFileSql(content || `-- SQL source for ${fileName}`);
  };

  const handleDownloadArtifact = async (
    format: "json" | "xml" | "csv" | "zip" | "parquet"
  ) => {
    try {
      if (format === "xml" && jobGraph) {
        const xmlText = buildDlineageXml(jobGraph);
        triggerBrowserDownload(xmlText, `${job.jobName}-${job.jobId}.xml`, "application/xml");
        showToast("Downloaded XML export");
        return;
      }
      const blob = await api.downloadJobArtifactBlob(job.jobId, format, job.jobName);
      const ext = format === "zip" ? "metadata.zip" : format;
      triggerBrowserDownload(blob, `${job.jobName}-${job.jobId}.${ext}`);
      showToast(`Downloaded ${format.toUpperCase()} export`);
    } catch {
      showToast(`Failed to download ${format.toUpperCase()}`);
    }
  };

  const handleToggleSchedule = async () => {
    const nextPaused = !schedulePaused;
    try {
      await api.toggleScheduleJob(job.jobId, nextPaused);
      setSchedulePaused(nextPaused);
      showToast(nextPaused ? "Schedule paused" : "Schedule resumed");
    } catch {
      showToast("Failed to update job schedule");
    }
  };

  const statusColor =
    JOB_STATUS_COLORS[job.status as keyof typeof JOB_STATUS_COLORS] ?? "#64748b";

  return (
    <div className="sf-modal-backdrop" data-testid="job-detail-modal" onClick={onClose}>
      <div
        className="sf-modal-card"
        style={{ maxWidth: 880, height: "82vh" }}
        onClick={(e) => e.stopPropagation()}
      >
        <div className="sf-modal-header">
          <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
            <span>Job Detail — {job.jobName}</span>
            <span
              style={{
                fontSize: 10,
                padding: "2px 7px",
                borderRadius: 999,
                background: statusColor,
                color: "#ffffff"
              }}
            >
              {job.status}
            </span>
          </div>
          <button type="button" className="sf-btn sf-btn--light" onClick={onClose}>
            Close
          </button>
        </div>

        {/* 5-Tab Bar (`OBL-105`) */}
        <div
          style={{
            display: "flex",
            gap: 4,
            padding: "6px 14px 0",
            borderBottom: "1px solid #e2e8f0",
            background: "#f8fafc"
          }}
        >
          {(
            [
              ["overview", "1. Overview"],
              ["lineage", "2. Lineage Graph"],
              ["databases", "3. Databases & Objects"],
              ["files", "4. SQL Files"],
              ["errors", "5. Error Logs & Exports"]
            ] as const
          ).map(([id, label]) => (
            <button
              key={id}
              type="button"
              data-testid={`job-modal-tab-${id}`}
              onClick={() => setActiveTab(id)}
              style={{
                padding: "7px 12px",
                fontSize: 12,
                fontWeight: activeTab === id ? 700 : 500,
                border: "1px solid #cbd5e1",
                borderBottom: activeTab === id ? "1px solid #ffffff" : "1px solid #cbd5e1",
                borderTopLeftRadius: 6,
                borderTopRightRadius: 6,
                background: activeTab === id ? "#ffffff" : "#e2e8f0",
                cursor: "pointer",
                marginBottom: -1
              }}
            >
              {label}
            </button>
          ))}
        </div>

        <div className="sf-modal-body" style={{ fontSize: 12 }}>
          {activeTab === "overview" && (
            <div style={{ display: "grid", gridTemplateColumns: "180px 1fr", rowGap: 10 }}>
              <strong>Job ID:</strong>
              <span>{job.jobId}</span>
              <strong>Job Name:</strong>
              <span>{job.jobName}</span>
              <strong>Dialect:</strong>
              <span>{job.dbVendor}</span>
              <strong>SQL Source:</strong>
              <span>{job.sqlsource ?? "uploadFile"}</span>
              <strong>Created Time:</strong>
              <span>{job.createTime ?? "-"}</span>
              <strong>Duration:</strong>
              <span>{job.spendTime ? `${job.spendTime} ms` : "-"}</span>
              <strong>Schedule:</strong>
              <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
                <span>{job.cronExpression ?? "Manual / On-demand"}</span>
                {job.schedulable && (
                  <button
                    type="button"
                    data-testid="job-toggle-schedule-btn"
                    className="sf-btn sf-btn--light"
                    style={{ height: 24, fontSize: 11 }}
                    onClick={() => void handleToggleSchedule()}
                  >
                    {schedulePaused ? "Resume Schedule" : "Pause Schedule"}
                  </button>
                )}
              </div>
            </div>
          )}

          {activeTab === "lineage" && (
            <div style={{ width: "100%", height: "100%", minHeight: 380 }}>
              {jobGraph ? (
                <LineageCanvas data={jobGraph} height={420} />
              ) : (
                <div style={{ padding: 24, color: "#64748b" }}>
                  No pre-rendered lineage snapshot cached for job {job.jobId}.
                </div>
              )}
            </div>
          )}

          {activeTab === "databases" && (
            <div style={{ display: "flex", flexDirection: "column", gap: 14 }}>
              <div style={{ display: "grid", gridTemplateColumns: "repeat(4, 1fr)", gap: 10 }}>
                {[
                  ["Databases", summaryInfo?.database ?? 0],
                  ["Schemas", summaryInfo?.schema ?? 0],
                  ["Tables", summaryInfo?.table ?? 0],
                  ["Views", summaryInfo?.view ?? 0]
                ].map(([lbl, count]) => (
                  <div
                    key={String(lbl)}
                    style={{
                      padding: 12,
                      borderRadius: 6,
                      border: "1px solid #e2e8f0",
                      background: "#f8fafc"
                    }}
                  >
                    <div style={{ color: "#64748b", fontSize: 11 }}>{lbl}</div>
                    <strong style={{ fontSize: 18 }}>{count}</strong>
                  </div>
                ))}
              </div>
              {(job.hostname || job.dataSource?.hostname) && (
                <div style={{ border: "1px solid #e2e8f0", borderRadius: 6, padding: 12 }}>
                  <strong>Database Connection Metadata</strong>
                  <div style={{ marginTop: 6, color: "#334155" }}>
                    Host: {job.hostname ?? job.dataSource?.hostname}:{job.port ?? job.dataSource?.port}{" "}
                    • DB: {job.database ?? job.dataSource?.database ?? "-"} • Account:{" "}
                    {job.account ?? job.dataSource?.account ?? "-"}
                  </div>
                </div>
              )}
            </div>
          )}

          {activeTab === "files" && (
            <div style={{ display: "grid", gridTemplateColumns: "240px 1fr", gap: 12, height: 380 }}>
              <div style={{ border: "1px solid #e2e8f0", borderRadius: 6, overflowY: "auto", padding: 8 }}>
                <strong>SQL Files ({job.fileNames?.length ?? 0})</strong>
                {(job.fileNames ?? []).map((fn) => (
                  <button
                    key={fn}
                    type="button"
                    className="sf-dropdown-item"
                    onClick={() => void handlePreviewFile(fn)}
                  >
                    {fn}
                  </button>
                ))}
                {job.subJobs && job.subJobs.length > 0 && (
                  <>
                    <div style={{ fontWeight: 700, marginTop: 10 }}>Sub-Jobs</div>
                    {job.subJobs.map((sj) => (
                      <div key={sj.batchId} style={{ fontSize: 11, padding: "4px 0", color: "#475569" }}>
                        {sj.batchId} ({sj.status ?? "done"})
                      </div>
                    ))}
                  </>
                )}
              </div>
              <div style={{ border: "1px solid #e2e8f0", borderRadius: 6, overflow: "hidden" }}>
                <SqlCodeEditor
                  value={selectedFileSql || "-- Select a SQL file on the left to inspect source"}
                  readOnly={true}
                  height="100%"
                />
              </div>
            </div>
          )}

          {activeTab === "errors" && (
            <div style={{ display: "flex", flexDirection: "column", gap: 14 }}>
              <div>
                <strong>Export Job Lineage Artifacts</strong>
                <div style={{ display: "flex", flexWrap: "wrap", gap: 8, marginTop: 8 }}>
                  {(["json", "xml", "csv", "zip", "parquet"] as const).map((fmt) => (
                    <button
                      key={fmt}
                      type="button"
                      data-testid={`job-export-${fmt}-btn`}
                      className="sf-btn sf-btn--light"
                      onClick={() => void handleDownloadArtifact(fmt)}
                    >
                      <Download size={12} />
                      Download {fmt.toUpperCase()}
                    </button>
                  ))}
                </div>
              </div>

              <div>
                <strong>Error Logs ({job.errorMessages?.length ?? (job.errorMessage ? 1 : 0)})</strong>
                <div
                  style={{
                    marginTop: 6,
                    padding: 10,
                    borderRadius: 6,
                    background: "#0f172a",
                    color: "#f8fafc",
                    fontFamily: "monospace",
                    minHeight: 140,
                    whiteSpace: "pre-wrap"
                  }}
                >
                  {job.errorMessages && job.errorMessages.length > 0
                    ? job.errorMessages.join("\n")
                    : job.errorMessage || "No job execution errors."}
                </div>
              </div>
            </div>
          )}
        </div>
      </div>
    </div>
  );
};
