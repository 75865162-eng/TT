package com.wimoor.erp.wms.enhance;

import com.wimoor.erp.wms.enhance.AssemblyRules.AllocationCandidate;
import com.wimoor.erp.wms.enhance.AssemblyRules.Demand;
import com.wimoor.erp.wms.enhance.AssemblyRules.Shortage;
import com.wimoor.erp.wms.enhance.EventBus.Delivery;
import com.wimoor.erp.wms.enhance.EventBus.WmsEvent;
import com.wimoor.erp.wms.enhance.EventBus.WmsEventConsumer;
import com.wimoor.erp.wms.enhance.InventoryRiskRules.Assessment;
import com.wimoor.erp.wms.enhance.InventoryRiskRules.Evidence;
import com.wimoor.erp.wms.enhance.InventoryRiskRules.Policy;
import com.wimoor.erp.wms.enhance.InventoryRiskRules.Stock;
import com.wimoor.erp.wms.enhance.QueryService.Params;
import com.wimoor.erp.wms.enhance.Repository.Balance;
import com.wimoor.erp.wms.enhance.Repository.Location;
import com.wimoor.erp.wms.enhance.Repository.Lot;
import com.wimoor.erp.wms.enhance.WmsContracts.Action;
import com.wimoor.erp.wms.enhance.WmsContracts.CommandInput;
import com.wimoor.erp.wms.enhance.WmsContracts.CommandPayload;
import com.wimoor.erp.wms.enhance.WmsContracts.CountLine;
import com.wimoor.erp.wms.enhance.WmsContracts.DiscrepancyItem;
import com.wimoor.erp.wms.enhance.WmsContracts.DiscrepancyLine;
import com.wimoor.erp.wms.enhance.WmsContracts.InspectLine;
import com.wimoor.erp.wms.enhance.WmsContracts.LineQuantity;
import com.wimoor.erp.wms.enhance.WmsContracts.Scope;
import com.wimoor.erp.wms.enhance.WmsDocumentStore.State;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 迁移覆盖测试：针对纯规则与关键业务流程，用源 TS 的确定性输入输出做预期值。
 */
class WmsEnhanceMigrationTest {

    private static final Scope SCOPE = new Scope("org-1", "ws-1", "user-1");

    // ---------------- assembly-rules ----------------

    @Test
    void assemblyScaleAndFormat() {
        assertEquals(BigInteger.valueOf(1_500_000L), AssemblyRules.scaleCost("1.5"));
        assertEquals(BigInteger.valueOf(-2_000_000L), AssemblyRules.scaleCost("-2"));
        assertEquals("1.500000", AssemblyRules.formatCost(BigInteger.valueOf(1_500_000L)));
        assertEquals("-2.000000", AssemblyRules.formatCost(BigInteger.valueOf(-2_000_000L)));
        assertEquals("3.000000", AssemblyRules.lineAmount(2, "1.5"));
        assertThrows(WmsError.class, () -> AssemblyRules.scaleCost("1.1234567"));
    }

    @Test
    void assemblyRolledUnitCostHalfUp() {
        // 总成本 10.000000 / 产出 3 = 3.333333（半入）
        assertEquals("3.333333", AssemblyRules.rolledUnitCost("10", 3));
        assertThrows(WmsError.class, () -> AssemblyRules.rolledUnitCost("10", 0));
    }

    @Test
    void assemblyExpandAndAllocateFifo() {
        List<Demand> bom = Arrays.asList(new Demand("C1", 2), new Demand("C2", 1));
        List<Demand> demand = AssemblyRules.expandComponents(bom, 3,
                List.of(new AssemblyRules.AssemblyLineOverride("C2", 5)));
        assertEquals(2, demand.size());
        assertEquals("C1", demand.get(0).getComponentProductRecordId());
        assertEquals(6, demand.get(0).getQuantity());
        assertEquals(5, demand.get(1).getQuantity());

        Date now = new Date();
        List<AllocationCandidate> candidates = Arrays.asList(
                new AllocationCandidate("b1", "C1", "L1", "loc1", "wh1", 4, new Date(now.getTime() - 1000)),
                new AllocationCandidate("b2", "C1", "L2", "loc1", "wh1", 4, now),
                new AllocationCandidate("b3", "C2", "L3", "loc1", "wh1", 5, now));
        AssemblyRules.AllocationResult result = AssemblyRules.allocateComponents(demand, candidates, "wh1");
        assertTrue(result.getShortages().isEmpty());
        assertEquals(3, result.getLines().size());
        assertEquals(4, result.getLines().get(0).getPlannedQty());
        assertEquals("b1", result.getLines().get(0).getBalanceId());
        assertEquals(2, result.getLines().get(1).getPlannedQty());
    }

    @Test
    void assemblyShortageMessage() {
        List<Shortage> shortages = List.of(new Shortage("C1", 10, 3));
        assertEquals("组件库存不足：C1 需 10 可用 3", AssemblyRules.shortageMessage(shortages));
    }

    // ---------------- consumable-rules ----------------

    @Test
    void consumableQuantityIntegralBatch() {
        assertEquals(3, ConsumableRules.consumableQuantity("0.5", 6));
        assertThrows(WmsError.class, () -> ConsumableRules.consumableQuantity("0.5", 3));
        assertThrows(WmsError.class, () -> ConsumableRules.consumableQuantity("1.5", 1));
    }

    // ---------------- transfer-order-rules ----------------

    @Test
    void transferOrderStatusMachine() {
        assertEquals("DRAFT", TransferOrderRules.transferOrderStatus(List.of(
                line(10, 0, 0, 0))));
        assertEquals("PARTIALLY_SHIPPED", TransferOrderRules.transferOrderStatus(List.of(
                line(10, 4, 0, 0))));
        assertEquals("IN_TRANSIT", TransferOrderRules.transferOrderStatus(List.of(
                line(10, 10, 0, 0))));
        assertEquals("PARTIALLY_RECEIVED", TransferOrderRules.transferOrderStatus(List.of(
                line(10, 10, 4, 0))));
        assertEquals("COMPLETED", TransferOrderRules.transferOrderStatus(List.of(
                line(10, 10, 7, 3))));
    }

    private static TransferOrderRules.TransferOrderLine line(int q, int shipped, int received, int disc) {
        return new TransferOrderRules.TransferOrderLine("L1", q, shipped, received, disc);
    }

    @Test
    void transferOrderValidateQuantities() {
        List<LineQuantity> selected = List.of(new LineQuantity("L1", 6));
        assertDoesNotThrow(() -> TransferOrderRules.validateTransferQuantities(
                Action.TRANSFER_ORDER_SHIP, selected, List.of(line(10, 4, 0, 0))));
        assertThrows(WmsError.class, () -> TransferOrderRules.validateTransferQuantities(
                Action.TRANSFER_ORDER_SHIP, selected, List.of(line(10, 6, 0, 0))));
    }

    @Test
    void transferDiscrepancyWholeBatch() {
        List<DiscrepancyLine> lines = List.of(new DiscrepancyLine("L1", List.of(
                new DiscrepancyItem("SHORT_RECEIVED", 2, "e1"),
                new DiscrepancyItem("DAMAGED", 1, "e2"))));
        assertDoesNotThrow(() -> TransferOrderDiscrepancyRules.validateTransferDiscrepancy(
                lines, List.of(line(10, 10, 7, 0)))); // net in transit = 3
        assertThrows(WmsError.class, () -> TransferOrderDiscrepancyRules.validateTransferDiscrepancy(
                lines, List.of(line(10, 10, 6, 0)))); // net = 4
        Map<String, Integer> totals = TransferOrderDiscrepancyRules.sumDiscrepancyByCategory(
                lines.get(0).getDiscrepancies());
        assertEquals(2, totals.get("SHORT_RECEIVED"));
        assertEquals(1, totals.get("DAMAGED"));
    }

    // ---------------- inventory-risk-rules ----------------

    @Test
    void inventoryRiskAssessmentComplete() {
        Policy policy = new Policy("POL-1", "CNY", 30, "5", "EXP", "ALW");
        String start = "2026-01-01";
        String snap = "2026-01-31";
        List<Evidence> evidence = List.of(new Evidence("P1", start, snap, "3", "COMPLETE", "sales-ref",
                "100.000000", "80.000000", "5.000000", "val-ref"));
        List<Stock> stocks = List.of(new Stock("P1", "SKU1", "100", "COMPLETE", null));
        Assessment result = InventoryRiskRules.assessInventoryRisk(stocks, snap, policy, evidence);
        assertEquals("COMPLETE", result.getStatus());
        assertEquals(1, result.getItems().size());
        assertTrue(result.getItems().get(0).getStagnant()); // sold 3 <= 5
        assertEquals("20.000000", result.getItems().get(0).getProposedProvision());
        assertEquals("15.000000", result.getItems().get(0).getProvisionDelta());
        assertEquals("15.000000", result.getProvisionIncrease());
        assertEquals(2, ((List<?>) result.getVoucher().get("lines")).size());
    }

    @Test
    void inventoryRiskWindowMismatch() {
        Policy policy = new Policy("POL-1", "CNY", 30, "5", "EXP", "ALW");
        List<Evidence> evidence = List.of(new Evidence("P1", "2025-12-01", "2026-01-31", "3",
                "COMPLETE", "r", "100.000000", "80.000000", "5.000000", "v"));
        List<Stock> stocks = List.of(new Stock("P1", "SKU1", "100", "COMPLETE", null));
        assertThrows(WmsError.class, () -> InventoryRiskRules.assessInventoryRisk(
                stocks, "2026-01-31", policy, evidence));
    }

    // ---------------- view helpers ----------------

    @Test
    void reservationProgressStates() {
        assertEquals("CLOSED", ReservationView.reservationProgress(10, 5, 5, 0).getWorkflowStatus());
        assertEquals("AWAITING_PICK", ReservationView.reservationProgress(10, 0, 0, 0).getWorkflowStatus());
        assertEquals("PARTIALLY_PICKED", ReservationView.reservationProgress(10, 0, 0, 3).getWorkflowStatus());
        assertEquals("AWAITING_DISPATCH", ReservationView.reservationProgress(10, 0, 0, 10).getWorkflowStatus());
        assertEquals(7, ReservationView.reservationProgress(10, 0, 3, 0).getRemainingQty());
    }

    @Test
    void receiptActionRemainingAndPayload() {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("id", "L1");
        line.put("expectedQty", 10);
        line.put("receivedQty", 6);
        assertEquals(4, ReceiptActionView.receiptActionRemaining(line, Action.RECEIVE));
        Map<String, com.wimoor.erp.wms.enhance.WmsContracts.ActionValue> values = new LinkedHashMap<>();
        values.put("L1", new com.wimoor.erp.wms.enhance.WmsContracts.ActionValue("3", null, null, null));
        CommandPayload payload = ReceiptActionView.receiptActionPayload(List.of(line), Action.RECEIVE, values);
        assertEquals(Action.RECEIVE, payload.getAction());
        assertEquals(1, payload.getLines().size());
        assertEquals(3, payload.getLines().get(0).getQuantity());
        // 超过剩余量应报错
        values.put("L1", new com.wimoor.erp.wms.enhance.WmsContracts.ActionValue("5", null, null, null));
        assertThrows(WmsError.class, () -> ReceiptActionView.receiptActionPayload(
                List.of(line), Action.RECEIVE, values));
    }

    @Test
    void stockActionLimit() {
        Map<String, Object> item = new HashMap<>();
        item.put("onHand", 100);
        item.put("pendingQc", 10);
        item.put("pendingPutaway", 5);
        item.put("rejected", 3);
        item.put("reserved", 2);
        assertEquals(80, StockActionView.stockActionLimit(item, StockActionView.StockAction.SKU_CONVERT));
        item.put("stock", Map.of("locationFrozen", true));
        assertEquals(0, StockActionView.stockActionLimit(item, StockActionView.StockAction.RESERVE));
    }

    @Test
    void stocktakeSummaryGrossGainsAndLosses() {
        StocktakeSummary.CountLine l1 = new StocktakeSummary.CountLine("LOT1", 10, 12);
        StocktakeSummary.CountLine l2 = new StocktakeSummary.CountLine("LOT1", 8, 6);
        StocktakeService.Stocktake st = null;
        Map<String, Object> summary = StocktakeSummary.stocktakeSummary(false, "REVIEW",
                List.of(new StocktakeSummary.Location("LOC1", List.of(l1, l2))));
        assertEquals("COMPLETE", summary.get("dataStatus"));
        assertEquals("2", summary.get("gainQty"));
        assertEquals("2", summary.get("lossQty"));
        assertEquals("18", summary.get("baselineQty"));
        assertEquals("18", summary.get("countedQty"));
    }

    @Test
    void stocktakePrintEscapeAndBlind() {
        assertEquals("&lt;&amp;&quot;&#39;", StocktakePrint.escape("<&\"'"));
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("number", "ST-1");
        root.put("status", "DRAFT");
        root.put("mode", "SAMPLE");
        root.put("blind", true);
        Map<String, Object> location = new LinkedHashMap<>();
        location.put("location", Map.of("code", "A-01"));
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("lot", Map.of("lotCode", "LOT1", "barcode", "BC1"));
        line.put("countedQty", 5);
        line.put("baselineOnHand", 4);
        location.put("lines", List.of(line));
        root.put("locations", List.of(location));
        StocktakePrint.Rendered rendered = StocktakePrint.renderStocktakePrint(root);
        assertTrue(rendered.getDocument().isBlind());
        assertFalse(rendered.getHtml().contains("<td>4</td>")); // 盲盘不输出账面
        assertFalse(rendered.getHtml().contains("<th>差异</th>"));
    }

    @Test
    void inventoryRiskCsvInjectionProtection() {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("productRecordId", "=cmd");
        item.put("sku", "SKU1");
        item.put("quantity", "10");
        item.put("stagnant", true);
        item.put("proposedProvision", "1.000000");
        item.put("provisionDelta", "-1.000000");
        item.put("status", "COMPLETE");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("snapshotDate", "2026-01-31");
        result.put("currency", "CNY");
        result.put("items", List.of(item));
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("id", "RA-1");
        report.put("snapshotId", "SN-1");
        report.put("status", "DRAFT");
        report.put("requestHash", "hash");
        report.put("result", result);
        String csv = InventoryRiskExport.inventoryRiskCsv(report);
        assertTrue(csv.startsWith("\uFEFF"));
        assertTrue(csv.contains("\"'=cmd\""));
        assertTrue(csv.contains("\r\n"));
    }

    // ---------------- event bus ----------------

    @Test
    void eventBusPublishDispatchAndRetry() {
        EventBus.InMemoryEventBus bus = new EventBus.InMemoryEventBus();
        WmsEvent event = new WmsEvent("e1", "org-1", "ws-1", "c1", "test.v1", 1,
                new Date(), Map.of("k", "v"));
        bus.publish(event);
        List<WmsEventConsumer> consumers = List.of(new WmsEventConsumer() {
            private int calls = 0;
            public String id() { return "consumer.v1"; }
            public String eventType() { return "test.v1"; }
            public int schemaVersion() { return 1; }
            public void handle(WmsEvent e) { calls++; }
        });
        List<Delivery> pending = bus.pendingWmsEventDeliveries(consumers);
        assertEquals(1, pending.size());
        assertEquals(EventBus.ProcessResult.PROCESSED,
                bus.processWmsEventDelivery(pending.get(0).getId(), consumers));
        assertEquals(0, bus.pendingWmsEventDeliveries(consumers).size()); // 已处理不在 pending
        assertEquals(0, EventDispatch.dispatchPendingWmsEvents(bus, consumers).getDispatched());
    }

    @Test
    void eventBusRetryBackoff() {
        EventBus.InMemoryEventBus bus = new EventBus.InMemoryEventBus();
        bus.publish(new WmsEvent("e1", "org-1", "ws-1", "c1", "test.v1", 1, new Date(), Map.of()));
        WmsEventConsumer failing = new WmsEventConsumer() {
            public String id() { return "fail.v1"; }
            public String eventType() { return "test.v1"; }
            public int schemaVersion() { return 1; }
            public void handle(WmsEvent e) { throw new IllegalStateException("boom"); }
        };
        Delivery delivery = bus.pendingWmsEventDeliveries(List.of(failing)).get(0);
        assertThrows(IllegalStateException.class,
                () -> bus.processWmsEventDelivery(delivery.getId(), List.of(failing)));
        assertEquals(1, delivery.getAttempts());
        assertEquals("WMS_EVENT_CONSUMER_FAILED", delivery.getLastError());
        assertEquals(2000L, EventBus.retryDelay(1));
        assertEquals(15L * 60 * 1000, EventBus.retryDelay(99));
    }

    // ---------------- repository / move ----------------

    @Test
    void repositoryMoveAndInvariant() {
        State state = new State();
        state.repo.addWarehouse("WH1", true);
        state.repo.addArea("A1", true);
        state.repo.addLocation(new Location("LOC1", "org-1", "ws-1", "WH1", "A1", "A-01", "STORAGE", true, false));
        state.repo.addLot(new Lot("LOT1", "org-1", "ws-1", "P1", "LOT1", "BC1", "0.1"));
        Repository.Balance balance = state.repo.ensureBalances(SCOPE, List.of(
                new Repository.LocationLot("LOC1", "WH1", "LOT1"))).get(0);
        Repository.Movement m = state.repo.move(SCOPE, "cmd1", 0, balance,
                new Repository.Delta(10, 2, 0, 0, 0),
                new Repository.Source("RECEIVE", "RECEIPT", "r1", "l1"));
        assertEquals(10, balance.getOnHand());
        assertEquals(2, balance.getPendingQc());
        assertEquals(2, balance.getRevision());
        assertEquals(0, m.getSequence());
        assertEquals("1.000000000", m.getDeltaVolumeCubicMeters());
        assertThrows(WmsError.Conflict.class, () -> state.repo.move(SCOPE, "cmd2", 0, balance,
                new Repository.Delta(-20, 0, 0, 0, 0),
                new Repository.Source("X", "Y", "z", null)));
    }

    // ---------------- period summary / history ----------------

    @Test
    void inventoryPeriodBoundariesAndBuild() {
        Instant from = Instant.parse("2026-01-01T00:00:00Z");
        Instant to = Instant.parse("2026-01-03T00:00:00Z");
        InventoryPeriodSummary.Boundaries b = InventoryPeriodSummary.inventoryPeriodBoundaries(from, to, "DAY");
        assertEquals(2, b.getPeriods().size());
        assertThrows(WmsError.class, () -> InventoryPeriodSummary.inventoryPeriodBoundaries(from, to, "WEEK"));
        List<InventoryPeriodSummary.Aggregate> rows = List.of(
                new InventoryPeriodSummary.Aggregate("2026-01-01T00:00:00Z", "RECEIPT", "RECEIPT", "10", "0"),
                new InventoryPeriodSummary.Aggregate("2026-01-01T00:00:00Z", "DISPATCH", "RESERVATION", "0", "3"));
        List<Map<String, Object>> periods = InventoryPeriodSummary.buildInventoryPeriods(
                b.getPeriods(), "5", rows);
        assertEquals("5", periods.get(0).get("openingQty"));
        assertEquals("12", periods.get(0).get("closingQty"));
        Map<?, ?> categories = (Map<?, ?>) periods.get(0).get("categories");
        assertEquals("10", categories.get("receiptQty"));
        assertEquals("3", categories.get("shipmentQty"));
    }

    @Test
    void inventoryHistoryRangeValidation() {
        InventoryHistoryQuery.Range range = InventoryHistoryQuery.inventoryHistoryRange(
                "2026-01-01T00:00:00Z", "2026-01-02T00:00:00.000Z");
        assertEquals(Instant.parse("2026-01-01T00:00:00Z"), range.getFrom());
        assertThrows(WmsError.class, () -> InventoryHistoryQuery.inventoryHistoryRange(
                "2026-01-02T00:00:00Z", "2026-01-01T00:00:00Z"));
        assertThrows(WmsError.class, () -> InventoryHistoryQuery.inventoryHistoryRange(
                "bad", "2026-01-01T00:00:00Z"));
    }

    // ---------------- query service ----------------

    @Test
    void queryServicePrepareAndCursor() {
        Params params = new Params().set("limit", "2").set("status", "DRAFT");
        Map<String, Object> prepared = QueryService.prepare(SCOPE, "transfer-orders", params);
        assertEquals(2, prepared.get("limit"));
        assertEquals("DRAFT", ((Map<?, ?>) prepared.get("filters")).get("status"));
        Params bad = new Params().set("limit", "101");
        assertThrows(WmsError.class, () -> QueryService.prepare(SCOPE, "transfer-orders", bad));
        Params badStatus = new Params().set("status", "NOPE");
        assertThrows(WmsError.class, () -> QueryService.prepare(SCOPE, "transfer-orders", badStatus));
        QueryService.InMemoryQueryRepository repo = new QueryService.InMemoryQueryRepository();
        for (int i = 1; i <= 5; i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", "id" + i);
            repo.add("lots", row);
        }
        QueryService.Page page = QueryService.query(SCOPE, "lots", new Params().set("limit", "2"), repo);
        assertEquals(2, page.getItems().size());
        assertNotNull(page.getNextCursor());
        Params next = new Params().set("limit", "2").set("cursor", page.getNextCursor());
        QueryService.Page page2 = QueryService.query(SCOPE, "lots", next, repo);
        assertEquals("id3", page2.getItems().get(0).get("id"));
    }

    // ---------------- valuation / approvals / application ----------------

    @Test
    void valuationVoucher() {
        ValuationService.ValuedEntry entry = new ValuationService.ValuedEntry("M1", "R1", 2,
                "1.500000", "3.000000", "CNY", "INV", "OFF");
        Map<String, Object> voucher = ValuationService.valuationVoucher(List.of(entry));
        assertEquals("EXPLICIT_STANDARD_COST", voucher.get("method"));
        assertEquals(Boolean.FALSE, voucher.get("postingExecuted"));
        assertEquals(2, ((List<?>) voucher.get("lines")).size());
        Map<?, ?> total = (Map<?, ?>) ((List<?>) voucher.get("totals")).get(0);
        assertEquals("3.000000", total.get("debit"));
    }

    @Test
    void approvalsNeedsAndDecide() {
        assertTrue(Approvals.needsApproval(Action.TRANSFER));
        assertFalse(Approvals.needsApproval(Action.PICK));
        assertFalse(Approvals.needsApproval(Action.STOCKTAKE_START));
        State state = new State();
        CommandInput input = new CommandInput("ws-1", "B1", 1, "key1",
                new CommandPayload(Action.TRANSFER, null, null, null, null, null, 5,
                        null, null, 0, null, "LOC2", null, null, null, null));
        WmsDocumentStore.ApprovalRecord approval = Approvals.requestApproval(state, SCOPE, input);
        assertEquals("PENDING", approval.getStatus());
        assertEquals(Approvals.requestHash(input), approval.getRequestHash());
        Scope approver = new Scope("org-1", "ws-1", "user-2");
        WmsDocumentStore.ApprovalRecord decided = Approvals.decideApproval(state, approver,
                approval.getId(), 1, true);
        assertEquals("APPROVED", decided.getStatus());
        assertThrows(WmsError.class, () -> Approvals.decideApproval(state, SCOPE, approval.getId(), 2, true));
    }

    @Test
    void applicationServiceReserveAndIdempotency() {
        State state = new State();
        state.repo.addWarehouse("WH1", true);
        state.repo.addArea("A1", true);
        state.repo.addLocation(new Location("LOC1", "org-1", "ws-1", "WH1", "A1", "A-01", "STORAGE", true, false));
        state.repo.addLot(new Lot("LOT1", "org-1", "ws-1", "P1", "LOT1", "BC1", null));
        state.repo.ensureBalances(SCOPE, List.of(new Repository.LocationLot("LOC1", "WH1", "LOT1")));
        Balance balance = state.repo.findBalance("org-1", "ws-1", Repository.balanceId(SCOPE, "LOC1", "LOT1"));
        state.repo.move(SCOPE, "seed", 0, balance,
                new Repository.Delta(100, 0, 0, 0, 0),
                new Repository.Source("RECEIVE", "RECEIPT", "r", "l"));
        CommandInput input = new CommandInput("ws-1", balance.getId(), balance.getRevision(), "reserve-key-1",
                new CommandPayload(Action.RESERVE, null, null, null, null, "RES-1", 30,
                        null, null, 0, null, null, null, null, null, null));
        // RESERVE 需独立审批：申请人发起，另一人批准。
        WmsDocumentStore.ApprovalRecord approval = Approvals.requestApproval(state, SCOPE, input);
        Approvals.decideApproval(state, new Scope("org-1", "ws-1", "user-2"),
                approval.getId(), approval.getRevision(), true);
        ApplicationService.CommandResult r1 = ApplicationService.executeWmsCommand(
                state, SCOPE, input, approval.getId());
        assertEquals(30, state.repo.findBalance("org-1", "ws-1", balance.getId()).getReserved());
        assertEquals(1, r1.getResult().get("revision"));
        // 幂等重放返回相同 commandId
        ApplicationService.CommandResult r2 = ApplicationService.executeWmsCommand(
                state, SCOPE, input, approval.getId());
        assertEquals(r1.getCommandId(), r2.getCommandId());
        assertEquals(30, state.repo.findBalance("org-1", "ws-1", balance.getId()).getReserved());
    }

    @Test
    void applicationServiceRejectsFbaReserve() {
        State state = new State();
        CommandInput input = new CommandInput("ws-1", "B1", 1, "fba-reserve:1",
                new CommandPayload(Action.RESERVE, null, null, null, null, "RES-1", 1,
                        null, null, 0, null, null, null, null, null, null));
        assertThrows(WmsError.class, () -> ApplicationService.executeWmsCommand(state, SCOPE, input, null));
    }

    // ---------------- overseas charge ----------------

    @Test
    void overseasChargeAllocationQuantityMethod() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("allocationMethod", "QUANTITY");
        body.put("reference", "CH-1");
        body.put("currency", "CNY");
        body.put("amount", "10.000000");
        body.put("stage", "ACTUAL");
        body.put("manifestId", "MF-1");
        body.put("basis", List.of(Map.of("lineId", "L1"), Map.of("lineId", "L2")));
        List<OverseasChargeRules.PlanLine> lines = List.of(
                new OverseasChargeRules.PlanLine("L1", 1),
                new OverseasChargeRules.PlanLine("L2", 3));
        Map<String, Object> allocation = OverseasChargeRules.overseasChargeAllocation(
                body, lines, new ArrayList<>(),
                new OverseasChargeRules.Estimate("CNY", "10.000000"));
        assertEquals("10.000000", allocation.get("amount"));
        assertEquals("QUANTITY", allocation.get("allocationMethod"));
        Map<?, ?> estimate = (Map<?, ?>) allocation.get("estimate");
        assertEquals(Boolean.TRUE, estimate.get("comparable"));
        assertEquals("0.000000", estimate.get("difference"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> outLines = (List<Map<String, Object>>) allocation.get("lines");
        assertEquals("2.500000", outLines.get(0).get("allocatedAmount"));
        assertEquals("7.500000", outLines.get(1).get("allocatedAmount"));
    }

    // ---------------- receipt service ----------------

    @Test
    void receiptServiceReceiveAndInspect() {
        State state = new State();
        state.repo.addWarehouse("WH1", true);
        state.repo.addArea("A1", true);
        state.repo.addLocation(new Location("LOC1", "org-1", "ws-1", "WH1", "A1", "A-01", "RECEIVING", true, false));
        state.repo.addLot(new Lot("LOT1", "org-1", "ws-1", "P1", "LOT1", "BC1", null));
        state.repo.ensureBalances(SCOPE, List.of(new Repository.LocationLot("LOC1", "WH1", "LOT1")));
        WmsDocumentStore.ReceiptLineRecord line = new WmsDocumentStore.ReceiptLineRecord("RL1", "LOT1", 10);
        WmsDocumentStore.ReceiptRecord receipt = new WmsDocumentStore.ReceiptRecord(
                "R1", "WH1", "LOC1", "DRAFT", 1, "user-1", List.of(line));
        state.receipts.put(receipt.getId(), receipt);
        CommandInput receive = new CommandInput("ws-1", "R1", 1, "recv-1",
                new CommandPayload(Action.RECEIVE, null, List.of(new LineQuantity("RL1", 6)),
                        null, null, null, 0, null, null, 0, null, null, null, null, null, null));
        ReceiptService.ReceiptResult rr = ReceiptService.executeReceipt(state, SCOPE, receive, "cmd-r");
        assertEquals("RECEIVED", receipt.getStatus());
        assertEquals(6, line.getReceivedQty());
        assertEquals(1, rr.getMovementIds().size());

        CommandInput inspect = new CommandInput("ws-1", "R1", receipt.getRevision(), "insp-1",
                new CommandPayload(Action.INSPECT, null, null,
                        List.of(new InspectLine("RL1", 5, 1)), null, null, 0, null, null, 0,
                        null, null, null, null, null, null));
        ReceiptService.executeReceipt(state, SCOPE, inspect, "cmd-i");
        assertEquals("INSPECTED", receipt.getStatus());
        assertEquals(5, line.getAcceptedQty());
        assertEquals(1, line.getRejectedQty());
    }
}
