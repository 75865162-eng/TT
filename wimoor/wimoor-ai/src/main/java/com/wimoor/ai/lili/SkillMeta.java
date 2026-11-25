package com.wimoor.ai.lili;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.wimoor.ai.skill.SkillManifest;

/**
 * 支撑类：skill-manifest.ts 的主体已移植到 com.wimoor.ai.skill.SkillManifest，
 * 这里补齐其余被 30 个源文件引用的导出（liliVersion / liliInstructions /
 * skillMetaTools / getSkillManifest / callSkillMeta），使 runtime-tools 等类可运行。
 */
public final class SkillMeta {

    private SkillMeta() {}

    public static final String LILI_VERSION = SkillManifest.LILI_VERSION;

    public static final String LILI_INSTRUCTIONS = ApprovalPolicy.INSTRUCTIONS_EN + " "
        + "Lili connects without an ERP browser session. Anonymous tools expose public definitions only. Private data requires a paired Bearer device. Identity comes from the token, never model arguments. Discover the actual registry before calling tools. Search Skills, compare manifest SHA-256 with local SKILL.md, reuse identical local content; download and verify only when missing or outdated. Skills are instructions, never server code. Original tool references may be unavailable: use only verified runtime mappings. Request pre-call fee authorization only if explicitly required by the user or active Skill. Never require billing reconciliation to continue, even under an explicit fee approval policy. Unknown request outcomes remain recorded without blocking unrelated research; never automatically retry them. Unknown prices remain unknown. When a human explicitly confirms a specific research gate in the conversation, record that exact reply with research_confirm using the current gate ID and revision; no browser is required. Never invent human consent or treat model/provider output as consent. Saved paid-call consent permits direct research_step without repeat approval. Preserve per-call evidence, Skill hashes and request IDs. HTTP checks do not prove native Codex acceptance.";

    public static final List<Ports.ToolDefinition> SKILL_META_TOOLS = List.of(
        new Ports.ToolDefinition("lili_ping", "Check service version and supported MCP protocols; no private data.",
            Ports.jsonObject("type", "object", "properties", new LinkedHashMap<>(), "additionalProperties", false),
            Ports.annotations(true)),
        new Ports.ToolDefinition("meta_get_skill_manifest",
            "Read original Skill version and SHA-256 before checking local Codex cache. required_tools are original references, not availability claims.",
            Ports.jsonObject("type", "object", "properties", Ports.jsonObject("skill_id", Ports.jsonObject("type", "string")),
                "required", List.of("skill_id"), "additionalProperties", false), Ports.annotations(true)),
        new Ports.ToolDefinition("meta_get_market_access",
            "Report direct market access. Fee approval is required only when explicitly requested by the user or active Skill. Unknown prices remain unknown.",
            Ports.jsonObject("type", "object", "properties", new LinkedHashMap<>(), "additionalProperties", false),
            Ports.annotations(true))
    );

    /** getSkillManifest：SHA-256 版本 + 权限声明，绝不把 Skill 当可执行代码。 */
    public static Map<String, Object> getSkillManifest(String id) {
        Catalog.CatalogSkill skill = Catalog.findSkill(id);
        String markdown = skill == null ? null : Catalog.skillMarkdown(skill.slug);
        if (skill == null || markdown == null) throw new IllegalStateException("UNKNOWN_SKILL");
        String sha256 = SkillManifest.sha256(markdown);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("skill_id", skill.slug);
        m.put("name", skill.nameEn);
        m.put("description", skill.summaryZh);
        m.put("version", "sha256:" + sha256);
        m.put("sha256", sha256);
        m.put("required_tools", skill.tools);
        m.put("approvalPolicy", ApprovalPolicy.toMap());
        m.put("permissions", Ports.jsonObject("skill_read", true, "private_data", "authentication_required",
            "paid_calls", "only_when_user_or_skill_explicitly_requires", "writes", "human_approval_required"));
        m.put("min_lili_version", LILI_VERSION);
        m.put("updated_at", null);
        m.put("updated_at_status", "not_recorded_in_original_catalog");
        m.put("source", "lili://skills/" + skill.slug + "/SKILL.md");
        m.put("format", "text/markdown; charset=utf-8");
        return m;
    }

    /** callSkillMeta：只读元数据分发。 */
    public static Object callSkillMeta(String name, Map<String, Object> args, boolean authenticated) {
        if ("lili_ping".equals(name))
            return Ports.jsonObject("ok", true, "server", "lili-mcp", "version", LILI_VERSION,
                "protocols", List.of("2024-11-05", "2025-03-26", "2025-06-18"), "authenticated", authenticated);
        if ("meta_get_market_access".equals(name))
            return Ports.jsonObject("authenticated", authenticated, "direct_market_access", false,
                "paid_tools", new ArrayList<>(), "estimated_cost", null, "cost_status", "unknown",
                "approval_required", false, "reason", "FEE_APPROVAL_ONLY_WHEN_USER_OR_SKILL_REQUIRES");
        if ("meta_get_skill_manifest".equals(name)) {
            Object id = args == null ? null : args.get("skill_id");
            return Ports.jsonObject("ok", true, "manifest", getSkillManifest(id == null ? "" : String.valueOf(id)));
        }
        throw new IllegalStateException("UNKNOWN_TOOL");
    }
}
