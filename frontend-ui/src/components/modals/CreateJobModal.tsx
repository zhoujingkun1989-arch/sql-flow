import React, { useState } from "react";
import { type SqlSourceType, UI_DIALECTS } from "@sqlflow/ui-core";
import { useApp } from "../../state/AppContext";
import {
  CRON_SCHEDULE_PRESETS,
  EXTENDED_JDBC_VENDORS,
  SNOWFLAKE_QUERY_TYPES,
  encodeCsvFormat,
  findExtendedJdbcVendor
} from "../../utils/jdbcVendors";

export interface CreateJobModalProps {
  onClose: () => void;
  onJobCreated?: () => void;
}

export const CreateJobModal: React.FC<CreateJobModalProps> = ({ onClose, onJobCreated }) => {
  const { api, dialect, settings, showToast } = useApp();

  const [jobName, setJobName] = useState(`lineage-job-${Date.now().toString().slice(-4)}`);
  const [sqlsource, setSqlsource] = useState<SqlSourceType>("uploadFile");
  const [persistJob, setPersistJob] = useState(false);

  // File upload / CSV / dbt / PySpark / Redshift
  const [fileVendor, setFileVendor] = useState<string>(dialect);
  const [uploadedFiles, setUploadedFiles] = useState<File[]>([]);
  const [enableCsvMapping, setEnableCsvMapping] = useState(false);
  const [encloseChar, setEncloseChar] = useState('"');
  const [escapeChar, setEscapeChar] = useState("\\");
  const [csvDbPos, setCsvDbPos] = useState("1");
  const [csvSchemaPos, setCsvSchemaPos] = useState("2");
  const [csvTypePos, setCsvTypePos] = useState("3");
  const [csvNamePos, setCsvNamePos] = useState("4");
  const [csvCodePos, setCsvCodePos] = useState("5");
  const [csvCommentPos, setCsvCommentPos] = useState("6");

  // Live JDBC connection (`account` + RSA-encrypted `password` via `api.client`)
  const [jdbcVendorKey, setJdbcVendorKey] = useState<string>("oracle");
  const activeJdbcMeta = findExtendedJdbcVendor(jdbcVendorKey);
  const [hostname, setHostname] = useState("127.0.0.1");
  const [port, setPort] = useState<string>(String(activeJdbcMeta.defaultPort ?? 1521));
  const [database, setDatabase] = useState("ORCL");
  const [account, setAccount] = useState("system");
  const [password, setPassword] = useState("");
  const [extractedDbsSchemas, setExtractedDbsSchemas] = useState("");
  const [excludedDbsSchemas, setExcludedDbsSchemas] = useState("");
  const [extractedStoredProcedures, setExtractedStoredProcedures] = useState(true);
  const [extractedViews, setExtractedViews] = useState(true);
  const [testStatus, setTestStatus] = useState<string | null>(null);

  // Snowflake Query History (`snowflakequeryhistory`)
  const [selectedSnowflakeTypes, setSelectedSnowflakeTypes] = useState<string[]>([
    "SELECT",
    "INSERT",
    "CREATE_VIEW",
    "MERGE"
  ]);
  const [snowflakeBlockMinutes, setSnowflakeBlockMinutes] = useState(60);
  const [snowflakeRole, setSnowflakeRole] = useState("ACCOUNTADMIN");

  // Cron schedule
  const [schedulable, setSchedulable] = useState(false);
  const [cronExpression, setCronExpression] = useState("0 0 2 * * ?");
  const [submitting, setSubmitting] = useState(false);

  const isDatabaseSource =
    sqlsource === "fromDatabase" ||
    sqlsource === "uploadFileAndFromDatabase" ||
    sqlsource === "snowflakequeryhistory";

  const handleVendorSelect = (nextKey: string) => {
    setJdbcVendorKey(nextKey);
    const meta = findExtendedJdbcVendor(nextKey);
    if (meta.defaultPort !== undefined) {
      setPort(String(meta.defaultPort));
    }
  };

  const handleTestConnection = async () => {
    setTestStatus("Testing JDBC connection...");
    try {
      const res = await api.client.testConnection({
        dbvendor: activeJdbcMeta.dbvendor,
        hostname,
        port,
        database,
        account,
        password
      });
      if (Number(res.code) === 200) {
        setTestStatus("Connection succeeded!");
      } else {
        setTestStatus(res.error || res.message || `Connection returned code ${res.code}`);
      }
    } catch (err) {
      setTestStatus(err instanceof Error ? err.message : "Connection failed");
    }
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setSubmitting(true);
    try {
      const effectiveVendor =
        sqlsource === "snowflakequeryhistory"
          ? "dbvsnowflake"
          : sqlsource === "redshiftlog"
            ? "dbvredshift"
            : isDatabaseSource
              ? activeJdbcMeta.dbvendor
              : fileVendor;

      const csvFormat = enableCsvMapping
        ? encodeCsvFormat({
            csvDatabase: csvDbPos,
            csvSchema: csvSchemaPos,
            csvObjectType: csvTypePos,
            csvObjectName: csvNamePos,
            csvObjectCode: csvCodePos,
            csvComments: csvCommentPos
          })
        : undefined;

      const payload = {
        jobName,
        dbvendor: effectiveVendor,
        sqlsource,
        ignoreRecordSet: settings.ignoreRecordSet,
        ignoreFunction: settings.ignoreFunction,
        showConstantTable: settings.showConstantTable,
        showTransform: settings.showTransform,
        ...(uploadedFiles.length > 0 ? { sqlfiles: uploadedFiles } : {}),
        ...(enableCsvMapping
          ? {
              objectCodeEncloseChar: encloseChar,
              objectCodeEscapeChar: escapeChar,
              csvFormat
            }
          : {}),
        ...(isDatabaseSource
          ? {
              hostname,
              port,
              database,
              account,
              password,
              extractedDbsSchemas,
              excludedDbsSchemas,
              extractedStoredProcedures,
              extractedViews
            }
          : {}),
        ...(sqlsource === "snowflakequeryhistory"
          ? {
              extra: {
                enableQueryHistory: true,
                queryHistoryBlockOfTimeInMinutes: snowflakeBlockMinutes,
                queryHistorySqlType: selectedSnowflakeTypes.join(","),
                snowflakeDefaultRole: snowflakeRole
              }
            }
          : {}),
        ...(schedulable ? { schedulable: true, cronExpression } : {})
      };

      const res = persistJob
        ? await api.client.submitPersistJob(payload)
        : await api.client.submitUserJob(payload);

      if (Number(res.code) === 200 || res.data) {
        showToast(`Job "${jobName}" submitted`);
        onJobCreated?.();
        onClose();
      } else {
        showToast(res.error ?? "Job submission failed");
      }
    } catch (err) {
      showToast(err instanceof Error ? err.message : "Job submission failed");
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="sf-modal-backdrop" data-testid="create-job-modal" onClick={onClose}>
      <form
        className="sf-modal-card"
        style={{ maxWidth: 740 }}
        onClick={(e) => e.stopPropagation()}
        onSubmit={(e) => void handleSubmit(e)}
      >
        <div className="sf-modal-header">
          <span>Create Lineage Job Wizard</span>
          <button type="button" className="sf-btn sf-btn--light" onClick={onClose}>
            Close
          </button>
        </div>

        <div className="sf-modal-body" style={{ display: "flex", flexDirection: "column", gap: 12, fontSize: 12 }}>
          <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: 10 }}>
            <div>
              <label style={{ display: "block", fontWeight: 600, marginBottom: 4 }}>Job Name</label>
              <input
                type="text"
                data-testid="create-job-name-input"
                className="sf-input"
                required
                value={jobName}
                onChange={(e) => setJobName(e.target.value)}
                style={{ width: "100%" }}
              />
            </div>

            <div>
              <label style={{ display: "block", fontWeight: 600, marginBottom: 4 }}>SQL Source</label>
              <select
                aria-label="Job SQL Source"
                data-testid="create-job-source-select"
                className="sf-select"
                value={sqlsource}
                onChange={(e) => setSqlsource(e.target.value as SqlSourceType)}
                style={{ width: "100%" }}
              >
                <option value="uploadFile">SQL / ZIP / CSV / PySpark (.py) Upload</option>
                <option value="fromDatabase">Live JDBC Database Harvester (16 Vendors)</option>
                <option value="uploadFileAndFromDatabase">Upload SQL + Live JDBC Schema</option>
                <option value="dbt">dbt Manifest (manifest.json + catalog.json)</option>
                <option value="redshiftlog">Redshift Query Log</option>
                <option value="snowflakequeryhistory">Snowflake Query History (71 SQL Types)</option>
              </select>
            </div>
          </div>

          {/* File Upload Section */}
          {(sqlsource === "uploadFile" ||
            sqlsource === "uploadFileAndFromDatabase" ||
            sqlsource === "dbt" ||
            sqlsource === "redshiftlog") && (
            <div
              style={{
                border: "1px solid #e2e8f0",
                borderRadius: 6,
                padding: 10,
                background: "#f8fafc"
              }}
            >
              <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: 10 }}>
                {!isDatabaseSource && (
                  <div>
                    <label style={{ display: "block", fontWeight: 600, marginBottom: 4 }}>
                      SQL Dialect
                    </label>
                    <select
                      className="sf-select"
                      value={fileVendor}
                      onChange={(e) => setFileVendor(e.target.value)}
                      style={{ width: "100%" }}
                    >
                      {UI_DIALECTS.map((d) => (
                        <option key={d.shortKey} value={d.shortKey}>
                          {d.label}
                        </option>
                      ))}
                    </select>
                  </div>
                )}
                <div>
                  <label style={{ display: "block", fontWeight: 600, marginBottom: 4 }}>
                    {sqlsource === "dbt"
                      ? "Upload dbt manifest.json / catalog.json"
                      : "Upload .sql / .zip / .csv / .py files"}
                  </label>
                  <input
                    type="file"
                    multiple
                    data-testid="create-job-file-input"
                    accept=".sql,.zip,.json,.csv,.py,.log,.txt"
                    onChange={(e) => setUploadedFiles(Array.from(e.target.files ?? []))}
                  />
                </div>
              </div>

              {/* 6-Digit `csvFormat` Metadata Mapping */}
              <div style={{ marginTop: 8 }}>
                <label style={{ display: "inline-flex", alignItems: "center", gap: 6 }}>
                  <input
                    type="checkbox"
                    data-testid="enable-csv-format-checkbox"
                    checked={enableCsvMapping}
                    onChange={(e) => setEnableCsvMapping(e.target.checked)}
                  />
                  Configure CSV Metadata Column Positions (6-digit <code>csvFormat</code>)
                </label>
                {enableCsvMapping && (
                  <div
                    style={{
                      display: "grid",
                      gridTemplateColumns: "repeat(4, 1fr)",
                      gap: 8,
                      marginTop: 8
                    }}
                  >
                    <div>
                      <span>Enclose Char</span>
                      <input
                        className="sf-input"
                        value={encloseChar}
                        onChange={(e) => setEncloseChar(e.target.value)}
                        style={{ width: "100%" }}
                      />
                    </div>
                    <div>
                      <span>Escape Char</span>
                      <input
                        className="sf-input"
                        value={escapeChar}
                        onChange={(e) => setEscapeChar(e.target.value)}
                        style={{ width: "100%" }}
                      />
                    </div>
                    <div>
                      <span>DB Col (1-6)</span>
                      <input
                        className="sf-input"
                        value={csvDbPos}
                        onChange={(e) => setCsvDbPos(e.target.value)}
                        style={{ width: "100%" }}
                      />
                    </div>
                    <div>
                      <span>Schema Col (1-6)</span>
                      <input
                        className="sf-input"
                        value={csvSchemaPos}
                        onChange={(e) => setCsvSchemaPos(e.target.value)}
                        style={{ width: "100%" }}
                      />
                    </div>
                    <div>
                      <span>Type Col (1-6)</span>
                      <input
                        className="sf-input"
                        value={csvTypePos}
                        onChange={(e) => setCsvTypePos(e.target.value)}
                        style={{ width: "100%" }}
                      />
                    </div>
                    <div>
                      <span>Name Col (1-6)</span>
                      <input
                        className="sf-input"
                        value={csvNamePos}
                        onChange={(e) => setCsvNamePos(e.target.value)}
                        style={{ width: "100%" }}
                      />
                    </div>
                    <div>
                      <span>Code Col (1-6)</span>
                      <input
                        className="sf-input"
                        value={csvCodePos}
                        onChange={(e) => setCsvCodePos(e.target.value)}
                        style={{ width: "100%" }}
                      />
                    </div>
                    <div>
                      <span>Comment Col (1-6)</span>
                      <input
                        className="sf-input"
                        value={csvCommentPos}
                        onChange={(e) => setCsvCommentPos(e.target.value)}
                        style={{ width: "100%" }}
                      />
                    </div>
                  </div>
                )}
              </div>
            </div>
          )}

          {/* Live JDBC Connection Form (16 Vendors + RSA JSEncrypt Password + Test Connection) */}
          {isDatabaseSource && (
            <div
              data-testid="jdbc-connection-section"
              style={{
                border: "1px solid #cbd5e1",
                borderRadius: 6,
                padding: 12,
                background: "#f8fafc"
              }}
            >
              <div style={{ display: "grid", gridTemplateColumns: "repeat(3, 1fr)", gap: 8 }}>
                <div>
                  <label style={{ display: "block", fontWeight: 600, marginBottom: 3 }}>
                    JDBC Vendor ({EXTENDED_JDBC_VENDORS.length})
                  </label>
                  <select
                    aria-label="JDBC Vendor"
                    data-testid="jdbc-vendor-select"
                    className="sf-select"
                    value={jdbcVendorKey}
                    onChange={(e) => handleVendorSelect(e.target.value)}
                    style={{ width: "100%" }}
                  >
                    {EXTENDED_JDBC_VENDORS.map((v) => (
                      <option key={v.shortKey} value={v.shortKey}>
                        {v.label}
                      </option>
                    ))}
                  </select>
                </div>
                <div>
                  <label style={{ display: "block", fontWeight: 600, marginBottom: 3 }}>
                    Hostname
                  </label>
                  <input
                    data-testid="jdbc-hostname-input"
                    className="sf-input"
                    value={hostname}
                    onChange={(e) => setHostname(e.target.value)}
                    style={{ width: "100%" }}
                  />
                </div>
                <div>
                  <label style={{ display: "block", fontWeight: 600, marginBottom: 3 }}>Port</label>
                  <input
                    data-testid="jdbc-port-input"
                    className="sf-input"
                    value={port}
                    onChange={(e) => setPort(e.target.value)}
                    style={{ width: "100%" }}
                  />
                </div>
                <div>
                  <label style={{ display: "block", fontWeight: 600, marginBottom: 3 }}>
                    {activeJdbcMeta.databaseFieldLabel}
                  </label>
                  <input
                    data-testid="jdbc-database-input"
                    className="sf-input"
                    required={activeJdbcMeta.databaseRequired}
                    value={database}
                    onChange={(e) => setDatabase(e.target.value)}
                    style={{ width: "100%" }}
                  />
                </div>
                <div>
                  <label style={{ display: "block", fontWeight: 600, marginBottom: 3 }}>
                    Account (Username)
                  </label>
                  <input
                    data-testid="jdbc-account-input"
                    className="sf-input"
                    value={account}
                    onChange={(e) => setAccount(e.target.value)}
                    style={{ width: "100%" }}
                  />
                </div>
                <div>
                  <label style={{ display: "block", fontWeight: 600, marginBottom: 3 }}>
                    Password (RSA Encrypted)
                  </label>
                  <input
                    type="password"
                    data-testid="jdbc-password-input"
                    className="sf-input"
                    value={password}
                    onChange={(e) => setPassword(e.target.value)}
                    style={{ width: "100%" }}
                  />
                </div>
              </div>

              <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: 8, marginTop: 8 }}>
                <input
                  placeholder="Included DBs/Schemas (e.g. DW.PUBLIC)"
                  className="sf-input"
                  value={extractedDbsSchemas}
                  onChange={(e) => setExtractedDbsSchemas(e.target.value)}
                />
                <input
                  placeholder="Excluded DBs/Schemas (e.g. SYS,INFORMATION_SCHEMA)"
                  className="sf-input"
                  value={excludedDbsSchemas}
                  onChange={(e) => setExcludedDbsSchemas(e.target.value)}
                />
              </div>

              <div
                style={{
                  display: "flex",
                  alignItems: "center",
                  justifyContent: "space-between",
                  marginTop: 8
                }}
              >
                <div style={{ display: "flex", gap: 12 }}>
                  <label style={{ display: "inline-flex", alignItems: "center", gap: 4 }}>
                    <input
                      type="checkbox"
                      checked={extractedStoredProcedures}
                      onChange={(e) => setExtractedStoredProcedures(e.target.checked)}
                    />
                    Extract Stored Procedures
                  </label>
                  <label style={{ display: "inline-flex", alignItems: "center", gap: 4 }}>
                    <input
                      type="checkbox"
                      checked={extractedViews}
                      onChange={(e) => setExtractedViews(e.target.checked)}
                    />
                    Extract Views
                  </label>
                </div>
                <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
                  {testStatus && (
                    <span data-testid="jdbc-test-status" style={{ fontSize: 11, color: "#0369a1" }}>
                      {testStatus}
                    </span>
                  )}
                  <button
                    type="button"
                    data-testid="jdbc-test-connection-btn"
                    className="sf-btn sf-btn--light"
                    onClick={() => void handleTestConnection()}
                  >
                    Test Connection
                  </button>
                </div>
              </div>
            </div>
          )}

          {/* Snowflake Query History 71 Query Types */}
          {sqlsource === "snowflakequeryhistory" && (
            <div style={{ border: "1px solid #cbd5e1", borderRadius: 6, padding: 10 }}>
              <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: 8, marginBottom: 8 }}>
                <div>
                  <label style={{ display: "block", fontWeight: 600 }}>Lookback Window (min)</label>
                  <input
                    type="number"
                    className="sf-input"
                    value={snowflakeBlockMinutes}
                    onChange={(e) => setSnowflakeBlockMinutes(Number(e.target.value) || 60)}
                    style={{ width: "100%" }}
                  />
                </div>
                <div>
                  <label style={{ display: "block", fontWeight: 600 }}>Snowflake Role</label>
                  <input
                    className="sf-input"
                    value={snowflakeRole}
                    onChange={(e) => setSnowflakeRole(e.target.value)}
                    style={{ width: "100%" }}
                  />
                </div>
              </div>
              <div style={{ fontWeight: 600, marginBottom: 4 }}>
                Snowflake QUERY_TYPE Filter ({SNOWFLAKE_QUERY_TYPES.length} available)
              </div>
              <select
                multiple
                data-testid="snowflake-query-types-select"
                value={selectedSnowflakeTypes}
                onChange={(e) =>
                  setSelectedSnowflakeTypes(
                    Array.from(e.target.selectedOptions).map((o) => o.value)
                  )
                }
                style={{ width: "100%", height: 95, padding: 4, borderRadius: 4 }}
              >
                {SNOWFLAKE_QUERY_TYPES.map((qt) => (
                  <option key={qt} value={qt}>
                    {qt}
                  </option>
                ))}
              </select>
            </div>
          )}

          {/* Persist & Cron Schedule */}
          <div style={{ display: "flex", flexWrap: "wrap", alignItems: "center", gap: 16 }}>
            <label style={{ display: "inline-flex", alignItems: "center", gap: 6 }}>
              <input
                type="checkbox"
                data-testid="persist-job-checkbox"
                checked={persistJob}
                onChange={(e) => setPersistJob(e.target.checked)}
              />
              Persist Metadata Job (<code>submitPersistJob</code>)
            </label>
            <label style={{ display: "inline-flex", alignItems: "center", gap: 6 }}>
              <input
                type="checkbox"
                data-testid="schedule-job-checkbox"
                checked={schedulable}
                onChange={(e) => setSchedulable(e.target.checked)}
              />
              Enable Recurring Cron Schedule
            </label>
          </div>

          {schedulable && (
            <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: 8 }}>
              <input
                data-testid="cron-expression-input"
                className="sf-input"
                value={cronExpression}
                onChange={(e) => setCronExpression(e.target.value)}
                placeholder="0 0 2 * * ?"
              />
              <select
                className="sf-select"
                value={cronExpression}
                onChange={(e) => setCronExpression(e.target.value)}
              >
                {CRON_SCHEDULE_PRESETS.map((p) => (
                  <option key={p.cron} value={p.cron}>
                    {p.label} ({p.cron})
                  </option>
                ))}
              </select>
            </div>
          )}
        </div>

        <div className="sf-modal-footer">
          <button type="button" className="sf-btn sf-btn--light" onClick={onClose}>
            Cancel
          </button>
          <button
            type="submit"
            data-testid="submit-create-job-btn"
            className="sf-btn sf-btn--primary"
            disabled={submitting}
          >
            {submitting ? "Submitting..." : "Create Job"}
          </button>
        </div>
      </form>
    </div>
  );
};
