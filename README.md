# SQLFlow — 开源多方言 SQL 数据血缘与关联分析平台

[中文文档](#核心特性) | [English Documentation](#english-documentation)

全栈开源的企业级 **SQL 数据血缘分析（Column / Table Lineage）**、**ER 实体关系图**、**存储过程调用图** 与 **Join 关联风险诊断平台**。单端口（`:8165`）一体化提供中英双语 Web 工作台、RESTful OpenAPI 与可嵌入前端的 `SQLFlow.init(...)` Widget SDK。

---

## 核心特性

- **39+ 数据库 SQL 方言全覆盖**：支持 Oracle、MySQL、PostgreSQL、SQL Server、Snowflake、BigQuery、Hive、Spark SQL、ClickHouse、StarRocks、Doris、DuckDB、Trino、Presto、GaussDB、达梦（Dameng）、OceanBase、DB2、Teradata、Redshift、Greenplum、Vertica、SQLite 等主流数仓与数据库方言。
- **精细化字段级与表级血缘**：
  - 区分直接数据流（`fdd`）与 `WHERE` / `JOIN` / `GROUP BY` / `HAVING` 间接影响流（`fdr`）。
  - 支持 `f(x)` 转换计算表达式提取与源码行列级（1-based `[line, col]`）双向点击联动高亮。
  - 支持上下游逐跳展开/收敛、最左源头表溯源、隐藏中间结果集（`ignoreRecordSet`）与 `>200` 节点大图概览保护。
- **多维 SQL 架构视图**：
  - **ER 关系图**：主键/唯一键/外键（`PK` / `UK` / `FK`）可视化及 Mermaid Live 导出。
  - **调用关系图（Call Graph）**：存储过程、函数、包与触发器调用拓扑分析。
  - **Join 深度关联分析（JoinFlow V2）**：扇出放大、笛卡尔积、隐式类型转换、外连接过滤失效等 10 类风险诊断。
- **批处理采集与多格式导出**：
  - 内置 14 类 JDBC 元数据采集器及 `dbt`、PowerBI、Tableau、Informatica、SSIS、Sqoop、Erwin 解析器。
  - 支持导出 JSON、XML（`<dlineage>`）、15 列标准 CSV、GraphML、Parquet、高清 PNG 与离线交互式 HTML ZIP 包。
- **零外部中间件依赖**：单容器内嵌 Spring Boot 3.4（Java 17+）、SQLite（事务元数据）与 DuckDB（分析型血缘存储），开箱即用。

---

## 模块架构

```text
sql-flow/
├── contracts/             # 共享 Java 17 数据契约、方言枚举、XML/JSON 模型与 7 个 ServiceLoader SPI 接口
├── lineage-engine/        # 多方言 SQL AST 解析器与字段级/表级/ER/调用图血缘分析引擎
├── graph-layout/          # Sugiyama 分层有向图布局、JoinFlow V2 关联风险分析、GraphML 与 PNG/SVG 渲染导出
├── metadata-connectors/   # JDBC 库表元数据采集器、BI/ETL 多源解析器及 CSV/ZIP 导出实现
├── backend-service/       # Spring Boot 3.4 统一服务端 (:8165)、RSA/JWT/Bearer 鉴权、SQLite + DuckDB 持久化
├── ui-core/               # @sqlflow/ui-core — 共享 TypeScript 类型、39 方言目录、Dagre/三次贝塞尔几何引擎与通用画布组件
├── frontend-ui/           # @sqlflow/frontend-ui — React 18 中英双语交互式 Web 工作台与 JoinFlow 画布
├── widget-sdk/            # @sqlflow/widget-sdk — UMD (window.SQLFlow) & ESM 嵌入式组件 SDK 与 16 个交互示例
└── deploy/                # 多阶段 Dockerfile、容器启动与运维脚本 (bin/*.sh) 及 52 项 Playwright 端到端测试
```

---

## 快速开始（Docker 一键启动）

```bash
# 1. 构建镜像
docker build -f deploy/Dockerfile -t sqlflow:latest .

# 2. 启动容器（映射 8165 端口并持久化数据卷）
docker run -d \
  --name sqlflow \
  -p 8165:8165 \
  -v sqlflow_data:/sqlflow \
  sqlflow:latest
```

启动后即可访问：
- **Web 交互工作台**：`http://localhost:8165/`（右上角可一键切换 **中文 / EN**）
- **嵌入式 Widget 示例集（1~16）**：`http://localhost:8165/widget/1/index.html` ~ `http://localhost:8165/widget/16/index.html`
- **健康与版本检查**：`curl http://localhost:8165/version`

---

## 本地源码编译与运行

### 环境要求
- **JDK**：17 及以上
- **Maven**：3.9+
- **Node.js**：20+（推荐 22 LTS）与 **pnpm** `10.x`（可通过 `corepack enable` 启用）

### 编译打包

```bash
# 1. 安装前端依赖并构建 ui-core、frontend-ui 与 widget-sdk
pnpm install --frozen-lockfile
pnpm -r --if-present build

# 2. 编译并打包全部 5 个 Java 模块（生成 backend-service/target/backend-service-8.2.2.15.jar）
mvn test package
```

### 本地直接启动

```bash
STAGE_DIR=/tmp/sqlflow-runtime
mkdir -p "$STAGE_DIR"/{bin,conf,conf.defaults,lib,static,widget,data,log,tmp}

cp backend-service/target/backend-service-8.2.2.15.jar "$STAGE_DIR/lib/sqlflow-server.jar"
cp -R frontend-ui/dist/. "$STAGE_DIR/static/"
cp -R widget-sdk/dist/. "$STAGE_DIR/widget/"
cp -R deploy/conf/. "$STAGE_DIR/conf/"
cp -R deploy/conf/. "$STAGE_DIR/conf.defaults/"
cp -R deploy/bin/. "$STAGE_DIR/bin/"
cp deploy/docker-entrypoint.sh "$STAGE_DIR/docker-entrypoint.sh"
chmod +x "$STAGE_DIR/docker-entrypoint.sh" "$STAGE_DIR/bin/"*.sh

SQLFLOW_HOME="$STAGE_DIR" "$STAGE_DIR/docker-entrypoint.sh"
```

---

## REST API 直接调用指南

服务端同时兼容根路径、`/gspLive_backend/...` 与 `/api/gspLive_backend/...` 三种前缀，支持 `application/json`、表单参数与 `multipart/form-data` 文件上传。

### 1. 免密钥快速调用（内置本地访客身份）

```bash
# 解析 SQL 获取结构化字段级血缘 JSON（dbobjs + relationships）
curl -X POST http://localhost:8165/sqlflow/generation/sqlflow \
  -H "Content-Type: application/json" \
  -d '{
    "dbvendor": "dbvoracle",
    "sqltext": "CREATE VIEW v_emp AS SELECT e.emp_id, SUM(s.amount) AS total_amt FROM emp e JOIN sales s ON e.emp_id = s.emp_id GROUP BY e.emp_id;",
    "showRelationType": "fdd,fdr",
    "ignoreRecordSet": false,
    "showTransform": true
  }'

# 上传 .sql 或 .zip 脚本文件解析血缘
curl -X POST http://localhost:8165/sqlflow/generation/sqlflow \
  -F "dbvendor=dbvmysql" \
  -F "showTransform=true" \
  -F "sqlfiles=@./query.sql"
```

常用交互与导出接口：
- `POST /sqlflow/generation/sqlflow/graph`：返回带节点布局坐标的血缘图 `SqlflowJSON`
- `POST /sqlflow/generation/sqlflow/graph/table_level_lineage`：表级血缘图
- `POST /sqlflow/generation/sqlflow/erdiagramSelectGraph`：ER 实体关系图
- `POST /sqlflow/generation/sqlflow/graph/call_lineage`：存储过程/函数调用关系图
- `POST /api/sqlflow/join-analysis`：Join 深度关联与风险诊断（`join-analysis.v1`）
- `POST /sqlflow/generation/sqlflow/exportLineageAsCsv`：导出 15 列标准血缘 CSV
- `POST /sqlflow/generation/sqlflow/png`：导出服务端渲染血缘图 PNG
- `POST /sqlflow/generation/sqlflow/toXML`：导出 `<dlineage>` XML

### 2. 标准 `/v1/sqlflow/**` OpenAPI（`Bearer SecretKey` 鉴权）

```bash
# 1. 生成 API SecretKey
SECRET_KEY=$(curl -s -X POST http://localhost:8165/user/generateSecretKey \
  -H "Content-Type: application/json" -d '{}' | jq -r '.secretKey')

# 2. 携带 Bearer SecretKey 调用 /v1/sqlflow 接口
curl -X POST http://localhost:8165/v1/sqlflow/sqlflow \
  -H "Authorization: Bearer ${SECRET_KEY}" \
  -H "Content-Type: application/json" \
  -d '{
    "dbvendor": "dbvpostgresql",
    "sqltext": "INSERT INTO dwd.user_summary SELECT id, name FROM ods.users;"
  }'
```

---

## 嵌入式 Widget SDK (`SQLFlow.init`)

可在任意第三方 Web 应用中直接引入 UMD 资源或通过 ESM 集成交互式血缘画布：

```html
<link rel="stylesheet" href="http://localhost:8165/widget/sqlflow.widget.3.6.16.css" />
<script src="http://localhost:8165/widget/sqlflow.widget.3.6.16.js"></script>

<div id="lineage-container" style="width: 100%; height: 640px;"></div>
<script>
  (async () => {
    const sqlflow = await window.SQLFlow.init({
      container: document.getElementById("lineage-container"),
      width: "100%",
      height: 640,
      apiPrefix: "http://localhost:8165/gspLive_backend",
      component: true
    });

    await sqlflow.visualizeSQL(
      "SELECT c.id, o.total FROM customers c JOIN orders o ON c.id = o.customer_id;",
      "postgresql"
    );

    sqlflow.addEventListener("onFieldClick", (field) => {
      console.log("Clicked column:", field);
    });
  })();
</script>
```

---

## 运行测试

```bash
# 1. Java 全模块单元测试
mvn test

# 2. TypeScript 全工作区类型检查
pnpm -r --if-present typecheck

# 3. 前端与 Widget SDK 单元测试 (Vitest)
pnpm --filter @sqlflow/ui-core test
pnpm --filter @sqlflow/frontend-ui test
pnpm --filter @sqlflow/widget-sdk test

# 4. Playwright 端到端测试（需先启动 :8165 服务）
SQLFLOW_BASE_URL=http://localhost:8165 pnpm --filter @sqlflow/e2e test
```

---

## English Documentation

**SQLFlow** is an open-source, self-hosted multi-dialect SQL data lineage, ER diagram, routine call graph, and Join risk analysis platform. It serves a bilingual (English / 中文) React 18 Web SPA, REST APIs, and an embeddable `SQLFlow.init(...)` Widget SDK from a single unified Spring Boot 3.4 runtime on port `:8165`.

### Key Highlights
- **39+ SQL Dialects**: Oracle, MySQL, PostgreSQL, SQL Server, Snowflake, BigQuery, Hive, Spark SQL, ClickHouse, StarRocks, Doris, DuckDB, Trino, Presto, Dameng, OceanBase, GaussDB, DB2, Teradata, Redshift, and more.
- **Interactive Column & Table Lineage**: Direct (`fdd`) vs. indirect (`fdr`) dataflow tracing, `f(x)` SQL expression badges, bidirectional 1-based `[line, col]` token highlighting, and upstream/downstream graph slicing.
- **ER Diagrams, Call Graphs, & JoinFlow V2**: PK/UK/FK entity diagrams with Mermaid export, stored routine call graphs, and 10-rule SQL Join risk diagnostics.
- **Single-Container Deployment**: Embedded SQLite + DuckDB storage with zero external database or message-broker dependencies.

### Quick Start with Docker

```bash
docker build -f deploy/Dockerfile -t sqlflow:latest .
docker run -d --name sqlflow -p 8165:8165 -v sqlflow_data:/sqlflow sqlflow:latest
```

Open `http://localhost:8165/` in your browser, or query the REST API directly:

```bash
curl -X POST http://localhost:8165/sqlflow/generation/sqlflow \
  -H "Content-Type: application/json" \
  -d '{
    "dbvendor": "dbvpostgresql",
    "sqltext": "CREATE VIEW v_sales AS SELECT o.customer_id, SUM(o.amount) AS total_amt FROM orders o GROUP BY o.customer_id;",
    "showRelationType": "fdd,fdr",
    "showTransform": true
  }'
```

---

## License

Licensed under the [Apache License, Version 2.0](./LICENSE).
