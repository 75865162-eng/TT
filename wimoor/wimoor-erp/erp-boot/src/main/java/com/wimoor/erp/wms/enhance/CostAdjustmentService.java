package com.wimoor.erp.wms.enhance;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 成本调整服务（对应源文件 cost-adjustment-service.ts）。
 * 移植了：parseCostDelta 的有符号金额解析（非零、六位微单位）、
 * mutateCostAdjustment 的动作白名单/幂等/自审禁止/累计调整非负校验、
 * readWmsCostAdjustmentSource 的凭证方向映射。
 */
public final class CostAdjustmentService {

    private CostAdjustmentService() {}

    /** parseCostDelta：有符号金额 → 规范定点字符串。 */
    public static String parseCostDelta(Object value) {
        if (!(value instanceof String)) throw new WmsError("成本调整额须为有符号十进制字符串。", 400);
        String text = (String) value;
        boolean negative = text.startsWith("-");
        String units = negative ? text.substring(1) : text;
        BigInteger micros = InventoryRiskRules.amountUnits(units);
        if (micros.signum() == 0) throw new WmsError("成本调整额不能为零。", 400);
        return InventoryRiskRules.unitsAmount(negative ? micros.negate() : micros);
    }

    /** 复核状态机校验。 */
    public static void validateReview(boolean isCreator, String currentStatus, String desiredStatus,
                                      String reviewer, String decisionEvidence, int revision,
                                      int expectedRevision, String reviewedBy, String storedEvidence,
                                      boolean cumulativeNegative) {
        if (isCreator) throw new WmsError("成本调整禁止申请人自审。", 403);
        if (!"DRAFT".equals(currentStatus)) {
            if (!currentStatus.equals(desiredStatus) || !reviewer.equals(reviewedBy)
                    || !decisionEvidence.equals(storedEvidence) || revision != expectedRevision + 1) {
                throw new WmsError("成本调整复核不可覆盖。", 409);
            }
            return;
        }
        if (revision != expectedRevision) throw new WmsError("成本调整版本已变化。", 409);
        if (cumulativeNegative) throw new WmsError("累计调整不能使原成本流水金额为负。", 422);
    }

    /** 凭证方向映射。 */
    public static Map<String, Object> sourceLines(String delta, String debitAccount, String creditAccount) {
        boolean positive = !delta.startsWith("-");
        String amount = positive ? delta : delta.substring(1);
        Map<String, Object> debit = new LinkedHashMap<>();
        debit.put("account", positive ? debitAccount : creditAccount);
        debit.put("debit", amount);
        debit.put("credit", "0.000000");
        Map<String, Object> credit = new LinkedHashMap<>();
        credit.put("account", positive ? creditAccount : debitAccount);
        credit.put("debit", "0.000000");
        credit.put("credit", amount);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("kind", "WMS_COST_ADJUSTMENT");
        result.put("lines", java.util.List.of(debit, credit));
        return result;
    }
}
