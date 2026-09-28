package cn.xiaofuge.dsh.nx.controller;

import cn.xiaofuge.dsh.nx.config.NginxProperties;
import cn.xiaofuge.dsh.nx.model.ApiException;
import cn.xiaofuge.dsh.nx.service.NginxService;
import cn.xiaofuge.dsh.nx.service.HarnessAiStreamService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

@RestController
public class NginxController {
    private final NginxService nginxService;
    private final HarnessAiStreamService harnessAiStreamService;
    private final NginxProperties properties;

    public NginxController(NginxService nginxService,
                           HarnessAiStreamService harnessAiStreamService,
                           NginxProperties properties) {
        this.nginxService = nginxService;
        this.harnessAiStreamService = harnessAiStreamService;
        this.properties = properties;
    }

    @PostMapping("/api/nginx/parse")
    public Map<String, Object> parse(@RequestBody Map<String, String> request) {
        return nginxService.parseConf(request.getOrDefault("conf", ""));
    }

    @PostMapping("/api/nginx/routes")
    public Map<String, Object> routes(@RequestBody Map<String, String> request) {
        return nginxService.routeTable(request.getOrDefault("conf", ""));
    }

    @PostMapping("/api/nginx/lint")
    public Map<String, Object> lint(@RequestBody Map<String, String> request) {
        return nginxService.lintRules(request.getOrDefault("conf", ""));
    }

    public record GenRequest(String intent, String target, String path) {
    }

    @PostMapping("/api/nginx/gen-location")
    public Map<String, Object> genLocation(@RequestBody GenRequest request) {
        return nginxService.genLocation(request.intent(), request.target(), request.path());
    }

    @PostMapping("/api/nginx/tls-checklist")
    public Map<String, Object> tlsChecklist(@RequestBody Map<String, String> request) {
        return nginxService.tlsChecklist(request.getOrDefault("domain", ""));
    }

    @PostMapping(value = "/api/assistant/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter assistant(@RequestBody Map<String, String> request) {
        String agentId = request.getOrDefault("agentId", "");
        String message = request.getOrDefault("message", "");
        if (agentId.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "agentId 不能为空");
        }
        if (message.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "message 不能为空");
        }
        return harnessAiStreamService.stream(agentId, buildPrompt(message));
    }

    private String buildPrompt(String message) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("当前环境：\n");
        prompt.append("- Nginx Composer（Nginx 配置工坊）：解析/路由表/lint/location 生成/TLS 清单，全部纯文本分析\n");
        prompt.append("- 你可以使用以下配置工具获取真实结果（配置分析类问题必须先调用工具，禁止臆造配置内容）：\n");
        prompt.append("  - plugin__dsh-java-nginx-plugin__nx_parse_conf(conf)：解析 server/location 块结构\n");
        prompt.append("  - plugin__dsh-java-nginx-plugin__nx_route_table(conf)：路由表（host+path → upstream）+ 重复路由检测\n");
        prompt.append("  - plugin__dsh-java-nginx-plugin__nx_lint_rules(conf)：常见错误 lint（缺分号/括号配平/proxy_pass 斜杠/顺序）\n");
        prompt.append("  - plugin__dsh-java-nginx-plugin__nx_gen_location(intent, target, path)：生成 location 块（static/proxy/spa/websocket/api-fallback）\n");
        prompt.append("  - plugin__dsh-java-nginx-plugin__nx_tls_checklist(domain)：TLS 配置 checklist + 片段\n");
        prompt.append("\n用户需求：").append(message);
        prompt.append("\n\n回答要求：\n");
        prompt.append("- 使用中文，Markdown 格式，结论先行。\n");
        prompt.append("- 引用 lint/路由表结果时给出行号与关键片段，不要转储大段 JSON。\n");
        prompt.append("- 红线：不写回任何 conf 文件、不建议直接 reload 生产配置（提醒先 nginx -t 验证）；本工具不执行任何 nginx 命令。\n");
        return prompt.toString();
    }
}
