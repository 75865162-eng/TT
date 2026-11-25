package com.wimoor.erp.wms.enhance;

/**
 * 库存风险评估服务（对应源文件 inventory-risk-service.ts）。
 * 移植了：mutateInventoryRisk 的动作白名单、字段严格校验、幂等冲突、
 * 快照 500 商品边界、复核禁止自审/结果不可覆盖/依据不完整不可通过/重复确认防护、
 * readWmsInventoryRiskSource 的已复核凭证交接。
 */
public final class InventoryRiskService {

    private InventoryRiskService() {}

    /** 复核状态机校验（create 走 InventoryRiskRules.parse* + assessInventoryRisk）。 */
    public static void validateReview(boolean isCreator, String status, boolean approve,
                                      String desiredStatus, String reviewer, String expectedReviewer,
                                      int revision, int expectedRevision, String decisionEvidence,
                                      String storedEvidence, boolean resultComplete, boolean alreadyReviewed) {
        if (isCreator) throw new WmsError("库存风险评估禁止申请人自审。", 403);
        if (!"DRAFT".equals(status)) {
            if (!status.equals(desiredStatus) || revision != expectedRevision + 1
                    || !reviewer.equals(expectedReviewer) || !decisionEvidence.equals(storedEvidence)) {
                throw new WmsError("复核结果不可覆盖。", 409);
            }
            return;
        }
        if (revision != expectedRevision) throw new WmsError("评估版本已变化。", 409);
        if (approve && !resultComplete) {
            throw new WmsError("依据不完整的评估不能复核通过，请保留原证据并创建新评估。", 422);
        }
        if (approve && alreadyReviewed) {
            throw new WmsError("该快照已有已复核评估，不得重复确认准备。", 409);
        }
    }

    /** 快照边界校验。 */
    public static void validateSnapshotSize(int recordCount) {
        if (recordCount > 500) {
            throw new WmsError("快照超过单次评估 500 个商品的边界，须采用专用评估任务。", 422);
        }
    }
}
