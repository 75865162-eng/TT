package com.wimoor.erp.wms.enhance;

import com.wimoor.erp.wms.enhance.WmsContracts.Action;
import com.wimoor.erp.wms.enhance.WmsContracts.ApprovalView;
import com.wimoor.erp.wms.enhance.WmsContracts.Scope;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 审批查询（对应源文件 approval-query.ts）。
 * 移植了：approvalWorkFilters 候选队列、approvalActionFlags 可执行/可裁决标志、
 * approvalWorkWhere 的工作队列谓词。源端生成 Prisma WhereInput；本类以纯函数表达，
 * 供内存审批列表在分页前过滤。
 */
public final class ApprovalQuery {

    /** approvalWorkFilters。 */
    public static final List<String> WORK_FILTERS = List.of(
            "TO_REVIEW", "TO_EXECUTE", "MY_REQUESTS", "EXPIRED");

    /** ApprovalReadAccess。 */
    public static final class ReadAccess {
        private final boolean canApprove;
        private final boolean canEdit;
        private final Date now;

        public ReadAccess(boolean canApprove, boolean canEdit, Date now) {
            this.canApprove = canApprove;
            this.canEdit = canEdit;
            this.now = now;
        }
    }

    private ApprovalQuery() {}

    /** approvalActionFlags。 */
    public static Map<String, Object> approvalActionFlags(Scope scope, ApprovalView item, ReadAccess access) {
        long expiresAt = parseDate(item.getExpiresAt());
        boolean live = expiresAt > access.now.getTime();
        Map<String, Object> flags = new LinkedHashMap<>();
        flags.put("canDecide", live && access.canApprove && "PENDING".equals(item.getStatus())
                && !item.getRequestedBy().equals(scope.getActorUserId()));
        flags.put("canExecute", live && access.canEdit && "APPROVED".equals(item.getStatus())
                && item.getRequestedBy().equals(scope.getActorUserId())
                && item.getApprovedBy() != null && !item.getApprovedBy().equals(scope.getActorUserId()));
        return flags;
    }

    private static long parseDate(String text) {
        try {
            return java.time.Instant.parse(text).toEpochMilli();
        } catch (Exception e) {
            return Long.MIN_VALUE;
        }
    }

    /** approvalWorkWhere 的纯函数形式：判断审批是否属于候选队列。 */
    public static boolean matchesWork(Scope scope, ApprovalView item, String filter, ReadAccess access) {
        switch (filter) {
            case "MY_REQUESTS":
                return item.getRequestedBy().equals(scope.getActorUserId());
            case "EXPIRED":
                return ("PENDING".equals(item.getStatus()) || "APPROVED".equals(item.getStatus()))
                        && parseDate(item.getExpiresAt()) <= access.now.getTime();
            case "TO_REVIEW":
                return access.canApprove && "PENDING".equals(item.getStatus())
                        && !item.getRequestedBy().equals(scope.getActorUserId())
                        && parseDate(item.getExpiresAt()) > access.now.getTime();
            case "TO_EXECUTE":
                return access.canEdit && "APPROVED".equals(item.getStatus())
                        && item.getRequestedBy().equals(scope.getActorUserId())
                        && item.getApprovedBy() != null
                        && !item.getApprovedBy().equals(scope.getActorUserId())
                        && parseDate(item.getExpiresAt()) > access.now.getTime();
            default:
                throw new WmsError("审批作业筛选无效。", 400);
        }
    }
}
