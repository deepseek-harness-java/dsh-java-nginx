package cn.xiaofuge.dsh.nx.service;

import cn.xiaofuge.dsh.nx.model.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Nginx Composer Nginx 配置工坊（纯文本分析，不读写 conf、不 reload）：
 * - nx_parse_conf：解析 conf 文本 → server/location 块结构
 * - nx_route_table：路由表可视化（server_name + location → upstream/proxy_pass）
 * - nx_lint_rules：常见错误 lint（缺分号、proxy_pass 末尾斜杠、location 顺序、重复端口等）
 * - nx_gen_location：按意图生成 location 块（静态/反代/SPA fallback/websocket）
 * - nx_tls_checklist：TLS 配置 checklist（生成不执行）
 * 红线：不写回 conf 文件、不 reload、不执行 nginx 命令。
 */
@Service
public class NginxService {

    private static final Pattern SERVER_BLOCK = Pattern.compile("(?s)server\\s*\\{(.*)\\}\\s*(?=#\\s*end-server|$)");
    private static final Pattern LOCATION_BLOCK = Pattern.compile("(?s)location\\s+([^\\{]+)\\s*\\{(.*?)\\}");
    private static final Pattern SERVER_NAME = Pattern.compile("(?m)^\\s*server_name\\s+([^;]+);");
    private static final Pattern LISTEN = Pattern.compile("(?m)^\\s*listen\\s+([^;]+);");
    private static final Pattern PROXY_PASS = Pattern.compile("(?m)^\\s*proxy_pass\\s+([^;]+);");
    private static final Pattern ROOT = Pattern.compile("(?m)^\\s*root\\s+([^;]+);");
    private static final Pattern TRY_FILES = Pattern.compile("(?m)^\\s*try_files\\s+([^;]+);");

    public Map<String, Object> parseConf(String confText) {
        if (confText == null || confText.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "confText 不能为空");
        }
        List<Map<String, Object>> servers = new ArrayList<>();
        // 逐个 server { ... } 块解析（非贪婪配对）
        Matcher sm = Pattern.compile("(?s)server\\s*\\{(.*?)\\n\\}").matcher(confText);
        while (sm.find()) {
            String body = sm.group(1);
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("serverName", first(SERVER_NAME, body, "_"));
            s.put("listen", first(LISTEN, body, "80"));
            s.put("locations", extractLocations(body));
            servers.add(s);
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("serverCount", servers.size());
        r.put("servers", servers);
        r.put("httpBlockPresent", confText.trim().contains("http {") || confText.contains("server {"));
        r.put("note", "解析为结构近似，复杂嵌套以 nginx -T 实际输出为准");
        return r;
    }

    public Map<String, Object> routeTable(String confText) {
        if (confText == null || confText.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "confText 不能为空");
        }
        List<Map<String, Object>> routes = new ArrayList<>();
        Matcher sm = Pattern.compile("(?s)server\\s*\\{(.*?)\\n\\}").matcher(confText);
        while (sm.find()) {
            String body = sm.group(1);
            String serverName = first(SERVER_NAME, body, "_").trim();
            String listen = first(LISTEN, body, "80").trim();
            for (Map<String, Object> loc : extractLocations(body)) {
                Map<String, Object> route = new LinkedHashMap<>();
                route.put("host", serverName + ":" + listen);
                route.put("path", loc.get("path"));
                route.put("type", loc.get("type"));
                route.put("target", loc.get("target"));
                routes.add(route);
            }
        }
        // 检测重复 host+path
        List<Map<String, Object>> duplicates = new ArrayList<>();
        Map<String, Integer> seen = new LinkedHashMap<>();
        for (Map<String, Object> route : routes) {
            String key = route.get("host") + " " + route.get("path");
            int count = seen.merge(key, 1, Integer::sum);
            if (count == 2) {
                duplicates.add(Map.of("route", key, "issue", "同一 host+path 定义了多次，前者会被覆盖"));
            }
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("routeCount", routes.size());
        r.put("routes", routes);
        r.put("duplicateCount", duplicates.size());
        r.put("duplicates", duplicates);
        return r;
    }

    public Map<String, Object> lintRules(String confText) {
        if (confText == null || confText.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "confText 不能为空");
        }
        List<Map<String, Object>> issues = new ArrayList<>();
        String[] lines = confText.split("\n");
        int braceBalance = 0;
        boolean sawRegexLocation = false;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            // 括号配平
            for (char c : trimmed.toCharArray()) {
                if (c == '{') braceBalance++;
                if (c == '}') braceBalance--;
            }
            // 缺分号：非块行（不以 { 或 } 结尾）也不以 ; 结尾
            if (!trimmed.endsWith("{") && !trimmed.endsWith("}") && !trimmed.endsWith(";") && !trimmed.endsWith("\\")) {
                issues.add(Map.of("line", i + 1, "level", "warn", "msg", "指令缺分号: " + abbreviate(trimmed, 60)));
            }
            // proxy_pass 末尾斜杠语义
            Matcher pm = PROXY_PASS.matcher(trimmed);
            if (pm.find()) {
                String target = pm.group(1).trim();
                int schemeEnd = target.indexOf("//");
                if (schemeEnd >= 0 && !target.endsWith("/") && !target.contains("$")) {
                    int pathStart = target.indexOf('/', schemeEnd + 2);
                    if (pathStart > 0) {
                        String path = target.substring(pathStart);
                        if (path.length() > 1) {
                            issues.add(Map.of("line", i + 1, "level", "info", "msg",
                                    "proxy_pass 带路径（" + path + "）会替换整个 location 前缀，确认这是想要的行为"));
                        }
                    }
                }
            }
            // location 顺序：正则 location 出现后，又出现前缀 location
            Matcher lm = Pattern.compile("^location\\s+(.+?)\\s*\\{").matcher(trimmed);
            if (lm.find()) {
                String arg = lm.group(1).trim();
                boolean isRegex = arg.startsWith("~") || arg.startsWith("^~") || arg.startsWith("=");
                if (isRegex) sawRegexLocation = true;
                else if (sawRegexLocation && arg.startsWith("/")) {
                    issues.add(Map.of("line", i + 1, "level", "info", "msg",
                            "正则 location 之后才出现前缀 location（" + arg + "）；nginx 正则优先级更高，确认匹配顺序符合预期"));
                }
                if (arg.equals("/") && trimmed.contains("proxy_pass") == false && !TRY_FILES.matcher(confText).find()) {
                    // / location 无 fallback
                }
            }
            // worker_processes/keepalive 等常见拼写检查
            if (trimmed.startsWith("worker_process ") || trimmed.startsWith("workerprocess ")) {
                issues.add(Map.of("line", i + 1, "level", "warn", "msg", "疑似拼写错误：应为 worker_processes"));
            }
        }
        if (braceBalance != 0) {
            issues.add(Map.of("line", 0, "level", "warn",
                    "msg", "花括号不配平（差 " + braceBalance + " 个），块结构可能被破坏"));
        }
        // root 目录末尾斜杠/变量
        if (confText.contains("root ") && confText.matches("(?s).*root\\s+[^;\\s]+/.;.*")) {
            issues.add(Map.of("line", 0, "level", "info", "msg", "root 路径以 / 结尾可能与 $uri 拼出双斜杠"));
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("issueCount", issues.size());
        r.put("issues", issues.stream().limit(50).toList());
        r.put("note", "lint 为文本近似检查，最终以 nginx -t 为准（本工具不执行）");
        return r;
    }

    public Map<String, Object> genLocation(String intent, String target, String path) {
        if (intent == null || intent.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "intent 不能为空");
        }
        if (target == null || target.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "target 不能为空");
        }
        String loc = path == null || path.isBlank() ? "/" : path;
        String body;
        switch (intent.toLowerCase()) {
            case "static" -> body = """
                    location %s {
                        alias %s;
                        index index.html;
                        try_files $uri $uri/ =404;
                    }""".formatted(loc.endsWith("/") ? loc : loc + "/", target.endsWith("/") ? target : target + "/");
            case "proxy", "reverse" -> body = """
                    location %s {
                        proxy_pass %s;
                        proxy_set_header Host $host;
                        proxy_set_header X-Real-IP $remote_addr;
                        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
                        proxy_set_header X-Forwarded-Proto $scheme;
                    }""".formatted(loc, target.endsWith("/") ? target : target + "/");
            case "spa" -> body = """
                    location %s {
                        root %s;
                        index index.html;
                        try_files $uri $uri/ /index.html;
                    }""".formatted(loc, target);
            case "websocket", "ws" -> body = """
                    location %s {
                        proxy_pass %s;
                        proxy_http_version 1.1;
                        proxy_set_header Upgrade $http_upgrade;
                        proxy_set_header Connection "upgrade";
                        proxy_read_timeout 3600s;
                    }""".formatted(loc, target);
            case "api-fallback" -> body = """
                    location %s {
                        proxy_pass %s;
                        proxy_next_upstream error timeout http_502 http_503;
                        proxy_connect_timeout 5s;
                        proxy_read_timeout 30s;
                    }""".formatted(loc, target);
            default -> throw new ApiException(HttpStatus.BAD_REQUEST,
                    "未知 intent: " + intent + "（支持 static/proxy/spa/websocket/api-fallback）");
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("intent", intent);
        r.put("target", target);
        r.put("snippet", body);
        r.put("note", "片段仅生成文本，不会写入任何 conf 文件");
        return r;
    }

    public Map<String, Object> tlsChecklist(String domain) {
        if (domain == null || domain.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "domain 不能为空");
        }
        List<String> checklist = List.of(
                "1. 证书获取：certbot certonly --webroot -w <webroot> -d " + domain + "（或用云厂商免费证书）",
                "2. server 块监听 443 ssl http2；80 端口单独 server 块做 301 跳转",
                "3. ssl_certificate 指向 fullchain.pem（不是 cert.pem，否则部分客户端报链不完整）",
                "4. 协议：ssl_protocols TLSv1.2 TLSv1.3；禁 SSLv3/TLSv1.0/1.1",
                "5. 套件：ssl_ciphers 用 Mozilla intermediate 档；开启 ssl_prefer_server_ciphers off",
                "6. 会话复用：ssl_session_cache shared:SSL:10m; ssl_session_timeout 1d",
                "7. HSTS：add_header Strict-Transport-Security \"max-age=63072000\" always（确认全站 HTTPS 后再开）",
                "8. OCSP stapling：ssl_stapling on; ssl_stapling_verify on",
                "9. 续期验证：certbot renew --dry-run 通过 + crontab/systemd timer 挂 certbot renew",
                "10. 上线后用 SSL Labs 检测，目标 A 级以上"
        );
        String snippet = """
                server {
                    listen 443 ssl http2;
                    server_name %s;
                    ssl_certificate     /etc/letsencrypt/live/%s/fullchain.pem;
                    ssl_certificate_key /etc/letsencrypt/live/%s/privkey.pem;
                    ssl_protocols TLSv1.2 TLSv1.3;
                    ssl_session_cache shared:SSL:10m;
                    add_header Strict-Transport-Security "max-age=63072000" always;
                }

                server {
                    listen 80;
                    server_name %s;
                    return 301 https://$host$request_uri;
                }""".formatted(domain, domain, domain, domain);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("domain", domain);
        r.put("checklist", checklist);
        r.put("snippet", snippet);
        r.put("note", "仅生成配置文本与清单，不写文件、不 reload、不执行 certbot");
        return r;
    }

    // ---------- helpers ----------

    private List<Map<String, Object>> extractLocations(String serverBody) {
        List<Map<String, Object>> locations = new ArrayList<>();
        Matcher lm = LOCATION_BLOCK.matcher(serverBody);
        while (lm.find()) {
            String path = lm.group(1).trim();
            String body = lm.group(2);
            Map<String, Object> loc = new LinkedHashMap<>();
            loc.put("path", path);
            Matcher ppm = PROXY_PASS.matcher(body);
            Matcher rm = ROOT.matcher(body);
            if (ppm.find()) {
                loc.put("type", "proxy");
                loc.put("target", ppm.group(1).trim());
            } else if (rm.find()) {
                loc.put("type", "static");
                loc.put("target", rm.group(1).trim());
            } else if (TRY_FILES.matcher(body).find()) {
                loc.put("type", "static");
                loc.put("target", "(try_files)");
            } else {
                loc.put("type", "other");
                loc.put("target", "");
            }
            locations.add(loc);
        }
        return locations;
    }

    private String first(Pattern p, String s, String def) {
        Matcher m = p.matcher(s);
        return m.find() ? m.group(1) : def;
    }

    private String abbreviate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
