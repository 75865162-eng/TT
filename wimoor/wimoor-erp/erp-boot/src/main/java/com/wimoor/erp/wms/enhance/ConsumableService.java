package com.wimoor.erp.wms.enhance;

import com.wimoor.erp.wms.enhance.Repository.Balance;
import com.wimoor.erp.wms.enhance.Repository.Delta;
import com.wimoor.erp.wms.enhance.Repository.Lot;
import com.wimoor.erp.wms.enhance.Repository.Source;
import com.wimoor.erp.wms.enhance.WmsContracts.CommandInput;
import com.wimoor.erp.wms.enhance.WmsContracts.Scope;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 耗材发料服务（对应源文件 consumable-service.ts）。
 * 移植了：validateConsumableIssue 的已出库主商品数量上限、整批耗材数量计算、
 * 来源商品/仓库匹配、可用量校验，以及 execute 的扣减与发料数量回写。
 * createConsumable 的正十进制耗用量校验由 CatalogDetailsService.positiveDecimal 提供。
 */
public final class ConsumableService {

    /** 耗材规则（简化领域实体）。 */
    public static final class ConsumableRule {
        private final String id;
        private final String warehouseId;
        private final String outboundLineId;
        private final String productRecordId;
        private final String units;
        private int accountedMainQty;
        private int issuedQty;
        private int revision;

        public ConsumableRule(String id, String warehouseId, String outboundLineId,
                              String productRecordId, String units) {
            this.id = id;
            this.warehouseId = warehouseId;
            this.outboundLineId = outboundLineId;
            this.productRecordId = productRecordId;
            this.units = units;
            this.revision = 1;
        }

        public String getId() { return id; }
        public String getWarehouseId() { return warehouseId; }
        public String getOutboundLineId() { return outboundLineId; }
        public String getProductRecordId() { return productRecordId; }
        public String getUnits() { return units; }
        public int getAccountedMainQty() { return accountedMainQty; }
        public int getIssuedQty() { return issuedQty; }
        public int getRevision() { return revision; }

        public void applyIssue(int mainQty, int qty) {
            accountedMainQty += mainQty;
            issuedQty += qty;
            revision++;
        }
    }

    /** 发料结果。 */
    public static final class IssueResult {
        private final int revision;
        private final java.util.List<String> movementIds;
        private final Map<String, Object> change;

        public IssueResult(int revision, java.util.List<String> movementIds, Map<String, Object> change) {
            this.revision = revision;
            this.movementIds = movementIds;
            this.change = change;
        }

        public int getRevision() { return revision; }
        public java.util.List<String> getMovementIds() { return movementIds; }
        public Map<String, Object> getChange() { return change; }
    }

    private ConsumableService() {}

    /** executeConsumableIssue。 */
    public static IssueResult execute(WmsDocumentStore.State state, Scope scope,
                                      CommandInput input, String commandId,
                                      ConsumableRule rule, int dispatchedMainQty) {
        if (input.getPayload().getAction() != com.wimoor.erp.wms.enhance.WmsContracts.Action.CONSUMABLE_ISSUE) {
            throw new WmsError("非耗材发料动作。", 400);
        }
        if (rule.getRevision() != input.getExpectedRevision()) throw new WmsError("耗材发料版本已变更。");
        if (rule.getAccountedMainQty() + input.getPayload().getMainQuantity() > dispatchedMainQty) {
            throw new WmsError("耗材计量主商品数量超过已实际出库且未发料数量。", 422);
        }
        int quantity = ConsumableRules.consumableQuantity(rule.getUnits(), input.getPayload().getMainQuantity());
        ConsumableRules.consumableQuantity(rule.getUnits(),
                rule.getAccountedMainQty() + input.getPayload().getMainQuantity());
        Balance balance = state.repo.findBalance(scope.getOrganizationId(), scope.getWorkspaceId(),
                input.getPayload().getSourceBalanceId());
        Lot lot = balance == null ? null : state.repo.findLot(scope.getOrganizationId(),
                scope.getWorkspaceId(), balance.getLotId());
        if (balance == null || !balance.getWarehouseId().equals(rule.getWarehouseId())
                || lot == null || !lot.getProductRecordId().equals(rule.getProductRecordId())) {
            throw new WmsError("耗材来源商品或仓库不匹配。", 422);
        }
        state.repo.activeLocation(scope, balance.getLocationId(), balance.getWarehouseId(), "STORAGE");
        if (quantity > WmsRules.availableQuantity(balance.snapshot())) throw new WmsError("耗材可用库存不足。", 422);
        String movementId = state.repo.move(scope, commandId, 0, balance,
                new Delta(-quantity, 0, 0, 0, 0),
                new Source("CONSUMABLE_ISSUE", "OUTBOUND_CONSUMABLE", rule.getId(), rule.getOutboundLineId())).getId();
        rule.applyIssue(input.getPayload().getMainQuantity(), quantity);
        Map<String, Object> change = new LinkedHashMap<>();
        change.put("units", rule.getUnits());
        change.put("mainQuantity", input.getPayload().getMainQuantity());
        change.put("issuedQuantity", quantity);
        change.put("reason", input.getPayload().getReason());
        change.put("outboundLineId", rule.getOutboundLineId());
        return new IssueResult(rule.getRevision(), Collections.singletonList(movementId), change);
    }
}
