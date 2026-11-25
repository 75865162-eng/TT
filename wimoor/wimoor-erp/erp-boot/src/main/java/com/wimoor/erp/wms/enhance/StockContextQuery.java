package com.wimoor.erp.wms.enhance;

import com.wimoor.erp.wms.enhance.Repository.Balance;
import com.wimoor.erp.wms.enhance.Repository.Lot;
import com.wimoor.erp.wms.enhance.Repository.Movement;
import com.wimoor.erp.wms.enhance.WmsContracts.Scope;
import com.wimoor.erp.wms.enhance.WmsContracts.StockContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 库存上下文查询（对应源文件 stock-context-query.ts）。
 * 移植了：presentBalances/presentMovements 的可用量计算与上下文富化，
 * queryStockContexts 的库位/仓库/批次上下文组装（仅页面可见部分）。
 */
public final class StockContextQuery {

    private StockContextQuery() {}

    /** 仅展示当前页，使用与拣货/审批相同的范围上下文。 */
    public static List<Map<String, Object>> presentBalances(List<Balance> balances,
                                                            Map<String, StockContext> contexts) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Balance balance : balances) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("id", balance.getId());
            view.put("onHand", balance.getOnHand());
            view.put("pendingQc", balance.getPendingQc());
            view.put("pendingPutaway", balance.getPendingPutaway());
            view.put("rejected", balance.getRejected());
            view.put("reserved", balance.getReserved());
            view.put("revision", balance.getRevision());
            view.put("available", WmsRules.availableQuantity(balance.snapshot()));
            view.put("stock", contexts.get(balance.getId()));
            out.add(view);
        }
        return out;
    }

    /** presentMovements。 */
    public static List<Map<String, Object>> presentMovements(List<Movement> movements,
                                                             Map<String, StockContext> contexts) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Movement movement : movements) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("id", movement.getId());
            view.put("balanceId", movement.getBalanceId());
            view.put("action", movement.getAction());
            view.put("deltaOnHand", movement.getDeltaOnHand());
            view.put("occurredAt", movement.getOccurredAt().toInstant().toString());
            view.put("stock", contexts.get(movement.getBalanceId()));
            out.add(view);
        }
        return out;
    }

    /** queryStockContexts：由仓储实体组装上下文。 */
    public static Map<String, StockContext> queryStockContexts(Repository.WmsRepository repo, Scope scope,
                                                               List<String> balanceIds) {
        Map<String, StockContext> out = new HashMap<>();
        if (balanceIds.isEmpty()) return out;
        for (String id : balanceIds) {
            Balance balance = repo.findBalance(scope.getOrganizationId(), scope.getWorkspaceId(), id);
            if (balance == null) continue;
            Repository.Location location = repo.findLocation(scope.getOrganizationId(), scope.getWorkspaceId(),
                    balance.getLocationId());
            Lot lot = repo.findLot(scope.getOrganizationId(), scope.getWorkspaceId(), balance.getLotId());
            if (location == null || lot == null) continue;
            out.put(id, new StockContext(null, lot.getProductRecordId(), balance.getWarehouseId(),
                    null, null, balance.getLocationId(), location.getCode(), location.isFrozen(),
                    balance.getLotId(), lot.getLotCode(), lot.getBarcode()));
        }
        return out;
    }
}
