package com.wimoor.ai.api;

import com.wimoor.ai.chat.DataSources;
import com.wimoor.ai.lili.Catalog;
import com.wimoor.ai.report.AdsMetrics;
import com.wimoor.ai.skill.SkillManifest;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * wimoor-ai 对外 HTTP 入口（对应条条 ERP 的 /api/lili、/api/skills、/api/selection-research、
 * /api/ai/chat/data-sources 的只读能力部分）。
 *
 * <p>全部端点为只读：返回真实移植后的技能目录 / 数据源配置 / 指标计算 / manifest 版本。
 * 需要 LLM 运行时、真实 DB 或付费 MCP 的写操作不在本层暴露（那些能力由领域类接口承接，
 * 待接入真实 transport 后启用），避免用假数据冒充业务结果。
 */
@RestController
@RequestMapping("/ai/api/v1")
public class AiApiController {

    /** 健康检查 + 模块版本（验收用：确认容器与迁移代码已加载）。 */
    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("module", "aec-ai");
        m.put("status", "UP");
        m.put("liliVersion", SkillManifest.LILI_VERSION);
        m.put("skills", Catalog.listSkills().size());
        m.put("tools", Catalog.listTools().size());
        m.put("workflows", Catalog.listWorkflows().size());
        return m;
    }

    /** 技能目录（← /api/lili catalog，GET）。 */
    @GetMapping("/skills")
    public List<?> skills(@RequestParam(required = false) String category) {
        List<Catalog.CatalogSkill> all = Catalog.listSkills();
        if (category == null || category.isEmpty()) return all;
        List<Catalog.CatalogSkill> out = new ArrayList<>();
        for (Catalog.CatalogSkill s : all) if (category.equals(s.category)) out.add(s);
        return out;
    }

    /** MCP 工具目录（← /api/lili tools，GET）。 */
    @GetMapping("/tools")
    public List<Catalog.CatalogTool> tools() {
        return Catalog.listTools();
    }

    /** 工作流目录（← /api/lili workflows，GET）。 */
    @GetMapping("/workflows")
    public List<Catalog.CatalogWorkflow> workflows() {
        return Catalog.listWorkflows();
    }

    /** AI 对话数据源清单（← /api/ai/chat/data-sources，GET）。 */
    @GetMapping("/data-sources")
    public List<String> dataSources() {
        return DataSources.CHAT_DATA_PROVIDERS;
    }

    /** 广告指标即时计算（← amazon-ads/metrics，GET）。纯计算，不查库。 */
    @GetMapping("/ads/metrics")
    public AdsMetrics.Derived adsMetrics(
            @RequestParam(required = false) String impressions,
            @RequestParam(required = false) String clicks,
            @RequestParam(required = false) String spend,
            @RequestParam(required = false) String orders,
            @RequestParam(required = false) String sales) {
        AdsMetrics.Input in = new AdsMetrics.Input();
        in.impressions = impressions;
        in.clicks = clicks;
        in.spend = spend;
        in.orders = orders;
        in.sales = sales;
        return AdsMetrics.derive(in);
    }

    /** 技能 manifest 版本信息（← skill-manifest.ts，GET）。
     *  只返回真实目录元数据与模块版本；SHA-256 由正文决定，需接入 SKILL.md 存储后计算，
     *  此处不虚构哈希。 */
    @GetMapping("/skills/{slug}/manifest")
    public Map<String, Object> manifest(@PathVariable String slug) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("skillId", slug);
        m.put("liliVersion", SkillManifest.LILI_VERSION);
        m.put("found", Catalog.listSkills().stream().anyMatch(s -> s.slug.equals(slug)));
        return m;
    }
}
