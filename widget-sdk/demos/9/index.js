(async function () {
  "use strict";

  var fixtures = window.SQLFlowDemoFixtures;
  var jobSelect = document.getElementById("job-select");
  var treeEl = document.getElementById("schema-tree");
  var statusEl = document.getElementById("job-status");

  var fallbackJobs = [
    {
      jobId: "demo_job_ecom_nightly",
      jobName: "nightly_ecommerce_warehouse (Demo)",
      dbVendor: "dbvsnowflake",
      status: "success",
      payload: fixtures.ECOMMERCE_LINEAGE_JSON
    },
    {
      jobId: "demo_job_ifrs_gl",
      jobName: "monthly_ifrs_trial_balance (Demo)",
      dbVendor: "dbvpostgresql",
      status: "success",
      payload: fixtures.FINANCE_LEDGER_JSON
    }
  ];

  var liveJobs = [];

  var widget = await window.SQLFlow.init({
    container: "#sqlflow",
    toolbar: true,
    minimap: true,
    search: true
  });
  window.sqlflowWidget = widget;

  widget.on("jobSelect", function (evt) {
    statusEl.textContent = "Selected Job ID: " + evt.jobId;
  });

  function renderTree() {
    var nodes = widget.job.lineage.getNodes();
    treeEl.innerHTML = "";
    nodes.forEach(function (dbNode) {
      var dbWrap = document.createElement("div");
      dbWrap.style.marginBottom = "8px";
      dbWrap.innerHTML =
        '<div style="font-weight:700;color:#0f172a;">🗄 ' + dbNode.title + "</div>";
      (dbNode.children || []).forEach(function (scNode) {
        var scDiv = document.createElement("div");
        scDiv.style.paddingLeft = "12px";
        scDiv.innerHTML =
          '<div style="font-weight:600;color:#475569;margin-top:4px;">📂 ' +
          scNode.title +
          "</div>";
        (scNode.children || []).forEach(function (tblNode) {
          var btn = document.createElement("button");
          btn.type = "button";
          btn.className = "demo-btn";
          btn.style.cssText =
            "display:block;width:100%;text-align:left;margin:3px 0 3px 10px;height:26px;";
          btn.textContent = "▤ " + tblNode.title;
          btn.addEventListener("click", function () {
            widget.highlightTable(tblNode.title);
          });
          scDiv.appendChild(btn);
        });
        dbWrap.appendChild(scDiv);
      });
      treeEl.appendChild(dbWrap);
    });
  }

  async function populateJobs() {
    jobSelect.innerHTML = "";
    try {
      liveJobs = await widget.job.list.get();
    } catch (_err) {
      liveJobs = [];
    }
    var list = liveJobs.length > 0 ? liveJobs : fallbackJobs;
    list.forEach(function (j) {
      var opt = document.createElement("option");
      opt.value = j.jobId;
      opt.textContent = j.jobName + " [" + j.status + "]";
      jobSelect.appendChild(opt);
    });
    statusEl.className = liveJobs.length > 0 ? "demo-status" : "demo-status demo-status-warn";
    statusEl.textContent =
      liveJobs.length > 0
        ? "Loaded " + liveJobs.length + " live backend jobs"
        : "Using sample persisted jobs (no live jobs found)";
  }

  async function loadSelectedJob() {
    var selectedId = jobSelect.value;
    var demoMatch = fallbackJobs.find(function (f) {
      return f.jobId === selectedId;
    });
    if (demoMatch && liveJobs.length === 0) {
      await widget.visualizeJson(demoMatch.payload);
      renderTree();
      statusEl.textContent = "Visualized sample job: " + demoMatch.jobName;
      return;
    }
    try {
      await widget.visualizeJob(selectedId);
      renderTree();
    } catch (_err) {
      await widget.visualizeJson(fixtures.ECOMMERCE_LINEAGE_JSON);
      renderTree();
    }
  }

  document.getElementById("load-job-btn").addEventListener("click", loadSelectedJob);
  document.getElementById("refresh-jobs-btn").addEventListener("click", async function () {
    await populateJobs();
    await loadSelectedJob();
  });

  await populateJobs();
  await loadSelectedJob();
})();
