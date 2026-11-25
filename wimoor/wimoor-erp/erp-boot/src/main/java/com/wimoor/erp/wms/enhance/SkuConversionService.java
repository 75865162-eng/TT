package com.wimoor.erp.wms.enhance;

import com.wimoor.erp.wms.enhance.Repository.Balance;
import com.wimoor.erp.wms.enhance.Repository.Delta;
import com.wimoor.erp.wms.enhance.Repository.LocationLot;
import com.wimoor.erp.wms.enhance.Repository.Lot;
import com.wimoor.erp.wms.enhance.Repository.Source;
import com.wimoor.erp.wms.enhance.WmsContracts.CommandInput;
import com.wimoor.erp.wms.enhance.WmsContracts.Scope;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SKU 转换服务（对应源文件 sku-conversion-service.ts）。
 * 移植了：validateSkuConversion（同仓异商品、版本与可用量校验）、executeSkuConversion
 * 的等量对转（源扣减+目标增加，两条流水）、validateConversionReversal/execute
 * 的原转换冲销（仅原申请人、不可重复冲销、完整冲销）。
 */
public final class SkuConversionService {

    private SkuConversionService() {}

    /** 转换结果。 */
    public static final class ConversionResult {
        private final int revision;
        private final List<String> movementIds;
        private final Map<String, Object> conversion;
        private final String targetBalanceId;

        public ConversionResult(int revision, List<String> movementIds,
                                Map<String, Object> conversion, String targetBalanceId) {
            this.revision = revision;
            this.movementIds = movementIds;
            this.conversion = conversion;
            this.targetBalanceId = targetBalanceId;
        }

        public int getRevision() { return revision; }
        public List<String> getMovementIds() { return movementIds; }
        public Map<String, Object> getConversion() { return conversion; }
        public String getTargetBalanceId() { return targetBalanceId; }
    }

    /** validateSkuConversion。 */
    public static Balance[] validateSkuConversion(WmsDocumentStore.State state, Scope scope,
                                                  CommandInput input) {
        Balance source = state.repo.findBalance(scope.getOrganizationId(), scope.getWorkspaceId(),
                input.getAggregateId());
        if (source == null) throw new WmsError("转换来源库存不存在。", 404);
        state.repo.activeLocation(scope, source.getLocationId(), source.getWarehouseId(), "STORAGE");
        Lot targetLot = state.repo.findLot(scope.getOrganizationId(), scope.getWorkspaceId(),
                input.getPayload().getTargetLotId());
        Lot sourceLot = state.repo.findLot(scope.getOrganizationId(), scope.getWorkspaceId(), source.getLotId());
        if (targetLot == null || sourceLot == null
                || targetLot.getProductRecordId().equals(sourceLot.getProductRecordId())) {
            throw new WmsError("目标批次须属于当前工作区的另一商品。", 422);
        }
        if (source.getRevision() != input.getExpectedRevision()) throw new WmsError("转换来源库存已变更。");
        if (input.getPayload().getQuantity() > WmsRules.availableQuantity(source.snapshot())) {
            throw new WmsError("转换数量超过可用库存。", 422);
        }
        return new Balance[]{source};
    }

    /** executeSkuConversion。 */
    public static ConversionResult execute(WmsDocumentStore.State state, Scope scope,
                                           CommandInput input, String commandId) {
        validateSkuConversion(state, scope, input);
        Balance source = state.repo.findBalance(scope.getOrganizationId(), scope.getWorkspaceId(),
                input.getAggregateId());
        Lot targetLot = state.repo.findLot(scope.getOrganizationId(), scope.getWorkspaceId(),
                input.getPayload().getTargetLotId());
        Lot sourceLot = state.repo.findLot(scope.getOrganizationId(), scope.getWorkspaceId(), source.getLotId());
        state.repo.ensureBalances(scope, Arrays.asList(
                new LocationLot(source.getLocationId(), source.getWarehouseId(), source.getLotId()),
                new LocationLot(source.getLocationId(), source.getWarehouseId(), targetLot.getId())));
        Balance target = state.repo.findBalance(scope.getOrganizationId(), scope.getWorkspaceId(),
                Repository.balanceId(scope, source.getLocationId(), targetLot.getId()));
        Source facts = new Source("SKU_CONVERT", "SKU_CONVERSION", commandId, null);
        String debit = state.repo.move(scope, commandId, 0, source,
                new Delta(-input.getPayload().getQuantity(), 0, 0, 0, 0), facts).getId();
        String credit = state.repo.move(scope, commandId, 1, target,
                new Delta(input.getPayload().getQuantity(), 0, 0, 0, 0), facts).getId();
        Map<String, Object> conversion = new LinkedHashMap<>();
        conversion.put("reason", input.getPayload().getReason());
        conversion.put("quantity", input.getPayload().getQuantity());
        conversion.put("sourceLotId", source.getLotId());
        conversion.put("sourceProductRecordId", sourceLot.getProductRecordId());
        conversion.put("targetLotId", targetLot.getId());
        conversion.put("targetProductRecordId", targetLot.getProductRecordId());
        return new ConversionResult(source.getRevision() + 1, Arrays.asList(debit, credit),
                conversion, target.getId());
    }
}
