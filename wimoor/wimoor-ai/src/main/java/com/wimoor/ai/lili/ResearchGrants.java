package com.wimoor.ai.lili;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.wimoor.ai.lili.Ports.PermissionAction;

/**
 * 移植自 src/lib/lili/research-grants.ts：研究工具的显式授权清单。
 * 既有 Amazon 工具授权绝不隐式继承这些权限。
 */
public final class ResearchGrants {

    private ResearchGrants() {}

    /** researchToolActions：工具名 → PermissionAction。 */
    public static final Map<String, PermissionAction> RESEARCH_TOOL_ACTIONS;
    static {
        Map<String, PermissionAction> m = new LinkedHashMap<>();
        m.put("workflow_sync_review", PermissionAction.VIEW);
        m.put("workflow_sync_confirm", PermissionAction.APPROVE);
        m.put("research_start", PermissionAction.CREATE);
        m.put("research_read", PermissionAction.VIEW);
        m.put("research_evidence", PermissionAction.VIEW);
        m.put("research_list", PermissionAction.VIEW);
        m.put("research_step", PermissionAction.EDIT);
        m.put("research_confirm", PermissionAction.APPROVE);
        m.put("research_record_charge", PermissionAction.APPROVE);
        m.put("research_pause", PermissionAction.EDIT);
        m.put("research_resume", PermissionAction.EDIT);
        m.put("research_recover", PermissionAction.EDIT);
        m.put("research_cancel", PermissionAction.EDIT);
        m.put("research_export", PermissionAction.EXPORT);
        RESEARCH_TOOL_ACTIONS = Collections.unmodifiableMap(m);
    }

    /** researchCredentialTools：[工具名, 中文说明] 保留原始顺序。 */
    public static final List<Map.Entry<String, String>> RESEARCH_CREDENTIAL_TOOLS = Collections.unmodifiableList(Arrays.asList(
        entry("workflow_sync_review", "Lili 工作流：对话预览本人同步申请"),
        entry("workflow_sync_confirm", "Lili 工作流：对话批准并应用本人同步申请"),
        entry("research_start", "Lili 开品：创建研究（真实调用仍需审批）"),
        entry("research_list", "Lili 开品：查询本人任务历史"),
        entry("research_evidence", "Lili 开品：分页读取本人原始证据"),
        entry("research_read", "Lili 开品：读取本人研究"),
        entry("research_confirm", "Lili 开品：保存用户在对话中的明确确认"),
        entry("research_record_charge", "Lili 开品：保存明确确认的研究积分费用凭据"),
        entry("research_step", "Lili 开品：推进研究（不能批准人工节点）"),
        entry("research_pause", "Lili 开品：暂停本人研究"),
        entry("research_resume", "Lili 开品：恢复暂停（不执行请求）"),
        entry("research_recover", "Lili 开品：恢复失败（不重放请求）"),
        entry("research_cancel", "Lili 开品：取消本人研究"),
        entry("research_export", "Lili 开品：保存已确认的 HTML 报告")
    ));

    private static Map.Entry<String, String> entry(String k, String v) { return new java.util.AbstractMap.SimpleImmutableEntry<>(k, v); }

    public static boolean isResearchTool(String name) { return name != null && RESEARCH_TOOL_ACTIONS.containsKey(name); }

    /** workflow_sync_* 归 operations，其余归 agents。 */
    public static String liliToolModule(String name) {
        return name != null && name.startsWith("workflow_sync_") ? "operations" : "agents";
    }
}
