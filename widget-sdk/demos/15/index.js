(async function () {
  "use strict";

  var fixtures = window.SQLFlowDemoFixtures;
  var enBtn = document.getElementById("lang-en-btn");
  var cnBtn = document.getElementById("lang-cn-btn");
  var statusEl = document.getElementById("i18n-status");

  var widget = await window.SQLFlow.init({
    container: "#sqlflow",
    lang: "cn",
    toolbar: true,
    editor: true,
    minimap: true,
    search: true,
    settings: true,
    export: true,
    sqlList: true
  });
  window.sqlflowWidget = widget;

  await widget.visualizeJson(fixtures.ECOMMERCE_LINEAGE_JSON, {
    sqltext: fixtures.ECOMMERCE_SQL
  });

  function applyLang(lang) {
    widget.setOptions({ lang: lang });
    enBtn.className = lang === "en" ? "demo-btn demo-btn-primary" : "demo-btn";
    cnBtn.className = lang === "cn" ? "demo-btn demo-btn-primary" : "demo-btn";
    statusEl.textContent =
      lang === "cn" ? "当前语言: 简体中文 (cn)" : "Active Language: English (en)";
  }

  enBtn.addEventListener("click", function () {
    applyLang("en");
  });
  cnBtn.addEventListener("click", function () {
    applyLang("cn");
  });
})();
