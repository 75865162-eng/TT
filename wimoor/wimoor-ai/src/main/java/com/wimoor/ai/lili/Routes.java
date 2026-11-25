package com.wimoor.ai.lili;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 移植自 src/lib/lili/routes.ts：页面清单、导航过滤、路径解析与 BASE_PATH/withBasePath。
 */
public final class Routes {

    private Routes() {}

    public static final String BASE_PATH = "/lili";

    public static final class Page {
        public final String path;
        public final String title;
        Page(String path, String title) { this.path = path; this.title = title; }
    }

    private static final List<Page> LILI_PAGES = List.of(
        new Page("start", "开始使用"), new Page("skills", "技能"),
        new Page("cases", "工作流模板"), new Page("tools", "工具目录"),
        new Page("workflows", "工作流状态"), new Page("tool-config", "工具配置"),
        new Page("team", "团队"), new Page("settings", "设置"),
        new Page("approvals", "审批"), new Page("recharge", "充值"),
        new Page("help", "帮助")
    );

    /** liliNavigation = liliPages.filter(path not in team/settings/help) */
    public static final List<Page> LILI_NAVIGATION;
    static {
        List<Page> pages = new ArrayList<>();
        for (Page p : LILI_PAGES) {
            if (!Arrays.asList("team", "settings", "help").contains(p.path)) pages.add(p);
        }
        LILI_NAVIGATION = Collections.unmodifiableList(pages);
    }

    /** LiliPage = { kind, slug?, title, moduleId } */
    public static final class LiliPage {
        public final String kind;
        public final String slug;
        public final String title;
        public final String moduleId;
        LiliPage(String kind, String slug, String title, String moduleId) {
            this.kind = kind; this.slug = slug; this.title = title; this.moduleId = moduleId;
        }
        @Override public String toString() { return kind + "/" + (slug == null ? "" : slug) + "@" + moduleId; }
    }

    public static LiliPage resolveLiliPage(List<String> parts) {
        if (parts.isEmpty()) return new LiliPage("start", null, "开始使用", "agents");
        if (parts.size() == 1 && "login".equals(parts.get(0))) return new LiliPage("login", null, "登录", "agents");
        if (!"v2".equals(parts.get(0))) return null;
        List<String> remaining = new ArrayList<>(parts.subList(1, parts.size()));
        if (!remaining.isEmpty() && "lab".equals(remaining.get(0))) remaining = new ArrayList<>(remaining.subList(1, remaining.size()));
        if (remaining.isEmpty() || remaining.size() > 2) return null;
        String kind = remaining.get(0);
        String slug = remaining.size() > 1 ? remaining.get(1) : null;
        Page item = null;
        for (Page p : LILI_PAGES) if (p.path.equals(kind)) { item = p; break; }
        if (item == null) return null;
        if (slug != null && !Arrays.asList("skills", "cases", "tools", "workflows").contains(kind)) return null;
        if (parts.size() > 1 && "lab".equals(parts.get(1)) && (slug == null || !Arrays.asList("skills", "cases").contains(kind))) return null;
        String moduleId = "team".equals(kind) ? "accounts"
            : (Arrays.asList("settings", "tool-config").contains(kind) ? "settings" : "agents");
        return new LiliPage(kind, slug, item.title, moduleId);
    }

    /** API 命名空间专用；其余路径统一加 /lili 前缀。 */
    public static String withBasePath(String path) {
        if ("/api".equals(path) || path.startsWith("/api/")) return "/api/lili" + path.substring(4);
        if (BASE_PATH.equals(path) || path.startsWith(BASE_PATH + "/")) return path;
        return BASE_PATH + "/" + path.replaceAll("^/+", "");
    }
}
