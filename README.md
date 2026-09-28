# dsh-java-nginx · Nginx Composer（Nginx 配置工坊）

> DSH 独立 AI 产品矩阵 #21 · 端口 **8112** · 形态参照 `dsh-java-mysql`：独立工具型产品，
> 本身是完整可用的 Nginx 配置后台 + DSH Java Native 插件（AI 配置审查员），而非"业务系统 + 右侧 AI 助手"。

## 产品定位

给 Nginx 运维/开发做**纯文本配置工作台**：解析、路由表、错误 lint、location 片段生成、TLS 清单。

- **conf 解析**：server 块 → server_name / listen / location(path → type/target) 结构化；
- **路由表**：host+path → upstream/静态目录 一览，检测同一 host+path 重复定义（会被覆盖）；
- **lint**：缺分号、花括号配平、proxy_pass 带路径的斜杠语义、正则/前缀 location 顺序、拼写错误（worker_process）；
- **location 生成**：按意图出片段——static（alias+404）/ proxy（标准转发头）/ spa（try_files fallback）/ websocket（Upgrade+长超时）/ api-fallback（next_upstream 容错）；
- **TLS 清单**：证书获取→443 监听→协议版本→HSTS→OCSP→续期 10 步 checklist + 443/301 server 块参考片段。

AI 的角色是"熟练的配置审查员"：一句话找配置问题、解释路由、生成标准片段。

## AI 工具（插件 `dsh-java-nginx-plugin`，前缀 `plugin__dsh-java-nginx-plugin__nx_*`）

| 工具 | 作用 |
|------|------|
| `nx_parse_conf` | conf 文本 → server/location 结构 |
| `nx_route_table` | 路由表 + 重复路由检测 |
| `nx_lint_rules` | 常见错误 lint（文本近似） |
| `nx_gen_location` | 按意图生成 location 片段 |
| `nx_tls_checklist` | 域名 → TLS checklist + 片段 |

## 安全红线

- **不写回任何 conf 文件、不 reload、不执行任何 nginx 命令**——全部纯文本分析/生成；
- lint 结果强制注明「以 nginx -t 实测为准」；
- systemPrompt + PRE_TOOL_USE hook 双重约束。

## 架构

```
nginx-app (Spring Boot :8112)                nginx-plugin (DSH Java Native Plugin)
├─ POST /api/nginx/parse            ─┐       ├─ DshNginxPlugin extends AbstractHarnessPlugin
├─ POST /api/nginx/routes            │ 同一  │   ├─ 5 × AbstractTool (nx_*)
├─ POST /api/nginx/lint              │ Nginx │   ├─ systemPrompt（配置审查员人设 + 红线）
├─ POST /api/nginx/gen-location      │ Service  ├─ PRE_TOOL_USE hook（审计上下文注入）
├─ POST /api/nginx/tls-checklist     └─       └─ SPI: META-INF/services/...JavaHarnessPlugin
└─ POST /api/assistant/stream ──→ harness :8090 /api/agent/stream（SSE 转发）
```

UI：`static/index.html` + `app.js`（会话管理、SSE 流式渲染、解析/路由/lint/生成四工作台、TLS 清单弹窗）。

## 运行

```bash
# 1. 启动 app（harness 8090 需已在运行）
SERVER__PORT= java -jar dsh-java-nginx-app/target/*.jar --server.port=8112

# 2. 安装并激活插件
curl --noproxy '*' -X POST http://127.0.0.1:8090/api/harness/plugins/install -H 'Content-Type: application/json' -d '{
  "pluginId":"dsh-java-nginx-plugin","jarName":"dsh-java-nginx-plugin-0.1.0.jar",
  "displayName":"Nginx Composer","pluginVersion":"0.1.0","runtimeType":"JAVA_NATIVE",
  "sourcePath":"<abs-path>/dsh-java-nginx-plugin-0.1.0.jar",
  "entrypoint":"cn.xiaofuge.dsh.nx.plugin.DshNginxPlugin","installMode":"COPY"}'
curl --noproxy '*' -X POST http://127.0.0.1:8090/api/harness/plugins/activate -H 'Content-Type: application/json' \
  -d '{"pluginId":"dsh-java-nginx-plugin"}'

# 3. 打开 http://127.0.0.1:8112
```

## E2E

`nginx_composer_e2e.py` — 8 用例 / 21 断言，全部通过：

| 用例 | 验证点 |
|------|--------|
| T0 | 解析 2 个 server 块，server_name 识别 |
| T1 | 路由表 ≥4 条，/api 路由提取 |
| T2 | lint 命中缺分号 + proxy_pass 路径语义 + location 顺序 |
| T3 | proxy/spa/websocket/static 四种 location 片段生成正确 |
| T4 | TLS checklist 10 条，片段含 443 ssl + 301 跳转 |
| T5 | 空 conf → 400 |
| T6 | AI 调用 nx_lint_rules（SSE step_break 断言） |
| T7 | AI 对改 conf+reload 请求给出「nginx -t 先验证、生成文本供人工核对」的安全响应 |

## 截图

- `screenshots/01-home.png` — Nginx 配置工坊首页
- `screenshots/02-ai-drawer.png` — AI 对话 lint（调用 nx_* 工具）
- `screenshots/03-safety.png` — TLS 清单（只生成文本，不写文件不 reload）
