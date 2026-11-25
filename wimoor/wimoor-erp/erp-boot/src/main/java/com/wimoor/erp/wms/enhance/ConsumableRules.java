package com.wimoor.erp.wms.enhance;

import java.math.BigDecimal;

/**
 * 耗材数量规则（对应源文件 consumable-rules.ts）。
 * 移植了 consumableQuantity：用量比例 × 主商品数量必须组成完整整数发料批次，
 * 不允许截断小数或超量，否则抛 422。源端 Prisma.Decimal 用 BigDecimal 精确等价。
 */
public final class ConsumableRules {

    private ConsumableRules() {}

    /**
     * 耗材需求量 = units × mainQuantity。
     *
     * @param units       单位主商品耗用比例（十进制字符串）
     * @param mainQuantity 主商品数量（正整数）
     * @return 完整整数发料批次数量
     */
    public static int consumableQuantity(String units, int mainQuantity) {
        WmsRules.requirePositiveQuantity(mainQuantity, "主商品数量");
        BigDecimal ratio;
        try {
            ratio = new BigDecimal(units == null ? "" : units.trim());
        } catch (NumberFormatException e) {
            throw new WmsError("耗材需求须组成完整整数发料批次；不允许截断小数或超量。", 422);
        }
        BigDecimal result = ratio.multiply(BigDecimal.valueOf(mainQuantity));
        if (result.signum() <= 0 || result.stripTrailingZeros().scale() > 0
                || result.compareTo(BigDecimal.valueOf(WmsRules.WMS_QUANTITY_LIMIT)) > 0) {
            throw new WmsError("耗材需求须组成完整整数发料批次；不允许截断小数或超量。", 422);
        }
        return result.intValueExact();
    }
}
