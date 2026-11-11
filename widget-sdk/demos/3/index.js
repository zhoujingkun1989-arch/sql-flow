(async function () {
  "use strict";

  var logEl = document.getElementById("event-log");
  var counterEl = document.getElementById("event-counter");
  var toggleBtn = document.getElementById("toggle-listener-btn");
  var count = 0;
  var listening = true;

  function appendLog(eventName, payload) {
    if (!listening) return;
    count += 1;
    counterEl.textContent = count + " events captured";
    var row = document.createElement("div");
    row.className = "demo-log-entry";
    row.setAttribute("data-event-name", eventName);
    var ts = new Date().toISOString().slice(11, 23);
    row.innerHTML =
      '<div style="color:#38bdf8;font-weight:700;margin-bottom:3px;">[' +
      ts +
      "] " +
      eventName +
      "</div>" +
      "<div>" +
      JSON.stringify(payload) +
      "</div>";
    logEl.insertBefore(row, logEl.firstChild);
  }

  var widget = await window.SQLFlow.init({
    container: "#sqlflow",
    minimap: true,
    search: true,
    onReady: function (e) {
      appendLog("ready", { version: e.version, hasIframe: Boolean(e.iframe) });
    },
    onFieldClick: function (field) {
      appendLog("onFieldClick", field);
    },
    onTableNameClick: function (tbl) {
      appendLog("onTableNameClick", tbl);
    }
  });
  window.sqlflowWidget = widget;

  widget
    .on("nodeClick", function (e) {
      appendLog("nodeClick", { nodeId: e.nodeId, schema: e.schema, table: e.table });
    })
    .on("columnClick", function (e) {
      appendLog("columnClick", { table: e.table, column: e.column, dataType: e.dataType });
    })
    .on("edgeClick", function (e) {
      appendLog("edgeClick", { edgeId: e.edgeId, type: e.type, effectType: e.effectType });
    })
    .on("canvasClick", function (e) {
      appendLog("canvasClick", e);
    })
    .on("visualizeSuccess", function (e) {
      appendLog("visualizeSuccess", { kind: e.kind, nodeCount: e.nodeCount, edgeCount: e.edgeCount });
    });

  toggleBtn.addEventListener("click", function () {
    listening = !listening;
    toggleBtn.textContent = listening ? "Pause Listeners" : "Resume Listeners";
  });

  document.getElementById("clear-log-btn").addEventListener("click", function () {
    logEl.innerHTML = "";
    count = 0;
    counterEl.textContent = "0 events captured";
  });

  await widget.visualizeJson(window.SQLFlowDemoFixtures.ECOMMERCE_LINEAGE_JSON);
})();
