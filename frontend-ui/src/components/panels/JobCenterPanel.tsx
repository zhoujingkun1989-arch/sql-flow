import React, { useCallback, useEffect, useMemo, useState } from "react";
import { JOB_STATUS_COLORS, type JobStatus, type JobSummary } from "@sqlflow/ui-core";
import {
  Briefcase,
  Eye,
  Pin,
  Plus,
  RefreshCw,
  RotateCcw,
  Square,
  Trash2
} from "lucide-react";
import { useI18n } from "../../i18n/I18nContext";
import { useApp } from "../../state/AppContext";

export const JobCenterPanel: React.FC = () => {
  const { t } = useI18n();
  const {
    api,
    setActiveModal,
    openJobDetailModal,
    setLineageResult,
    showToast
  } = useApp();

  const [jobs, setJobs] = useState<JobSummary[]>([]);
  const [statusFilter, setStatusFilter] = useState<"ALL" | JobStatus>("ALL");
  const [searchQuery, setSearchQuery] = useState("");
  const [loading, setLoading] = useState(false);

  const fetchJobs = useCallback(async () => {
    setLoading(true);
    try {
      const res = await api.client.displayUserJobsSummary();
      const list = res?.data?.jobDetails ?? [];
      setJobs(list);
    } catch {
      // Keep current jobs in offline mode
    } finally {
      setLoading(false);
    }
  }, [api]);

  useEffect(() => {
    void fetchJobs();
  }, [fetchJobs]);

  // Active job 5000ms polling (`OBL-105`, `04 §9.1`):
  // Runs ONLY while at least one job is in `"create"` or `"process"` status.
  const hasActiveJobs = useMemo(
    () => jobs.some((j) => j.status === "create" || j.status === "process"),
    [jobs]
  );

  useEffect(() => {
    if (!hasActiveJobs) {
      return undefined;
    }
    const timer = setInterval(() => {
      void fetchJobs();
    }, 5000);
    return () => clearInterval(timer);
  }, [hasActiveJobs, fetchJobs]);

  const filteredJobs = useMemo(() => {
    const q = searchQuery.trim().toLowerCase();
    const matching = jobs.filter((j) => {
      if (statusFilter !== "ALL" && j.status !== statusFilter) {
        return false;
      }
      if (!q) return true;
      return (
        j.jobName.toLowerCase().includes(q) ||
        j.jobId.toLowerCase().includes(q) ||
        String(j.dbVendor ?? "").toLowerCase().includes(q)
      );
    });
    return matching.sort((a, b) => Number(Boolean(b.onTop)) - Number(Boolean(a.onTop)));
  }, [jobs, statusFilter, searchQuery]);

  const handleTogglePin = async (job: JobSummary) => {
    try {
      await api.client.setJobOnTop(job.jobId, !job.onTop);
      await fetchJobs();
    } catch {
      showToast("Failed to update job pin");
    }
  };

  const handleRerun = async (job: JobSummary) => {
    try {
      await api.client.rerunJob(job.jobId, job.jobName);
      showToast(`Rerunning ${job.jobName}`);
      await fetchJobs();
    } catch {
      showToast("Failed to rerun job");
    }
  };

  const handleCancel = async (job: JobSummary) => {
    try {
      await api.cancelUserJob(job.jobId);
      showToast(`Stopped ${job.jobName}`);
      await fetchJobs();
    } catch {
      showToast("Failed to stop job");
    }
  };

  const handleDelete = async (job: JobSummary) => {
    try {
      await api.client.deleteUserJob(job.jobId);
      showToast(`Deleted ${job.jobName}`);
      await fetchJobs();
    } catch {
      showToast("Failed to delete job");
    }
  };

  const handleVisualizeJob = async (job: JobSummary) => {
    try {
      const res = await api.client.displayUserJobSummary(job.jobId);
      if (res?.data) {
        openJobDetailModal(res.data);
      }
      const graphRes = await api.client.getJobGraphSummary({
        jobId: job.jobId,
        dbvendor: job.dbVendor || "dbvoracle"
      });
      if (graphRes?.data) {
        setLineageResult(
          { summary: graphRes.data, mode: "summary" },
          job.sessionId,
          job
        );
      }
    } catch {
      openJobDetailModal(job);
    }
  };

  return (
    <div
      data-testid="job-center-panel"
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
          <Briefcase size={15} />
          <span>{t("job.title")}</span>
          <span style={{ fontSize: 11, color: "#64748b", fontWeight: 400 }}>({jobs.length})</span>
        </div>
        <div style={{ display: "flex", gap: 6 }}>
          <button
            type="button"
            data-testid="job-refresh-btn"
            className="sf-btn sf-btn--light"
            style={{ height: 28 }}
            onClick={() => void fetchJobs()}
          >
            <RefreshCw size={12} />
          </button>
          <button
            type="button"
            data-testid="open-create-job-btn"
            className="sf-btn sf-btn--primary"
            style={{ height: 28 }}
            onClick={() => setActiveModal("createJob")}
          >
            <Plus size={13} />
            {t("job.create")}
          </button>
        </div>
      </div>

      {/* Active Polling Indicator */}
      {hasActiveJobs && (
        <div
          data-testid="job-polling-indicator"
          style={{
            padding: "4px 12px",
            background: "#eff6ff",
            color: "#1d4ed8",
            fontSize: 11,
            borderBottom: "1px solid #dbeafe"
          }}
        >
          {t("job.polling")}
        </div>
      )}

      {/* Search & Status Filter */}
      <div
        style={{
          padding: "8px 12px",
          borderBottom: "1px solid #e2e8f0",
          display: "flex",
          gap: 6
        }}
      >
        <input
          type="text"
          data-testid="job-search-input"
          className="sf-input"
          placeholder="Filter jobs..."
          value={searchQuery}
          onChange={(e) => setSearchQuery(e.target.value)}
          style={{ flex: 1 }}
        />
        <select
          aria-label="Job Status Filter"
          data-testid="job-status-filter"
          className="sf-select"
          value={statusFilter}
          onChange={(e) => setStatusFilter(e.target.value as "ALL" | JobStatus)}
        >
          <option value="ALL">All Status</option>
          <option value="create">Created</option>
          <option value="process">Processing</option>
          <option value="success">Success</option>
          <option value="partial_success">Partial</option>
          <option value="fail">Failed</option>
        </select>
      </div>

      {/* Job Cards List */}
      <div style={{ flex: 1, overflowY: "auto", padding: "8px 12px", display: "flex", flexDirection: "column", gap: 8 }}>
        {loading && jobs.length === 0 ? (
          <div style={{ fontSize: 12, color: "#64748b", padding: 12 }}>Loading jobs...</div>
        ) : filteredJobs.length === 0 ? (
          <div style={{ fontSize: 12, color: "#64748b", padding: 12 }}>
            No batch lineage jobs found. Click <strong>{t("job.create")}</strong> to harvest SQL or
            live JDBC metadata.
          </div>
        ) : (
          filteredJobs.map((job) => {
            const statusColor =
              JOB_STATUS_COLORS[job.status as keyof typeof JOB_STATUS_COLORS] ?? "#64748b";
            const isRunning = job.status === "create" || job.status === "process";
            return (
              <div
                key={job.jobId}
                data-testid={`job-card-${job.jobId}`}
                style={{
                  border: job.onTop ? "1.5px solid #3b82f6" : "1px solid #e2e8f0",
                  borderRadius: 6,
                  padding: "8px 10px",
                  background: job.onTop ? "#f8fafc" : "#ffffff"
                }}
              >
                <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center" }}>
                  <div style={{ display: "flex", alignItems: "center", gap: 6 }}>
                    <strong style={{ fontSize: 12, color: "#0f172a" }}>{job.jobName}</strong>
                    <span
                      style={{
                        fontSize: 10,
                        fontWeight: 700,
                        padding: "1px 6px",
                        borderRadius: 999,
                        background: statusColor,
                        color: "#ffffff"
                      }}
                    >
                      {job.status}
                    </span>
                  </div>
                  <span style={{ fontSize: 10, color: "#64748b" }}>{job.dbVendor}</span>
                </div>

                <div style={{ fontSize: 11, color: "#64748b", marginTop: 4 }}>
                  ID: {job.jobId} {job.createTime ? `• ${job.createTime}` : ""}
                </div>

                <div style={{ display: "flex", flexWrap: "wrap", gap: 5, marginTop: 8 }}>
                  <button
                    type="button"
                    data-testid={`job-detail-btn-${job.jobId}`}
                    className="sf-btn sf-btn--light"
                    style={{ height: 24, fontSize: 11 }}
                    onClick={() => void handleVisualizeJob(job)}
                  >
                    <Eye size={11} />
                    Detail
                  </button>
                  <button
                    type="button"
                    data-testid={`job-pin-btn-${job.jobId}`}
                    className="sf-btn sf-btn--light"
                    style={{ height: 24, fontSize: 11 }}
                    onClick={() => void handleTogglePin(job)}
                  >
                    <Pin size={11} />
                    {job.onTop ? "Unpin" : "Pin"}
                  </button>
                  <button
                    type="button"
                    data-testid={`job-rerun-btn-${job.jobId}`}
                    className="sf-btn sf-btn--light"
                    style={{ height: 24, fontSize: 11 }}
                    onClick={() => void handleRerun(job)}
                  >
                    <RotateCcw size={11} />
                    Rerun
                  </button>
                  {isRunning && (
                    <button
                      type="button"
                      data-testid={`job-cancel-btn-${job.jobId}`}
                      className="sf-btn sf-btn--light"
                      style={{ height: 24, fontSize: 11 }}
                      onClick={() => void handleCancel(job)}
                    >
                      <Square size={11} />
                      Stop
                    </button>
                  )}
                  <button
                    type="button"
                    data-testid={`job-delete-btn-${job.jobId}`}
                    className="sf-btn sf-btn--light"
                    style={{ height: 24, fontSize: 11, color: "#dc2626" }}
                    onClick={() => void handleDelete(job)}
                  >
                    <Trash2 size={11} />
                    Delete
                  </button>
                </div>
              </div>
            );
          })
        )}
      </div>
    </div>
  );
};
