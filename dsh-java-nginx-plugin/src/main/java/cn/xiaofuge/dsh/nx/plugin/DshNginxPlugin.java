package cn.xiaofuge.dsh.nx.plugin;

import cn.xiaofuge.deepseek.harness.domain.model.entity.AbstractTool;
import cn.xiaofuge.deepseek.harness.domain.model.entity.ToolDefinition;
import cn.xiaofuge.deepseek.harness.domain.spi.AbstractHarnessPlugin;
import cn.xiaofuge.deepseek.harness.domain.spi.PluginContext;
import cn.xiaofuge.deepseek.harness.domain.spi.PluginHookResult;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

public class DshNginxPlugin extends AbstractHarnessPlugin {
    public static final String PLUGIN_ID = "dsh-java-nginx-plugin";

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    public DshNginxPlugin() {
        super(PLUGIN_ID);
    }

    @Override
    public List<ToolDefinition> tools() {
        return List.of(
                new ParseConfTool(),
                new RouteTableTool(),
                new LintTool(),
                new GenLocationTool(),
                new TlsChecklistTool()
        );
    }

    @Override
    public void configure(PluginContext context) {
        super.configure(context);
        context.registerSystemPrompt("nginx-nginxcomposer-capabilities", 20, """
                ## DSH Nginx Composer Plugin
                - 全部工具纯文本分析/生成，不读写任何 conf 文件，不执行 nginx 命令，不 reload。
                - lint 结果是文本近似检查，必须提醒用户以 nginx -t 实测为准。
                - 生成的 location 片段/TLS 片段仅供参考，上线前需人工审查。
                """);
        context.registerHook("PRE_TOOL_USE", (toolName, payloadJson) -> {
            if (toolName != null && toolName.startsWith("plugin__" + PLUGIN_ID + "__nx_")) {
                return PluginHookResult.context("DSH Nginx Composer audit: text-only analysis, never writes conf or reloads.");
            }
            return null;
        });
    }

    private String post(String path, String json, Map<String, Object> args) {
        String adminUrl = baseUrl(args);
        return send(HttpRequest.newBuilder(URI.create(adminUrl + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build());
    }

    private String baseUrl(Map<String, Object> args) {
        Object override = args == null ? null : args.get("adminBaseUrl");
        return override == null || String.valueOf(override).isBlank()
                ? System.getenv().getOrDefault("DSH_NGINX_ADMIN_URL", "http://127.0.0.1:8112")
                : String.valueOf(override);
    }

    private String send(HttpRequest request) {
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                return failJson(response.statusCode(), response.body());
            }
            return response.body();
        } catch (Exception e) {
            return failJson(0, e.getMessage());
        }
    }

    private String failJson(int status, String message) {
        return "{\"error\":true,\"status\":" + status + ",\"message\":\"" + json(message) + "\"}";
    }

    private String json(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    private String str(Map<String, Object> args, String key) {
        Object value = args == null ? null : args.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private String confBody(Map<String, Object> args) {
        return "{\"conf\":\"" + json(str(args, "conf")) + "\"}";
    }

    private class ParseConfTool extends AbstractTool {
        @Override public String name() { return "nx_parse_conf"; }
        @Override public String description() { return "解析 nginx conf 文本为结构化块：server_name/listen/location(path,type,target)。用户贴配置问「里面有什么」时先调用。"; }
        @Override public Map<String, Object> parameters() {
            return objectSchema()
                    .prop("conf", stringSchema("nginx conf 全文"))
                    .prop("adminBaseUrl", stringSchema("可选，Nginx Composer 后台地址，默认 http://127.0.0.1:8112"))
                    .required("conf")
                    .build();
        }
        @Override public boolean isConcurrencySafe(Object args) { return true; }
        @Override protected java.util.concurrent.CompletableFuture<cn.xiaofuge.deepseek.harness.domain.model.entity.ToolExecutionResult>
        run(Map<String, Object> args, cn.xiaofuge.deepseek.harness.domain.model.entity.ToolRunContext ctx) {
            return ok(post("/api/nginx/parse", confBody(args), args));
        }
    }

    private class RouteTableTool extends AbstractTool {
        @Override public String name() { return "nx_route_table"; }
        @Override public String description() { return "生成路由表：host+path → upstream/静态目录，并检测同一 host+path 重复定义（会被覆盖）。用户问「哪些域名路由到哪」时调用。"; }
        @Override public Map<String, Object> parameters() {
            return objectSchema()
                    .prop("conf", stringSchema("nginx conf 全文"))
                    .prop("adminBaseUrl", stringSchema("可选，后台地址"))
                    .required("conf")
                    .build();
        }
        @Override public boolean isConcurrencySafe(Object args) { return true; }
        @Override protected java.util.concurrent.CompletableFuture<cn.xiaofuge.deepseek.harness.domain.model.entity.ToolExecutionResult>
        run(Map<String, Object> args, cn.xiaofuge.deepseek.harness.domain.model.entity.ToolRunContext ctx) {
            return ok(post("/api/nginx/routes", confBody(args), args));
        }
    }

    private class LintTool extends AbstractTool {
        @Override public String name() { return "nx_lint_rules"; }
        @Override public String description() { return "nginx conf 常见错误 lint：缺分号、花括号配平、proxy_pass 路径斜杠语义、正则/前缀 location 顺序、拼写错误（worker_process）。结果为文本近似，需 nginx -t 验证。用户贴配置问「有什么问题」时调用。"; }
        @Override public Map<String, Object> parameters() {
            return objectSchema()
                    .prop("conf", stringSchema("nginx conf 全文"))
                    .prop("adminBaseUrl", stringSchema("可选，后台地址"))
                    .required("conf")
                    .build();
        }
        @Override public boolean isConcurrencySafe(Object args) { return true; }
        @Override protected java.util.concurrent.CompletableFuture<cn.xiaofuge.deepseek.harness.domain.model.entity.ToolExecutionResult>
        run(Map<String, Object> args, cn.xiaofuge.deepseek.harness.domain.model.entity.ToolRunContext ctx) {
            return ok(post("/api/nginx/lint", confBody(args), args));
        }
    }

    private class GenLocationTool extends AbstractTool {
        @Override public String name() { return "nx_gen_location"; }
        @Override public String description() { return "按意图生成 location 块文本：static（静态目录+404 fallback）/ proxy（反代+标准转发头）/ spa（try_files fallback index.html）/ websocket（Upgrade 头+长超时）/ api-fallback（next_upstream 容错）。只返回片段文本，不写文件。用户要「帮我写一段反代/静态配置」时调用。"; }
        @Override public Map<String, Object> parameters() {
            return objectSchema()
                    .prop("intent", stringSchema("意图：static | proxy | spa | websocket | api-fallback"))
                    .prop("target", stringSchema("目标：静态目录路径（static/spa）或 upstream 地址如 http://127.0.0.1:8080（proxy/websocket）"))
                    .prop("path", stringSchema("location 路径，默认 /"))
                    .prop("adminBaseUrl", stringSchema("可选，后台地址"))
                    .required("intent")
                    .required("target")
                    .build();
        }
        @Override public boolean isConcurrencySafe(Object args) { return true; }
        @Override protected java.util.concurrent.CompletableFuture<cn.xiaofuge.deepseek.harness.domain.model.entity.ToolExecutionResult>
        run(Map<String, Object> args, cn.xiaofuge.deepseek.harness.domain.model.entity.ToolRunContext ctx) {
            StringBuilder b = new StringBuilder("{");
            b.append("\"intent\":\"").append(json(str(args, "intent"))).append("\"");
            b.append(",\"target\":\"").append(json(str(args, "target"))).append("\"");
            String path = str(args, "path");
            if (!path.isBlank()) b.append(",\"path\":\"").append(json(path)).append("\"");
            b.append("}");
            return ok(post("/api/nginx/gen-location", b.toString(), args));
        }
    }

    private class TlsChecklistTool extends AbstractTool {
        @Override public String name() { return "nx_tls_checklist"; }
        @Override public String description() { return "按域名生成 HTTPS/TLS 配置 checklist（证书获取/443 监听/协议版本/HSTS/OCSP/续期）+ 可直接参考的 server 块片段。只输出文本，不写文件不 reload。用户问「怎么上 HTTPS」时调用。"; }
        @Override public Map<String, Object> parameters() {
            return objectSchema()
                    .prop("domain", stringSchema("域名，如 api.example.com"))
                    .prop("adminBaseUrl", stringSchema("可选，后台地址"))
                    .required("domain")
                    .build();
        }
        @Override public boolean isConcurrencySafe(Object args) { return true; }
        @Override protected java.util.concurrent.CompletableFuture<cn.xiaofuge.deepseek.harness.domain.model.entity.ToolExecutionResult>
        run(Map<String, Object> args, cn.xiaofuge.deepseek.harness.domain.model.entity.ToolRunContext ctx) {
            return ok(post("/api/nginx/tls-checklist", "{\"domain\":\"" + json(str(args, "domain")) + "\"}", args));
        }
    }
}
