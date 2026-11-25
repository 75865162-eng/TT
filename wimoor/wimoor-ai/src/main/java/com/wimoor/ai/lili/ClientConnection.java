package com.wimoor.ai.lili;

import java.net.URI;

/**
 * 移植自 src/lib/lili/client-connection.ts：由页面 origin 生成可复制的客户端连接说明。
 * 绝不从凭证推导地址；只允许 HTTPS 或本机 loopback HTTP。
 */
public final class ClientConnection {

    private ClientConnection() {}

    public static final class Connection {
        public final String endpoint;
        public final String toml;
        public final String loginCommand;
        public final String verifyPrompt;
        Connection(String endpoint, String toml, String loginCommand, String verifyPrompt) {
            this.endpoint = endpoint; this.toml = toml; this.loginCommand = loginCommand; this.verifyPrompt = verifyPrompt;
        }
    }

    public static Connection liliClientConnection(String origin) {
        URI parsed;
        try { parsed = URI.create(origin); }
        catch (IllegalArgumentException e) { throw new SyncInputException("Lili 连接地址必须使用 HTTPS，或本机 loopback HTTP。", 400); }
        String host = parsed.getHost();
        boolean loopback = "localhost".equals(host) || "127.0.0.1".equals(host) || "[::1]".equals(host);
        String path = parsed.getPath();
        if (parsed.getUserInfo() != null || (path != null && !path.isEmpty() && !"/".equals(path))
            || parsed.getRawQuery() != null || parsed.getRawFragment() != null
            || !("https".equalsIgnoreCase(parsed.getScheme()) || ("http".equalsIgnoreCase(parsed.getScheme()) && loopback))) {
            throw new SyncInputException("Lili 连接地址必须使用 HTTPS，或本机 loopback HTTP。", 400);
        }
        String endpoint = parsed.getScheme() + "://" + parsed.getRawAuthority() + "/api/lili/mcp";
        String toml = "[mcp_servers.lili]\nurl = " + JsonSupport.stringify(endpoint) + "\n";
        return new Connection(endpoint, toml, "codex mcp login lili",
            "请检查当前会话是否有 Lili MCP 工具，调用 lili_ping，确认 authenticated=true，并列出实际可用工具。不要用普通 HTTP 成功代替原生 MCP 验收。");
    }
}
