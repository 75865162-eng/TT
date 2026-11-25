package com.wimoor.erp.wms.enhance;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SKU 转换查询（对应源文件 conversion-query.ts）。
 * 移植了 presentConversions：转换结果视图、源/目标库存上下文、冲销命令关联、
 * canRequestReversal 判定（无冲销、申请人本人、目标余额可用量足够）。
 */
public final class ConversionQuery {

    private ConversionQuery() {}

    /** 转换命令视图输入。 */
    public static final class ConversionCommand {
        private final String id;
        private final String createdAt;
        private final String createdBy;
        private final String aggregateId;
        private final String targetBalanceId;
        private final int quantity;
        private final String reason;
        private final String sourceLotId;
        private final String targetLotId;

        public ConversionCommand(String id, String createdAt, String createdBy, String aggregateId,
                                 String targetBalanceId, int quantity, String reason,
                                 String sourceLotId, String targetLotId) {
            this.id = id;
            this.createdAt = createdAt;
            this.createdBy = createdBy;
            this.aggregateId = aggregateId;
            this.targetBalanceId = targetBalanceId;
            this.quantity = quantity;
            this.reason = reason;
            this.sourceLotId = sourceLotId;
            this.targetLotId = targetLotId;
        }
    }

    /** presentConversions。 */
    public static List<Map<String, Object>> presentConversions(
            List<ConversionCommand> commands,
            Map<String, Repository.Balance> balancesById,
            Map<String, WmsContracts.StockContext> contexts,
            Map<String, String> reversalByOriginalId,
            String actorUserId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ConversionCommand command : commands) {
            Repository.Balance balance = balancesById.get(command.targetBalanceId);
            String reversal = reversalByOriginalId.get(command.id);
            boolean canReversal = reversal == null && command.createdBy.equals(actorUserId)
                    && balance != null
                    && WmsRules.availableQuantity(balance.snapshot()) >= command.quantity;
            Map<String, Object> conversion = new LinkedHashMap<>();
            conversion.put("quantity", command.quantity);
            conversion.put("reason", command.reason);
            conversion.put("sourceLotId", command.sourceLotId);
            conversion.put("targetLotId", command.targetLotId);
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("id", command.id);
            view.put("createdAt", command.createdAt);
            view.put("createdBy", command.createdBy);
            view.put("conversion", conversion);
            view.put("source", contexts.get(command.aggregateId));
            view.put("target", contexts.get(command.targetBalanceId));
            view.put("targetBalanceRevision", balance == null ? null : balance.getRevision());
            view.put("reversalCommandId", reversal);
            view.put("canRequestReversal", canReversal);
            out.add(view);
        }
        return out;
    }
}
