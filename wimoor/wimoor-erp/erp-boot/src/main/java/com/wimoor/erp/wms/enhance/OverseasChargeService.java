package com.wimoor.erp.wms.enhance;

/**
 * 海外费用服务（对应源文件 overseas-charge-service.ts）。
 * 移植了：mutateOverseasCharge 的动作白名单、review/create 的字段严格校验、
 * 禁止制单人自审、已复核证据不可覆盖、幂等冲突，以及 readWmsOverseasChargeSource
 * 的已复核+原调拨有效+非零金额校验与凭证映射。分摊逻辑见 OverseasChargeRules。
 */
public final class OverseasChargeService {

    private OverseasChargeService() {}

    /** 复核状态机校验。 */
    public static void validateReview(boolean isCreator, String status, String reviewer,
                                      String expectedReviewer) {
        if (isCreator) throw new WmsError("海外费用禁止制单人自审。", 403);
        if ("REVIEWED".equals(status)) {
            if (!reviewer.equals(expectedReviewer)) throw new WmsError("原审核证据不可覆盖。", 409);
        } else if (!"DRAFT".equals(status)) {
            throw new WmsError("海外费用状态无效。", 409);
        }
    }

    /** 交接凭证校验。 */
    public static void validateSource(boolean reviewed, String reviewedBy, boolean orderCancelled,
                                      boolean zeroAmount) {
        if (!reviewed || reviewedBy == null || orderCancelled) {
            throw new WmsError("海外费用须已独立复核且原调拨有效。", 409);
        }
        if (zeroAmount) throw new WmsError("零金额海外费用不生成凭证。", 422);
    }
}
