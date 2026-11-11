import React, { useEffect, useState } from "react";
import {
  type CouponVo,
  type GroupUserVo,
  type GroupVo,
  type ProductItem,
  type ShareSummaryItem,
  SqlCodeEditor,
  type UserVo
} from "@sqlflow/ui-core";
import { AlertTriangle, Copy, KeyRound, Shield, Trash2 } from "lucide-react";
import { useI18n } from "../../i18n/I18nContext";
import { type AdminRequestLogRecord, type AdminSqlRecord } from "../../services/spaApi";
import { useApp } from "../../state/AppContext";
import { triggerBrowserDownload } from "../../utils/exporters";
import { CreateJobModal } from "./CreateJobModal";
import { JobDetailModal } from "./JobDetailModal";

export const SystemModalsHost: React.FC = () => {
  const {
    activeModal,
    setActiveModal,
    selectedJobForDetail,
    businessErrorBanner,
    clearBusinessErrorBanner,
    api,
    activeJobId,
    activeJobSummary,
    showToast
  } = useApp();

  return (
    <>
      {/* Global HTTP 200 Business Error Banner (`401`, `403`, `410`, `411..417`, `426`, `509`) */}
      {businessErrorBanner && (
        <div
          data-testid="business-error-banner"
          style={{
            position: "fixed",
            top: 56,
            right: 16,
            zIndex: 120,
            maxWidth: 420,
            background: "#fef2f2",
            border: "1px solid #ef4444",
            borderRadius: 8,
            padding: "10px 14px",
            boxShadow: "0 10px 25px rgba(0,0,0,0.18)",
            fontSize: 12,
            color: "#7f1d1d"
          }}
        >
          <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", gap: 8 }}>
            <strong style={{ display: "inline-flex", alignItems: "center", gap: 5 }}>
              <AlertTriangle size={14} />
              Code {businessErrorBanner.code}
            </strong>
            <button
              type="button"
              className="sf-btn sf-btn--light"
              style={{ height: 22, fontSize: 11 }}
              onClick={clearBusinessErrorBanner}
            >
              Dismiss
            </button>
          </div>
          <div style={{ marginTop: 4 }}>
            {businessErrorBanner.error ||
              businessErrorBanner.message ||
              "Backend reported a business status alert."}
          </div>
          {businessErrorBanner.machineCode && (
            <div style={{ marginTop: 4 }}>
              Machine Code: <code>{businessErrorBanner.machineCode}</code>
            </div>
          )}
          {businessErrorBanner.code === 410 && activeJobId && (
            <button
              type="button"
              data-testid="banner-rerun-job-btn"
              className="sf-btn sf-btn--primary"
              style={{ marginTop: 6, height: 24, fontSize: 11 }}
              onClick={async () => {
                await api.client.rerunJob(
                  activeJobId,
                  activeJobSummary?.jobName ?? activeJobId
                );
                clearBusinessErrorBanner();
                showToast("Job rerun triggered");
              }}
            >
              Rerun Stale Job
            </button>
          )}
        </div>
      )}

      {activeModal === "settings" && <SettingsModal onClose={() => setActiveModal(null)} />}
      {activeModal === "login" && <LoginRegisterModal onClose={() => setActiveModal(null)} />}
      {activeModal === "changePassword" && (
        <ChangePasswordModal onClose={() => setActiveModal(null)} />
      )}
      {activeModal === "account" && <AccountSecretKeyModal onClose={() => setActiveModal(null)} />}
      {activeModal === "share" && <SharePermalinkModal onClose={() => setActiveModal(null)} />}
      {activeModal === "shareManage" && <ShareManageModal onClose={() => setActiveModal(null)} />}
      {activeModal === "createTeam" && <CreateTeamModal onClose={() => setActiveModal(null)} />}
      {activeModal === "teamManage" && <TeamManageModal onClose={() => setActiveModal(null)} />}
      {activeModal === "license" && <LicenseVersionModal onClose={() => setActiveModal(null)} />}
      {activeModal === "pricing" && <PricingProductsModal onClose={() => setActiveModal(null)} />}
      {activeModal === "admin" && <AdminConsoleModal onClose={() => setActiveModal(null)} />}
      {activeModal === "createJob" && <CreateJobModal onClose={() => setActiveModal(null)} />}
      {activeModal === "jobDetail" && selectedJobForDetail && (
        <JobDetailModal job={selectedJobForDetail} onClose={() => setActiveModal(null)} />
      )}
    </>
  );
};

const SettingsModal: React.FC<{ onClose: () => void }> = ({ onClose }) => {
  const { t } = useI18n();
  const { settings, updateSettings, resetSettings } = useApp();

  return (
    <div className="sf-modal-backdrop" data-testid="settings-modal" onClick={onClose}>
      <div className="sf-modal-card" style={{ maxWidth: 520 }} onClick={(e) => e.stopPropagation()}>
        <div className="sf-modal-header">
          <span>{t("settings.title")}</span>
          <button type="button" className="sf-btn sf-btn--light" onClick={onClose}>
            Close
          </button>
        </div>
        <div className="sf-modal-body" style={{ display: "flex", flexDirection: "column", gap: 10, fontSize: 12 }}>
          <label style={{ display: "flex", alignItems: "center", gap: 8 }}>
            <input
              type="checkbox"
              data-testid="settings-ignore-recordset"
              checked={settings.ignoreRecordSet}
              onChange={(e) => void updateSettings({ ignoreRecordSet: e.target.checked })}
            />
            Ignore Intermediate RecordSets (`ignoreRecordSet`)
          </label>
          <label style={{ display: "flex", alignItems: "center", gap: 8 }}>
            <input
              type="checkbox"
              data-testid="settings-ignore-function"
              checked={settings.ignoreFunction}
              onChange={(e) => void updateSettings({ ignoreFunction: e.target.checked })}
            />
            Ignore Function Nodes (`ignoreFunction`)
          </label>
          <label style={{ display: "flex", alignItems: "center", gap: 8 }}>
            <input
              type="checkbox"
              checked={settings.showTransform}
              onChange={(e) => void updateSettings({ showTransform: e.target.checked })}
            />
            Show Transform Expressions `f(x)` (`showTransform`)
          </label>
          <label style={{ display: "flex", alignItems: "center", gap: 8 }}>
            <input
              type="checkbox"
              checked={settings.showConstantTable}
              onChange={(e) => void updateSettings({ showConstantTable: e.target.checked })}
            />
            Show Constant Literal Tables (`showConstantTable`)
          </label>
          <label style={{ display: "flex", alignItems: "center", gap: 8 }}>
            <input
              type="checkbox"
              checked={settings.treatArgumentsInCountFunctionAsDirectDataflow}
              onChange={(e) =>
                void updateSettings({
                  treatArgumentsInCountFunctionAsDirectDataflow: e.target.checked
                })
              }
            />
            Treat `COUNT()` Arguments as Direct Dataflow
          </label>
          <label style={{ display: "flex", alignItems: "center", gap: 8 }}>
            <input
              type="checkbox"
              data-testid="settings-join-v2"
              checked={settings.joinAnalysisV2}
              onChange={(e) => void updateSettings({ joinAnalysisV2: e.target.checked })}
            />
            Enable Join Analysis V2 (`joinAnalysisV2` in `localStorage`)
          </label>
          <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between" }}>
            <span>Layout Orientation:</span>
            <select
              className="sf-select"
              value={settings.rankdir}
              onChange={(e) => void updateSettings({ rankdir: e.target.value as "LR" | "TB" })}
            >
              <option value="LR">Left to Right (LR)</option>
              <option value="TB">Top to Bottom (TB)</option>
            </select>
          </div>
          <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between" }}>
            <span>Default Stream Distance (1..10):</span>
            <input
              type="number"
              min={1}
              max={10}
              className="sf-input"
              value={settings.defaultDistance}
              onChange={(e) =>
                void updateSettings({
                  defaultDistance: Math.min(10, Math.max(1, Number(e.target.value) || 3))
                })
              }
              style={{ width: 70 }}
            />
          </div>
        </div>
        <div className="sf-modal-footer">
          <button
            type="button"
            className="sf-btn sf-btn--light"
            onClick={() => void resetSettings()}
          >
            {t("settings.reset")}
          </button>
          <button
            type="button"
            data-testid="settings-save-btn"
            className="sf-btn sf-btn--primary"
            onClick={async () => {
              await updateSettings({}, true);
              onClose();
            }}
          >
            {t("settings.save")}
          </button>
        </div>
      </div>
    </div>
  );
};

/**
 * Login / Register Modal (`00 §3.3 #6`):
 * Uses `POST /user/register` with plaintext `{ userId, userEmail, password }`.
 */
const LoginRegisterModal: React.FC<{ onClose: () => void }> = ({ onClose }) => {
  const { api, applyAuthenticatedUser, showToast } = useApp();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [submitting, setSubmitting] = useState(false);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setSubmitting(true);
    try {
      const res = await api.client.login({
        userEmail: email,
        password
      });
      if (Number(res.code) === 200 && res.data) {
        applyAuthenticatedUser({
          userId: res.data.userId,
          userEmail: res.data.userEmail || email,
          userName: res.data.userName,
          token: res.data.token
        });
        showToast(`Signed in as ${res.data.userEmail || res.data.userId}`);
        onClose();
      } else {
        showToast(res.error ?? "Authentication failed");
      }
    } catch (err) {
      showToast(err instanceof Error ? err.message : "Authentication failed");
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="sf-modal-backdrop" data-testid="login-modal" onClick={onClose}>
      <form
        className="sf-modal-card"
        style={{ maxWidth: 420 }}
        onClick={(e) => e.stopPropagation()}
        onSubmit={(e) => void handleSubmit(e)}
      >
        <div className="sf-modal-header">
          <span>Sign In / Register</span>
          <button type="button" className="sf-btn sf-btn--light" onClick={onClose}>
            Close
          </button>
        </div>
        <div className="sf-modal-body" style={{ display: "flex", flexDirection: "column", gap: 10, fontSize: 12 }}>
          <div>
            <label style={{ display: "block", fontWeight: 600, marginBottom: 4 }}>Email</label>
            <input
              type="email"
              required
              data-testid="login-email-input"
              className="sf-input"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              style={{ width: "100%" }}
            />
          </div>
          <div>
            <label style={{ display: "block", fontWeight: 600, marginBottom: 4 }}>Password</label>
            <input
              type="password"
              required
              data-testid="login-password-input"
              className="sf-input"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              style={{ width: "100%" }}
            />
          </div>
        </div>
        <div className="sf-modal-footer">
          <button type="button" className="sf-btn sf-btn--light" onClick={onClose}>
            Cancel
          </button>
          <button
            type="submit"
            data-testid="login-submit-btn"
            className="sf-btn sf-btn--primary"
            disabled={submitting}
          >
            Sign In
          </button>
        </div>
      </form>
    </div>
  );
};

const ChangePasswordModal: React.FC<{ onClose: () => void }> = ({ onClose }) => {
  const { api, showToast } = useApp();
  const [oldPassword, setOldPassword] = useState("");
  const [newPassword, setNewPassword] = useState("");

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    const res = await api.client.modifyPassword({ oldPassword, newPassword });
    if (Number(res.code) === 200) {
      showToast("Password updated");
      onClose();
    } else {
      showToast(res.error ?? "Password update failed");
    }
  };

  return (
    <div className="sf-modal-backdrop" data-testid="change-password-modal" onClick={onClose}>
      <form
        className="sf-modal-card"
        style={{ maxWidth: 400 }}
        onClick={(e) => e.stopPropagation()}
        onSubmit={(e) => void handleSubmit(e)}
      >
        <div className="sf-modal-header">
          <span>Change Password</span>
          <button type="button" className="sf-btn sf-btn--light" onClick={onClose}>
            Close
          </button>
        </div>
        <div className="sf-modal-body" style={{ display: "flex", flexDirection: "column", gap: 10, fontSize: 12 }}>
          <input
            type="password"
            required
            placeholder="Current Password"
            className="sf-input"
            value={oldPassword}
            onChange={(e) => setOldPassword(e.target.value)}
          />
          <input
            type="password"
            required
            placeholder="New Password"
            className="sf-input"
            value={newPassword}
            onChange={(e) => setNewPassword(e.target.value)}
          />
        </div>
        <div className="sf-modal-footer">
          <button type="submit" className="sf-btn sf-btn--primary">
            Update Password
          </button>
        </div>
      </form>
    </div>
  );
};

/**
 * Account & API `secretKey` Modal (`00 §3.3 #3`):
 * `POST /user/generateSecretKey` & `POST /user/generateToken` return strict string `code === "200"`.
 */
const AccountSecretKeyModal: React.FC<{ onClose: () => void }> = ({ onClose }) => {
  const { api, currentUser, userProfile, showToast } = useApp();
  const [secretKey, setSecretKey] = useState(userProfile?.secretKey ?? currentUser.secretKey ?? "");
  const [apiToken, setApiToken] = useState("");
  const [couponCode, setCouponCode] = useState("");

  const handleGenerateKeyPair = async () => {
    const skRes = await api.client.generateSecretKey();
    if (skRes.code === "200" && skRes.secretKey) {
      setSecretKey(skRes.secretKey);
      const tokRes = await api.client.generateToken(skRes.secretKey);
      if (tokRes.code === "200" && tokRes.token) {
        setApiToken(tokRes.token);
      }
      showToast("Generated new API secretKey & token");
    } else {
      showToast(skRes.error ?? "Failed to generate secret key");
    }
  };

  const handleRedeemCoupon = async () => {
    if (!couponCode.trim()) return;
    const res = await api.applyUserCoupon(couponCode.trim());
    if (Number(res.code) === 200) {
      showToast("Coupon applied");
      setCouponCode("");
    } else {
      showToast(res.error ?? "Invalid coupon");
    }
  };

  return (
    <div className="sf-modal-backdrop" data-testid="account-modal" onClick={onClose}>
      <div className="sf-modal-card" style={{ maxWidth: 540 }} onClick={(e) => e.stopPropagation()}>
        <div className="sf-modal-header">
          <span>Account &amp; API Credentials</span>
          <button type="button" className="sf-btn sf-btn--light" onClick={onClose}>
            Close
          </button>
        </div>
        <div className="sf-modal-body" style={{ display: "flex", flexDirection: "column", gap: 12, fontSize: 12 }}>
          <div>
            <strong>User ID:</strong> <code>{currentUser.userId}</code>
          </div>
          <div>
            <strong>Email:</strong> {userProfile?.userEmail || currentUser.userEmail || "Guest"}
          </div>

          <div style={{ border: "1px solid #e2e8f0", borderRadius: 6, padding: 10 }}>
            <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center" }}>
              <strong>OpenAPI SecretKey &amp; Token</strong>
              <button
                type="button"
                data-testid="generate-secret-key-btn"
                className="sf-btn sf-btn--primary"
                onClick={() => void handleGenerateKeyPair()}
              >
                <KeyRound size={12} />
                Generate SecretKey
              </button>
            </div>
            {secretKey && (
              <div style={{ marginTop: 8 }}>
                <div>
                  SecretKey: <code data-testid="generated-secret-key">{secretKey}</code>
                </div>
                {apiToken && (
                  <div style={{ marginTop: 4 }}>
                    Token: <code data-testid="generated-api-token">{apiToken}</code>
                  </div>
                )}
              </div>
            )}
          </div>

          <div style={{ display: "flex", gap: 6 }}>
            <input
              placeholder="Redeem coupon code..."
              className="sf-input"
              value={couponCode}
              onChange={(e) => setCouponCode(e.target.value)}
              style={{ flex: 1 }}
            />
            <button
              type="button"
              className="sf-btn sf-btn--light"
              onClick={() => void handleRedeemCoupon()}
            >
              Apply Coupon
            </button>
          </div>
        </div>
      </div>
    </div>
  );
};

const SharePermalinkModal: React.FC<{ onClose: () => void }> = ({ onClose }) => {
  const { api, combinedSqlText, dialect, sessionId, activeRightTab, showToast } = useApp();
  const [shareUrl, setShareUrl] = useState("");
  const [permalinkUrl, setPermalinkUrl] = useState("");

  const originBase = typeof window !== "undefined" ? window.location.origin + window.location.pathname : "";

  const handleCreateShare = async () => {
    const res = await api.createShareGraph({
      sqltext: combinedSqlText,
      dbvendor: dialect,
      tableLevel: activeRightTab === "tableLineage",
      sessionId
    });
    const url = `${originBase}#/share?shareId=${encodeURIComponent(res.shareId)}${
      res.tableLevel ? "&tableLevel=true" : ""
    }`;
    setShareUrl(url);
    showToast("Share link generated");
  };

  const handleCreatePermalink = async () => {
    const res = await api.createPermalink({
      sqltext: combinedSqlText,
      dbvendor: dialect
    });
    const url = `${originBase}#/p/${encodeURIComponent(res.permalinkId)}`;
    setPermalinkUrl(url);
    showToast("Permalink generated");
  };

  return (
    <div className="sf-modal-backdrop" data-testid="share-modal" onClick={onClose}>
      <div className="sf-modal-card" style={{ maxWidth: 540 }} onClick={(e) => e.stopPropagation()}>
        <div className="sf-modal-header">
          <span>Share Lineage &amp; Permalink</span>
          <button type="button" className="sf-btn sf-btn--light" onClick={onClose}>
            Close
          </button>
        </div>
        <div className="sf-modal-body" style={{ display: "flex", flexDirection: "column", gap: 12, fontSize: 12 }}>
          <div style={{ display: "flex", gap: 8 }}>
            <button
              type="button"
              data-testid="create-share-link-btn"
              className="sf-btn sf-btn--primary"
              onClick={() => void handleCreateShare()}
            >
              Generate Share Link (`/#/share`)
            </button>
            <button
              type="button"
              data-testid="create-permalink-btn"
              className="sf-btn sf-btn--light"
              onClick={() => void handleCreatePermalink()}
            >
              Generate Permalink (`/#/p/:id`)
            </button>
          </div>
          {shareUrl && (
            <div style={{ display: "flex", gap: 6, alignItems: "center" }}>
              <input
                readOnly
                data-testid="generated-share-url"
                className="sf-input"
                value={shareUrl}
                style={{ flex: 1 }}
              />
              <button
                type="button"
                className="sf-btn sf-btn--light"
                onClick={() => void navigator.clipboard?.writeText?.(shareUrl)}
              >
                <Copy size={12} />
              </button>
            </div>
          )}
          {permalinkUrl && (
            <div style={{ display: "flex", gap: 6, alignItems: "center" }}>
              <input
                readOnly
                data-testid="generated-permalink-url"
                className="sf-input"
                value={permalinkUrl}
                style={{ flex: 1 }}
              />
              <button
                type="button"
                className="sf-btn sf-btn--light"
                onClick={() => void navigator.clipboard?.writeText?.(permalinkUrl)}
              >
                <Copy size={12} />
              </button>
            </div>
          )}
        </div>
      </div>
    </div>
  );
};

const ShareManageModal: React.FC<{ onClose: () => void }> = ({ onClose }) => {
  const { api, showToast } = useApp();
  const [shares, setShares] = useState<ShareSummaryItem[]>([]);

  const loadShares = async () => {
    try {
      const res = await api.client.displayUserShareSummary();
      setShares(res.data ?? []);
    } catch {
      setShares([]);
    }
  };

  useEffect(() => {
    void loadShares();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  return (
    <div className="sf-modal-backdrop" data-testid="share-manage-modal" onClick={onClose}>
      <div className="sf-modal-card" style={{ maxWidth: 560 }} onClick={(e) => e.stopPropagation()}>
        <div className="sf-modal-header">
          <span>Shared Graphs ({shares.length})</span>
          <button type="button" className="sf-btn sf-btn--light" onClick={onClose}>
            Close
          </button>
        </div>
        <div className="sf-modal-body" style={{ fontSize: 12 }}>
          {shares.length === 0 ? (
            <div style={{ color: "#64748b" }}>No shared lineage links created yet.</div>
          ) : (
            shares.map((s) => (
              <div
                key={s.shareId}
                style={{
                  display: "flex",
                  justifyContent: "space-between",
                  alignItems: "center",
                  padding: "6px 0",
                  borderBottom: "1px solid #f1f5f9"
                }}
              >
                <span>
                  <code>{s.shareId}</code> • {s.shareDate}
                </span>
                <button
                  type="button"
                  className="sf-btn sf-btn--light"
                  onClick={async () => {
                    await api.client.deleteUserShare(s.shareId);
                    showToast("Deleted share link");
                    await loadShares();
                  }}
                >
                  <Trash2 size={12} />
                </button>
              </div>
            ))
          )}
        </div>
      </div>
    </div>
  );
};

const CreateTeamModal: React.FC<{ onClose: () => void }> = ({ onClose }) => {
  const { api, showToast } = useApp();
  const [groupName, setGroupName] = useState("");
  const [quote, setQuote] = useState(10);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    const res = await api.createGroup(groupName, quote);
    if (Number(res.code) === 200) {
      showToast(`Created team "${groupName}"`);
      onClose();
    } else {
      showToast(res.error ?? "Failed to create team");
    }
  };

  return (
    <div className="sf-modal-backdrop" data-testid="create-team-modal" onClick={onClose}>
      <form
        className="sf-modal-card"
        style={{ maxWidth: 420 }}
        onClick={(e) => e.stopPropagation()}
        onSubmit={(e) => void handleSubmit(e)}
      >
        <div className="sf-modal-header">
          <span>Create Team / Group</span>
          <button type="button" className="sf-btn sf-btn--light" onClick={onClose}>
            Close
          </button>
        </div>
        <div className="sf-modal-body" style={{ display: "flex", flexDirection: "column", gap: 10, fontSize: 12 }}>
          <input
            required
            placeholder="Team Name"
            data-testid="team-name-input"
            className="sf-input"
            value={groupName}
            onChange={(e) => setGroupName(e.target.value)}
          />
          <input
            type="number"
            min={1}
            placeholder="Seat Quota"
            className="sf-input"
            value={quote}
            onChange={(e) => setQuote(Number(e.target.value) || 10)}
          />
        </div>
        <div className="sf-modal-footer">
          <button type="submit" data-testid="create-team-submit" className="sf-btn sf-btn--primary">
            Create Team
          </button>
        </div>
      </form>
    </div>
  );
};

const TeamManageModal: React.FC<{ onClose: () => void }> = ({ onClose }) => {
  const { api, showToast } = useApp();
  const [group, setGroup] = useState<GroupVo | null>(null);
  const [members, setMembers] = useState<GroupUserVo[]>([]);
  const [inviteEmail, setInviteEmail] = useState("");

  useEffect(() => {
    const load = async () => {
      try {
        const info = await api.getGroupInfo();
        if (info.data) {
          setGroup(info.data);
          const resolvedGroupId = info.data.groupId ?? info.data.userGroup;
          if (resolvedGroupId) {
            const users = await api.listGroupUsers(resolvedGroupId);
            setMembers(users.data ?? info.data.groupUsers ?? []);
          } else {
            setMembers(info.data.groupUsers ?? []);
          }
        }
      } catch {
        // Ignore
      }
    };
    void load();
  }, [api]);

  const activeGroupId = group ? group.groupId ?? group.userGroup : "";

  const handleAddMember = async () => {
    if (!group || !activeGroupId || !inviteEmail.trim()) return;
    await api.addGroupUser(activeGroupId, inviteEmail.trim());
    setInviteEmail("");
    const users = await api.listGroupUsers(activeGroupId);
    setMembers(users.data ?? []);
    showToast("Member added");
  };

  return (
    <div className="sf-modal-backdrop" data-testid="team-manage-modal" onClick={onClose}>
      <div className="sf-modal-card" style={{ maxWidth: 540 }} onClick={(e) => e.stopPropagation()}>
        <div className="sf-modal-header">
          <span>Team Management {group ? `— ${group.groupName}` : ""}</span>
          <button type="button" className="sf-btn sf-btn--light" onClick={onClose}>
            Close
          </button>
        </div>
        <div className="sf-modal-body" style={{ fontSize: 12, display: "flex", flexDirection: "column", gap: 10 }}>
          <div style={{ display: "flex", gap: 6 }}>
            <input
              placeholder="Invite member email..."
              className="sf-input"
              value={inviteEmail}
              onChange={(e) => setInviteEmail(e.target.value)}
              style={{ flex: 1 }}
            />
            <button
              type="button"
              className="sf-btn sf-btn--primary"
              onClick={() => void handleAddMember()}
            >
              Add Member
            </button>
          </div>
          {members.map((m) => (
            <div
              key={m.userEmail}
              style={{
                display: "flex",
                justifyContent: "space-between",
                padding: "6px 0",
                borderBottom: "1px solid #f1f5f9"
              }}
            >
              <span>
                {m.userEmail} (Role {m.userRole})
              </span>
              {group && activeGroupId && (
                <button
                  type="button"
                  className="sf-btn sf-btn--light"
                  onClick={async () => {
                    await api.removeGroupUser(activeGroupId, m.userEmail);
                    setMembers((prev) => prev.filter((x) => x.userEmail !== m.userEmail));
                  }}
                >
                  Remove
                </button>
              )}
            </div>
          ))}
        </div>
      </div>
    </div>
  );
};

const LicenseVersionModal: React.FC<{ onClose: () => void }> = ({ onClose }) => {
  const { api, versionInfo, licenseInfo, showToast } = useApp();
  const [file, setFile] = useState<File | null>(null);

  const handleUpload = async () => {
    if (!file) return;
    const res = await api.client.uploadLicense(file);
    if (Number(res.code) === 200) {
      showToast("License uploaded");
      onClose();
    } else {
      showToast(res.error ?? "License upload failed");
    }
  };

  return (
    <div className="sf-modal-backdrop" data-testid="license-modal" onClick={onClose}>
      <div className="sf-modal-card" style={{ maxWidth: 560 }} onClick={(e) => e.stopPropagation()}>
        <div className="sf-modal-header">
          <span>License &amp; Version Information</span>
          <button type="button" className="sf-btn sf-btn--light" onClick={onClose}>
            Close
          </button>
        </div>
        <div className="sf-modal-body" style={{ fontSize: 12, display: "flex", flexDirection: "column", gap: 10 }}>
          <div>
            <strong>Backend Version:</strong> {versionInfo?.backend?.version ?? "8.2.2.15"} •{" "}
            <strong>GSP Parser:</strong> {versionInfo?.gsp?.version ?? "3.0.9.5"} •{" "}
            <strong>Machine Code:</strong> <code>{versionInfo?.["machine.code"] ?? "N/A"}</code>
          </div>
          {licenseInfo && (
            <div style={{ border: "1px solid #e2e8f0", borderRadius: 6, padding: 10 }}>
              {Object.entries(licenseInfo).map(([k, v]) => (
                <div key={k} style={{ padding: "2px 0" }}>
                  <strong>{k}:</strong> {v}
                </div>
              ))}
            </div>
          )}
          <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
            <input
              type="file"
              data-testid="license-file-input"
              onChange={(e) => setFile(e.target.files?.[0] ?? null)}
            />
            <button
              type="button"
              data-testid="upload-license-btn"
              className="sf-btn sf-btn--primary"
              disabled={!file}
              onClick={() => void handleUpload()}
            >
              Upload License
            </button>
          </div>
        </div>
      </div>
    </div>
  );
};

/**
 * Pricing & Products Modal (`00 §3.3 #14`):
 * Reads top-level `res.products` from `POST /product/products`.
 */
const PricingProductsModal: React.FC<{ onClose: () => void }> = ({ onClose }) => {
  const { api } = useApp();
  const [products, setProducts] = useState<ProductItem[]>([]);

  useEffect(() => {
    void api
      .listProducts()
      .then(setProducts)
      .catch(() => setProducts([]));
  }, [api]);

  return (
    <div className="sf-modal-backdrop" data-testid="pricing-modal" onClick={onClose}>
      <div className="sf-modal-card" style={{ maxWidth: 680 }} onClick={(e) => e.stopPropagation()}>
        <div className="sf-modal-header">
          <span>SQLFlow Plans &amp; Pricing</span>
          <button type="button" className="sf-btn sf-btn--light" onClick={onClose}>
            Close
          </button>
        </div>
        <div className="sf-modal-body" style={{ display: "grid", gridTemplateColumns: "repeat(auto-fit, minmax(190px, 1fr))", gap: 12, fontSize: 12 }}>
          {products.length === 0 ? (
            <div style={{ color: "#64748b" }}>On-Premise Enterprise Edition active.</div>
          ) : (
            products.map((p) => (
              <div
                key={String(p.productId)}
                data-testid={`product-card-${p.productId}`}
                style={{
                  border: p.recommend ? "2px solid #2563eb" : "1px solid #cbd5e1",
                  borderRadius: 8,
                  padding: 12
                }}
              >
                <strong style={{ fontSize: 14 }}>{p.productName}</strong>
                <div style={{ fontSize: 18, fontWeight: 700, color: "#0f172a", margin: "6px 0" }}>
                  {p.productPrice}
                </div>
                <div style={{ color: "#64748b" }}>{p.productPeriod}</div>
                {p.description && <div style={{ marginTop: 6 }}>{p.description}</div>}
              </div>
            ))
          )}
        </div>
      </div>
    </div>
  );
};

/**
 * 4-Tab Admin Console (`Users`, `SQL Audit` incl. `downloadUserSql` query-on-POST, `API Requests`, `Coupons`).
 */
const AdminConsoleModal: React.FC<{ onClose: () => void }> = ({ onClose }) => {
  const { api, showToast } = useApp();
  const [tab, setTab] = useState<"users" | "sqls" | "requests" | "coupons">("users");
  const [users, setUsers] = useState<UserVo[]>([]);
  const [sqls, setSqls] = useState<AdminSqlRecord[]>([]);
  const [requests, setRequests] = useState<AdminRequestLogRecord[]>([]);
  const [coupons, setCoupons] = useState<CouponVo[]>([]);
  const [previewSql, setPreviewSql] = useState<string | null>(null);

  useEffect(() => {
    if (tab === "users") void api.listAdminUsers().then(setUsers).catch(() => undefined);
    if (tab === "sqls") void api.listAdminSqls().then(setSqls).catch(() => undefined);
    if (tab === "requests")
      void api.listAdminRequestLogs().then(setRequests).catch(() => undefined);
    if (tab === "coupons") void api.listAdminCoupons().then(setCoupons).catch(() => undefined);
  }, [api, tab]);

  return (
    <div className="sf-modal-backdrop" data-testid="admin-console-modal" onClick={onClose}>
      <div
        className="sf-modal-card"
        style={{ maxWidth: 820, height: "78vh" }}
        onClick={(e) => e.stopPropagation()}
      >
        <div className="sf-modal-header">
          <span style={{ display: "inline-flex", alignItems: "center", gap: 6 }}>
            <Shield size={15} />
            Admin Management Console
          </span>
          <button type="button" className="sf-btn sf-btn--light" onClick={onClose}>
            Close
          </button>
        </div>
        <div style={{ display: "flex", gap: 6, padding: "8px 14px", borderBottom: "1px solid #e2e8f0" }}>
          {(["users", "sqls", "requests", "coupons"] as const).map((k) => (
            <button
              key={k}
              type="button"
              data-testid={`admin-tab-${k}`}
              className={`sf-btn ${tab === k ? "sf-btn--primary" : "sf-btn--light"}`}
              onClick={() => setTab(k)}
            >
              {k.toUpperCase()}
            </button>
          ))}
        </div>
        <div className="sf-modal-body" style={{ fontSize: 12 }}>
          {tab === "users" && (
            <div>
              {users.map((u) => (
                <div
                  key={u.userId}
                  style={{
                    display: "flex",
                    justifyContent: "space-between",
                    padding: "6px 0",
                    borderBottom: "1px solid #f1f5f9"
                  }}
                >
                  <span>
                    <strong>{u.userEmail || u.userId}</strong> ({u.userType ?? "standard"})
                  </span>
                  <button
                    type="button"
                    className="sf-btn sf-btn--light"
                    onClick={async () => {
                      await api.deleteAdminUser(u);
                      setUsers((prev) => prev.filter((x) => x.userId !== u.userId));
                    }}
                  >
                    Delete
                  </button>
                </div>
              ))}
            </div>
          )}

          {tab === "sqls" && (
            <div>
              {sqls.map((rec, idx) => (
                <div
                  key={`${rec.userId}-${rec.fileName}-${idx}`}
                  style={{
                    display: "flex",
                    justifyContent: "space-between",
                    alignItems: "center",
                    padding: "6px 0",
                    borderBottom: "1px solid #f1f5f9"
                  }}
                >
                  <span>
                    {rec.userId} / <code>{rec.fileName}</code>
                  </span>
                  <div style={{ display: "flex", gap: 6 }}>
                    <button
                      type="button"
                      className="sf-btn sf-btn--light"
                      onClick={async () => {
                        const txt = await api.previewAdminUserSql(rec.userId, rec.fileName);
                        setPreviewSql(txt);
                      }}
                    >
                      Preview
                    </button>
                    <button
                      type="button"
                      data-testid={`admin-download-sql-${rec.fileName}`}
                      className="sf-btn sf-btn--light"
                      onClick={async () => {
                        const blob = await api.downloadAdminUserSql(rec.userId, rec.fileName);
                        triggerBrowserDownload(blob, rec.fileName, "text/plain");
                        showToast(`Downloaded ${rec.fileName}`);
                      }}
                    >
                      Download
                    </button>
                  </div>
                </div>
              ))}
              {previewSql !== null && (
                <div style={{ height: 180, marginTop: 10 }}>
                  <SqlCodeEditor value={previewSql} readOnly={true} height="100%" />
                </div>
              )}
            </div>
          )}

          {tab === "requests" && (
            <div>
              {requests.map((r, idx) => (
                <div key={r.id ?? idx} style={{ padding: "5px 0", borderBottom: "1px solid #f1f5f9" }}>
                  <code>{r.endpoint}</code> • {r.userId} • {r.requestTime}
                </div>
              ))}
            </div>
          )}

          {tab === "coupons" && (
            <div>
              <button
                type="button"
                data-testid="admin-create-coupons-btn"
                className="sf-btn sf-btn--primary"
                style={{ marginBottom: 10 }}
                onClick={async () => {
                  await api.batchCreateCoupons(5, 30, "2027-12-31");
                  const updated = await api.listAdminCoupons();
                  setCoupons(updated);
                  showToast("Created 5 coupons");
                }}
              >
                Batch Create 5 Coupons
              </button>
              {coupons.map((c) => (
                <div
                  key={c.code}
                  style={{
                    display: "flex",
                    justifyContent: "space-between",
                    padding: "5px 0",
                    borderBottom: "1px solid #f1f5f9"
                  }}
                >
                  <span>
                    <code>{c.code}</code> ({c.period} days, exp {c.expireDate})
                  </span>
                  <button
                    type="button"
                    className="sf-btn sf-btn--light"
                    onClick={async () => {
                      await api.deleteCoupon(c.code);
                      setCoupons((prev) => prev.filter((x) => x.code !== c.code));
                    }}
                  >
                    Delete
                  </button>
                </div>
              ))}
            </div>
          )}
        </div>
      </div>
    </div>
  );
};
