package com.wimoor.erp.wms.enhance;

import com.wimoor.erp.wms.enhance.AssemblyRules.AllocationCandidate;
import com.wimoor.erp.wms.enhance.AssemblyRules.AllocationResult;
import com.wimoor.erp.wms.enhance.AssemblyRules.AssemblyDraft;
import com.wimoor.erp.wms.enhance.AssemblyRules.BomDraft;
import com.wimoor.erp.wms.enhance.AssemblyRules.ConsumedLine;
import com.wimoor.erp.wms.enhance.AssemblyRules.Demand;
import com.wimoor.erp.wms.enhance.Repository.Balance;
import com.wimoor.erp.wms.enhance.Repository.LocationLot;
import com.wimoor.erp.wms.enhance.WmsContracts.CommandInput;
import com.wimoor.erp.wms.enhance.WmsContracts.Scope;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 组装服务（对应源文件 assembly-service.ts）。
 * 移植了：createBom 草稿解析与组件存在性校验、createAssemblyOrder 的 BOM 展开、
 * FIFO 组件分配、组件成本回退采购价、validateAssemblyCompletion 整单完成校验、
 * executeAssembly 的扣组件/产成品入库/滚成本（componentCost/unitCost）。
 * 草稿不预占库存；完成走命令通道。
 */
public final class AssemblyService {

    private AssemblyService() {}

    /** 组装单（简化领域实体）。 */
    public static final class AssemblyOrder {
        private final String id;
        private final String createdBy;
        private final String productRecordId;
        private final String locationId;
        private final String warehouseId;
        private final String number;
        private final int plannedQty;
        private String status;
        private int revision;
        private final List<AssemblyOrderLine> lines;

        public AssemblyOrder(String id, String createdBy, String productRecordId, String locationId,
                             String warehouseId, String number, int plannedQty, List<AssemblyOrderLine> lines) {
            this.id = id;
            this.createdBy = createdBy;
            this.productRecordId = productRecordId;
            this.locationId = locationId;
            this.warehouseId = warehouseId;
            this.number = number;
            this.plannedQty = plannedQty;
            this.status = "DRAFT";
            this.revision = 1;
            this.lines = lines;
        }

        public String getId() { return id; }
        public String getCreatedBy() { return createdBy; }
        public String getProductRecordId() { return productRecordId; }
        public String getLocationId() { return locationId; }
        public String getWarehouseId() { return warehouseId; }
        public String getNumber() { return number; }
        public int getPlannedQty() { return plannedQty; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public int getRevision() { return revision; }
        public void incrementRevision() { revision++; }
        public List<AssemblyOrderLine> getLines() { return lines; }
    }

    /** 组装明细。 */
    public static final class AssemblyOrderLine {
        private final String id;
        private final String balanceId;
        private final String componentProductRecordId;
        private final String lotId;
        private final String locationId;
        private final String warehouseId;
        private final int plannedQty;
        private int consumedQty;
        private final String unitCost;

        public AssemblyOrderLine(String id, String balanceId, String componentProductRecordId, String lotId,
                                 String locationId, String warehouseId, int plannedQty, String unitCost) {
            this.id = id;
            this.balanceId = balanceId;
            this.componentProductRecordId = componentProductRecordId;
            this.lotId = lotId;
            this.locationId = locationId;
            this.warehouseId = warehouseId;
            this.plannedQty = plannedQty;
            this.unitCost = unitCost;
        }

        public String getId() { return id; }
        public String getBalanceId() { return balanceId; }
        public String getComponentProductRecordId() { return componentProductRecordId; }
        public String getLotId() { return lotId; }
        public String getLocationId() { return locationId; }
        public String getWarehouseId() { return warehouseId; }
        public int getPlannedQty() { return plannedQty; }
        public int getConsumedQty() { return consumedQty; }
        public String getUnitCost() { return unitCost; }
        public void addConsumed(int qty) { consumedQty += qty; }
    }

    /** 成本提供者：优先历史计价成本，回退采购价。 */
    public interface CostProvider {
        String costFor(String productRecordId);
    }

    /** createAssemblyOrder：展开 BOM、分配组件、计算行金额。 */
    public static AssemblyOrder createOrder(WmsDocumentStore.State state, Scope scope, AssemblyDraft draft,
                                            List<Demand> bomLines, CostProvider costProvider) {
        List<Demand> demand = AssemblyRules.expandComponents(bomLines, draft.getPlannedQty(), draft.getOverrides());
        state.repo.activeLocation(scope, draft.getLocationId(), draft.getWarehouseId(), "STORAGE");
        List<AllocationCandidate> candidates = new ArrayList<>();
        for (Balance balance : state.repo.findBalances(scope.getOrganizationId(), scope.getWorkspaceId())) {
            if (!draft.getWarehouseId().equals(balance.getWarehouseId())) continue;
            com.wimoor.erp.wms.enhance.Repository.Lot lot = state.repo.findLot(
                    scope.getOrganizationId(), scope.getWorkspaceId(), balance.getLotId());
            if (lot == null) continue;
            int available = WmsRules.availableQuantity(balance.snapshot());
            candidates.add(new AllocationCandidate(balance.getId(), lot.getProductRecordId(),
                    balance.getLotId(), balance.getLocationId(), balance.getWarehouseId(),
                    available, balance.getCreatedAt()));
        }
        AllocationResult allocation = AssemblyRules.allocateComponents(demand, candidates, draft.getWarehouseId());
        if (!allocation.getShortages().isEmpty()) {
            throw new WmsError(AssemblyRules.shortageMessage(allocation.getShortages()), 422);
        }
        List<AssemblyOrderLine> lines = new ArrayList<>();
        for (AssemblyRules.AllocatedLine line : allocation.getLines()) {
            String unitCost = costProvider.costFor(line.getComponentProductRecordId());
            lines.add(new AssemblyOrderLine(WmsDocumentStore.newId(), line.getBalanceId(),
                    line.getComponentProductRecordId(), line.getLotId(), line.getLocationId(),
                    line.getWarehouseId(), line.getPlannedQty(), unitCost));
        }
        return new AssemblyOrder(WmsDocumentStore.newId(), scope.getActorUserId(), draft.getProductRecordId(),
                draft.getLocationId(), draft.getWarehouseId(), draft.getNumber(), draft.getPlannedQty(), lines);
    }

    /** validateAssemblyCompletion：整单完成校验。 */
    public static List<AssemblyOrderLine> validateCompletion(WmsDocumentStore.State state, Scope scope,
                                                             CommandInput input, AssemblyOrder order) {
        if ("DRAFT".equals(order.getStatus())) {
            // 合法
        } else {
            throw new WmsError("组装单已完成或已作废。", 409);
        }
        if (order.getRevision() != input.getExpectedRevision()) throw new WmsError("组装单已变更，请刷新。", 409);
        if (!order.getCreatedBy().equals(scope.getActorUserId())) throw new WmsError("仅创建人可提交组装完成。", 403);
        if (order.getLines().isEmpty()) throw new WmsError("组装单没有组件明细。", 422);
        state.repo.activeLocation(scope, order.getLocationId(), order.getWarehouseId(), "STORAGE");
        List<AssemblyOrderLine> selected = new ArrayList<>();
        for (AssemblyOrderLine line : order.getLines()) {
            if (line.getPlannedQty() - line.getConsumedQty() > 0) selected.add(line);
        }
        if (selected.isEmpty()) throw new WmsError("组件已全部耗用，无需重复完成。", 409);
        for (AssemblyOrderLine line : selected) {
            Balance balance = state.repo.findBalance(scope.getOrganizationId(), scope.getWorkspaceId(),
                    line.getBalanceId());
            if (balance == null) throw new WmsError("组件余额不存在。", 404);
            int remaining = line.getPlannedQty() - line.getConsumedQty();
            if (remaining > WmsRules.availableQuantity(balance.snapshot())) {
                throw new WmsError("组件可用库存不足，请刷新后重新创建组装单。", 422);
            }
        }
        return selected;
    }

    /** 执行结果。 */
    public static final class AssemblyResult {
        private final int revision;
        private final String componentCost;
        private final String unitCost;
        private final List<String> movementIds;

        public AssemblyResult(int revision, String componentCost, String unitCost, List<String> movementIds) {
            this.revision = revision;
            this.componentCost = componentCost;
            this.unitCost = unitCost;
            this.movementIds = movementIds;
        }

        public int getRevision() { return revision; }
        public String getComponentCost() { return componentCost; }
        public String getUnitCost() { return unitCost; }
        public List<String> getMovementIds() { return movementIds; }
    }

    /** executeAssembly：扣组件、产成品入库、滚成本。 */
    public static AssemblyResult execute(WmsDocumentStore.State state, Scope scope, CommandInput input,
                                         String commandId, AssemblyOrder order, String finishedLotId) {
        List<AssemblyOrderLine> selected = validateCompletion(state, scope, input, order);
        List<String> movementIds = new ArrayList<>();
        List<ConsumedLine> costLines = new ArrayList<>();
        for (AssemblyOrderLine line : selected) {
            Balance balance = state.repo.findBalance(scope.getOrganizationId(), scope.getWorkspaceId(),
                    line.getBalanceId());
            int qty = line.getPlannedQty() - line.getConsumedQty();
            movementIds.add(state.repo.move(scope, commandId, movementIds.size(), balance,
                    new com.wimoor.erp.wms.enhance.Repository.Delta(-qty, 0, 0, 0, 0),
                    new com.wimoor.erp.wms.enhance.Repository.Source("ASSEMBLY_COMPLETE",
                            "ASSEMBLY_ORDER", order.getId(), line.getId())).getId());
            line.addConsumed(qty);
            costLines.add(new ConsumedLine(qty, line.getUnitCost()));
        }
        state.repo.ensureBalances(scope, java.util.Arrays.asList(
                new LocationLot(order.getLocationId(), order.getWarehouseId(), finishedLotId)));
        Balance finished = state.repo.findBalance(scope.getOrganizationId(), scope.getWorkspaceId(),
                Repository.balanceId(scope, order.getLocationId(), finishedLotId));
        if (finished != null) {
            movementIds.add(state.repo.move(scope, commandId, movementIds.size(), finished,
                    new com.wimoor.erp.wms.enhance.Repository.Delta(order.getPlannedQty(), 0, 0, 0, 0),
                    new com.wimoor.erp.wms.enhance.Repository.Source("ASSEMBLY_COMPLETE",
                            "ASSEMBLY_ORDER", order.getId(), null)).getId());
        }
        String componentCost = AssemblyRules.totalComponentCost(costLines);
        String unitCost = AssemblyRules.rolledUnitCost(componentCost, order.getPlannedQty());
        order.setStatus("COMPLETED");
        order.incrementRevision();
        return new AssemblyResult(order.getRevision(), componentCost, unitCost, movementIds);
    }
}
