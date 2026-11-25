package com.wimoor.erp.wms.enhance;

import com.wimoor.erp.wms.enhance.WmsContracts.CommandInput;
import com.wimoor.erp.wms.enhance.WmsContracts.CountLine;
import com.wimoor.erp.wms.enhance.WmsContracts.Scope;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 盘点服务（对应源文件 stocktake-service.ts）。
 * 移植了：createStocktake 的范围/盲盘/模式校验（全盘最多 100 库位）、
 * executeStocktake 的状态机（DRAFT→COUNTING→REVIEW→POSTED/CANCELLED）与
 * overposting 校验（实盘不得低于待处理及预占合计）。
 */
public final class StocktakeService {

    /** 盘点单（简化领域实体）。 */
    public static final class Stocktake {
        private final String id;
        private final String warehouseId;
        private final String mode;
        private final boolean blind;
        private final List<String> locationIds;
        private String status;
        private int revision;

        public Stocktake(String id, String warehouseId, String mode, boolean blind, List<String> locationIds) {
            this.id = id;
            this.warehouseId = warehouseId;
            this.mode = mode;
            this.blind = blind;
            this.locationIds = locationIds;
            this.status = "DRAFT";
            this.revision = 1;
        }

        public String getId() { return id; }
        public String getWarehouseId() { return warehouseId; }
        public String getMode() { return mode; }
        public boolean isBlind() { return blind; }
        public List<String> getLocationIds() { return locationIds; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public int getRevision() { return revision; }
        public void incrementRevision() { revision++; }
    }

    /** 盘点行。 */
    public static final class CountRow {
        private final String locationId;
        private final String lotId;
        private final Integer baselineOnHand;
        private final int pendingTotal;
        private final int baselineRevision;
        private Integer countedQty;

        public CountRow(String locationId, String lotId, Integer baselineOnHand, int pendingTotal,
                        int baselineRevision) {
            this.locationId = locationId;
            this.lotId = lotId;
            this.baselineOnHand = baselineOnHand;
            this.pendingTotal = pendingTotal;
            this.baselineRevision = baselineRevision;
        }

        public String getLocationId() { return locationId; }
        public String getLotId() { return lotId; }
        public Integer getBaselineOnHand() { return baselineOnHand; }
        public int getPendingTotal() { return pendingTotal; }
        public Integer getCountedQty() { return countedQty; }
        public void setCountedQty(Integer countedQty) { this.countedQty = countedQty; }
    }

    private StocktakeService() {}

    /** createStocktake 的输入校验。 */
    public static Stocktake createStocktake(WmsDocumentStore.State state, Scope scope,
                                            String warehouseId, String number, Object modeRaw,
                                            Object blindRaw, List<String> locationIds, boolean full) {
        String mode = modeRaw == null ? "SAMPLE" : WmsRules.requireText(modeRaw, "盘点模式");
        if (!List.of("FULL", "SAMPLE", "ACTIVITY").contains(mode)) throw new WmsError("盘点范围模式无效。", 400);
        if (blindRaw != null && !(blindRaw instanceof Boolean)) throw new WmsError("须明确是否盲盘。", 400);
        boolean blind = Boolean.TRUE.equals(blindRaw);
        if ("ACTIVITY".equals(mode)) WmsRules.requireText(Boolean.TRUE, "动销范围依据");
        if (full) {
            if (locationIds != null && !locationIds.isEmpty()) {
                throw new WmsError("全盘范围由服务端确定，请勿指定部分库位。", 400);
            }
        }
        if (locationIds == null || locationIds.isEmpty() || locationIds.size() > 100) {
            throw new WmsError("请选择 1-100 个盘点库位。", 400);
        }
        List<String> ids = new ArrayList<>(locationIds);
        for (int i = 0; i < ids.size(); i++) ids.set(i, WmsRules.requireText(ids.get(i), "库位 ID"));
        java.util.Collections.sort(ids);
        if (new HashSet<>(ids).size() != ids.size()) throw new WmsError("盘点库位不能重复。", 400);
        for (String id : ids) state.repo.activeLocation(scope, id, warehouseId, "STORAGE");
        return new Stocktake(WmsDocumentStore.newId(), warehouseId, mode, blind, ids);
    }

    /** execute：状态机校验与推进。 */
    public static int execute(WmsDocumentStore.State state, Scope scope, CommandInput input,
                              Stocktake stocktake, List<CountRow> rows) {
        if (stocktake.getRevision() != input.getExpectedRevision()) throw new WmsError("盘点单已变更，请刷新。");
        switch (input.getPayload().getAction()) {
            case STOCKTAKE_CANCEL:
                if (!List.of("DRAFT", "COUNTING", "REVIEW").contains(stocktake.getStatus())) {
                    throw new WmsError("当前盘点状态不能取消。");
                }
                stocktake.setStatus("CANCELLED");
                break;
            case STOCKTAKE_START:
                if (!"DRAFT".equals(stocktake.getStatus())) throw new WmsError("仅草稿可以开始盘点。");
                for (String id : stocktake.getLocationIds()) {
                    state.repo.activeLocation(scope, id, stocktake.getWarehouseId(), "STORAGE");
                }
                stocktake.setStatus("COUNTING");
                break;
            case STOCKTAKE_REVIEW:
                if (!"COUNTING".equals(stocktake.getStatus())) throw new WmsError("仅盘点中单据可以提交审核。");
                requireCounted(rows);
                stocktake.setStatus("REVIEW");
                break;
            case STOCKTAKE_COUNT:
                if (!"COUNTING".equals(stocktake.getStatus())) throw new WmsError("当前状态不允许录入实盘。");
                for (CountLine entry : input.getPayload().getCountLines()) {
                    if (!stocktake.getLocationIds().contains(entry.getLocationId())) {
                        throw new WmsError("实盘库位不在当前盘点范围。", 422);
                    }
                    boolean found = false;
                    for (CountRow row : rows) {
                        if (row.getLocationId().equals(entry.getLocationId())
                                && row.getLotId().equals(entry.getLotId())) {
                            row.setCountedQty(entry.getCountedQty());
                            found = true;
                            break;
                        }
                    }
                    if (!found) {
                        if (rows.size() + 1 > WmsRules.WMS_MAX_RECEIPT_LINES) {
                            throw new WmsError("单次盘点最多 100 个库位批次。", 422);
                        }
                        rows.add(new CountRow(entry.getLocationId(), entry.getLotId(), 0, 0, 0));
                        rows.get(rows.size() - 1).setCountedQty(entry.getCountedQty());
                    }
                }
                break;
            case STOCKTAKE_POST:
                if (!"REVIEW".equals(stocktake.getStatus())) throw new WmsError("仅待审核盘点单可以申请或执行过账。");
                requireCounted(rows);
                for (CountRow row : rows) {
                    if (row.getCountedQty() < row.getPendingTotal()) {
                        throw new WmsError("实盘数量低于待处理及预占合计，请先处理相关库存后重新盘点。", 422);
                    }
                }
                stocktake.setStatus("POSTED");
                break;
            default:
                throw new WmsError("当前状态不允许此盘点动作。", 400);
        }
        stocktake.incrementRevision();
        return stocktake.getRevision();
    }

    private static void requireCounted(List<CountRow> rows) {
        for (CountRow row : rows) {
            if (row.getCountedQty() == null) {
                throw new WmsError("仍有未实盘明细；实盘为零也必须明确录入。", 422);
            }
        }
    }
}
