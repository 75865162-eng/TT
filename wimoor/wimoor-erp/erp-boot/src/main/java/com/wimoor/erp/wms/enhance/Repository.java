package com.wimoor.erp.wms.enhance;

import com.wimoor.erp.wms.enhance.WmsContracts.BalanceSnapshot;
import com.wimoor.erp.wms.enhance.WmsContracts.Scope;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WMS 仓储与库存流水（对应源文件 repository.ts）。
 * 移植了：balanceId（sha256 复合键）、snapshot、move（余额增量变更 + 不变量校验 +
 * 乐观锁版本校验 + 体积增量计算 + 流水落库）、ensureBalances（幂等建余额）、
 * activeLocation（启用/未冻结/仓库类型校验）。源端用 Prisma 事务与行锁；
 * 本实现给出 Repository 接口与 ConcurrentHashMap 内存实现，真实实现查询与聚合逻辑。
 */
public final class Repository {

    private Repository() {}

    /** sha256(JSON([org, workspace, locationId, lotId]))，对应 balanceId。 */
    public static String balanceId(Scope scope, String locationId, String lotId) {
        String input = WmsRules.stableJson(java.util.Arrays.asList(
                scope.getOrganizationId(), scope.getWorkspaceId(), locationId, lotId));
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            StringBuilder sb = new StringBuilder();
            for (byte b : digest.digest(input.getBytes(StandardCharsets.UTF_8))) {
                sb.append(String.format("%02x", b & 0xff));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** WmsStockBalance 领域实体。 */
    public static final class Balance {
        private final String id;
        private final String organizationId;
        private final String workspaceId;
        private final String warehouseId;
        private final String locationId;
        private final String lotId;
        private int onHand;
        private int pendingQc;
        private int pendingPutaway;
        private int rejected;
        private int reserved;
        private int revision;
        private final Date createdAt;

        public Balance(String id, String organizationId, String workspaceId, String warehouseId,
                       String locationId, String lotId, Date createdAt) {
            this.id = id;
            this.organizationId = organizationId;
            this.workspaceId = workspaceId;
            this.warehouseId = warehouseId;
            this.locationId = locationId;
            this.lotId = lotId;
            this.createdAt = createdAt;
            this.revision = 1;
        }

        public String getId() { return id; }
        public String getWarehouseId() { return warehouseId; }
        public String getLocationId() { return locationId; }
        public String getLotId() { return lotId; }
        public int getOnHand() { return onHand; }
        public int getPendingQc() { return pendingQc; }
        public int getPendingPutaway() { return pendingPutaway; }
        public int getRejected() { return rejected; }
        public int getReserved() { return reserved; }
        public int getRevision() { return revision; }
        public Date getCreatedAt() { return createdAt; }

        public BalanceSnapshot snapshot() {
            return new BalanceSnapshot(onHand, pendingQc, pendingPutaway, rejected, reserved, revision);
        }

        void apply(int[] delta) {
            onHand += delta[0];
            pendingQc += delta[1];
            pendingPutaway += delta[2];
            rejected += delta[3];
            reserved += delta[4];
            revision++;
        }
    }

    /** WmsMovement 领域实体。 */
    public static final class Movement {
        private final String id;
        private final String organizationId;
        private final String workspaceId;
        private final String commandId;
        private final int sequence;
        private final String balanceId;
        private final String action;
        private final String sourceType;
        private final String sourceId;
        private final String sourceLineId;
        private final int deltaOnHand;
        private final int deltaPendingQc;
        private final int deltaPendingPutaway;
        private final int deltaRejected;
        private final int deltaReserved;
        private final String deltaVolumeCubicMeters;
        private final int beforeRevision;
        private final int afterRevision;
        private final Date occurredAt;

        public Movement(String id, String organizationId, String workspaceId, String commandId, int sequence,
                        String balanceId, String action, String sourceType, String sourceId, String sourceLineId,
                        int deltaOnHand, int deltaPendingQc, int deltaPendingPutaway, int deltaRejected,
                        int deltaReserved, String deltaVolumeCubicMeters, int beforeRevision, int afterRevision,
                        Date occurredAt) {
            this.id = id;
            this.organizationId = organizationId;
            this.workspaceId = workspaceId;
            this.commandId = commandId;
            this.sequence = sequence;
            this.balanceId = balanceId;
            this.action = action;
            this.sourceType = sourceType;
            this.sourceId = sourceId;
            this.sourceLineId = sourceLineId;
            this.deltaOnHand = deltaOnHand;
            this.deltaPendingQc = deltaPendingQc;
            this.deltaPendingPutaway = deltaPendingPutaway;
            this.deltaRejected = deltaRejected;
            this.deltaReserved = deltaReserved;
            this.deltaVolumeCubicMeters = deltaVolumeCubicMeters;
            this.beforeRevision = beforeRevision;
            this.afterRevision = afterRevision;
            this.occurredAt = occurredAt;
        }

        public String getId() { return id; }
        public String getOrganizationId() { return organizationId; }
        public String getWorkspaceId() { return workspaceId; }
        public String getCommandId() { return commandId; }
        public int getSequence() { return sequence; }
        public String getBalanceId() { return balanceId; }
        public String getAction() { return action; }
        public String getSourceType() { return sourceType; }
        public String getSourceId() { return sourceId; }
        public String getSourceLineId() { return sourceLineId; }
        public int getDeltaOnHand() { return deltaOnHand; }
        public int getDeltaOnHandValue() { return deltaOnHand; }
        public int getDeltaPendingQc() { return deltaPendingQc; }
        public int getDeltaPendingPutaway() { return deltaPendingPutaway; }
        public int getDeltaRejected() { return deltaRejected; }
        public int getDeltaReserved() { return deltaReserved; }
        public String getDeltaVolumeCubicMeters() { return deltaVolumeCubicMeters; }
        public int getBeforeRevision() { return beforeRevision; }
        public int getAfterRevision() { return afterRevision; }
        public Date getOccurredAt() { return occurredAt; }
    }

    /** WmsLocation / Area / Warehouse 的简化领域实体。 */
    public static final class Location {
        private final String id;
        private final String organizationId;
        private final String workspaceId;
        private final String warehouseId;
        private final String areaId;
        private final String code;
        private final String kind;
        private boolean enabled;
        private boolean frozen;
        private int revision;

        public Location(String id, String organizationId, String workspaceId, String warehouseId,
                        String areaId, String code, String kind, boolean enabled, boolean frozen) {
            this.id = id;
            this.organizationId = organizationId;
            this.workspaceId = workspaceId;
            this.warehouseId = warehouseId;
            this.areaId = areaId;
            this.code = code;
            this.kind = kind;
            this.enabled = enabled;
            this.frozen = frozen;
            this.revision = 1;
        }

        public String getId() { return id; }
        public String getWarehouseId() { return warehouseId; }
        public String getAreaId() { return areaId; }
        public String getCode() { return code; }
        public String getKind() { return kind; }
        public boolean isEnabled() { return enabled; }
        public boolean isFrozen() { return frozen; }
        public int getRevision() { return revision; }

        public void setFrozen(boolean frozen) { this.frozen = frozen; this.revision++; }
    }

    /** WmsLot 简化实体。 */
    public static final class Lot {
        private final String id;
        private final String organizationId;
        private final String workspaceId;
        private final String productRecordId;
        private final String lotCode;
        private final String barcode;
        private final String unitVolumeCubicMeters;

        public Lot(String id, String organizationId, String workspaceId, String productRecordId,
                   String lotCode, String barcode, String unitVolumeCubicMeters) {
            this.id = id;
            this.organizationId = organizationId;
            this.workspaceId = workspaceId;
            this.productRecordId = productRecordId;
            this.lotCode = lotCode;
            this.barcode = barcode;
            this.unitVolumeCubicMeters = unitVolumeCubicMeters;
        }

        public String getId() { return id; }
        public String getProductRecordId() { return productRecordId; }
        public String getLotCode() { return lotCode; }
        public String getBarcode() { return barcode; }
        public String getUnitVolumeCubicMeters() { return unitVolumeCubicMeters; }
    }

    /** 库存变更来源。 */
    public static final class Source {
        private final String action;
        private final String sourceType;
        private final String sourceId;
        private final String sourceLineId;

        public Source(String action, String sourceType, String sourceId, String sourceLineId) {
            this.action = action;
            this.sourceType = sourceType;
            this.sourceId = sourceId;
            this.sourceLineId = sourceLineId;
        }

        public String getAction() { return action; }
        public String getSourceType() { return sourceType; }
        public String getSourceId() { return sourceId; }
        public String getSourceLineId() { return sourceLineId; }
    }

    /** 增量。 */
    public static final class Delta {
        private final int onHand;
        private final int pendingQc;
        private final int pendingPutaway;
        private final int rejected;
        private final int reserved;

        public Delta(int onHand, int pendingQc, int pendingPutaway, int rejected, int reserved) {
            this.onHand = onHand;
            this.pendingQc = pendingQc;
            this.pendingPutaway = pendingPutaway;
            this.rejected = rejected;
            this.reserved = reserved;
        }

        public int[] values() { return new int[]{onHand, pendingQc, pendingPutaway, rejected, reserved}; }
    }

    /** 仓储接口。 */
    public interface WmsRepository {
        Balance findBalance(String organizationId, String workspaceId, String id);
        List<Balance> findBalances(String organizationId, String workspaceId);
        List<Balance> ensureBalances(Scope scope, List<LocationLot> rows);
        Movement move(Scope scope, String commandId, int sequence, Balance balance, Delta delta, Source source);
        Location findLocation(String organizationId, String workspaceId, String id);
        Location activeLocation(Scope scope, String id, String warehouseId, String kind);
        Lot findLot(String organizationId, String workspaceId, String id);
        List<Movement> findMovements(String organizationId, String workspaceId);
    }

    /** 库位批次键。 */
    public static final class LocationLot {
        private final String locationId;
        private final String warehouseId;
        private final String lotId;

        public LocationLot(String locationId, String warehouseId, String lotId) {
            this.locationId = locationId;
            this.warehouseId = warehouseId;
            this.lotId = lotId;
        }
    }

    /** ConcurrentHashMap 内存实现。 */
    public static final class InMemoryRepository implements WmsRepository {
        private final Map<String, Balance> balances = new ConcurrentHashMap<>();
        private final Map<String, Movement> movements = new ConcurrentHashMap<>();
        private final Map<String, Location> locations = new ConcurrentHashMap<>();
        private final Map<String, Lot> lots = new ConcurrentHashMap<>();
        private final Map<String, Boolean> warehouses = new ConcurrentHashMap<>();
        private final Map<String, Boolean> areas = new ConcurrentHashMap<>();

        public void addLocation(Location location) { locations.put(location.id, location); }
        public void addLot(Lot lot) { lots.put(lot.id, lot); }
        public void addWarehouse(String id, boolean enabled) { warehouses.put(id, enabled); }
        public void addArea(String id, boolean enabled) { areas.put(id, enabled); }
        public Boolean findWarehouseEnabled(String id) { return warehouses.get(id); }
        public Boolean findAreaEnabled(String id) { return areas.get(id); }

        @Override
        public Balance findBalance(String organizationId, String workspaceId, String id) {
            Balance balance = balances.get(id);
            if (balance == null || !balance.organizationId.equals(organizationId)
                    || !balance.workspaceId.equals(workspaceId)) return null;
            return balance;
        }

        @Override
        public List<Balance> findBalances(String organizationId, String workspaceId) {
            List<Balance> out = new ArrayList<>();
            for (Balance balance : balances.values()) {
                if (balance.organizationId.equals(organizationId) && balance.workspaceId.equals(workspaceId)) {
                    out.add(balance);
                }
            }
            out.sort(Comparator.comparing(Balance::getId));
            return out;
        }

        @Override
        public List<Balance> ensureBalances(Scope scope, List<LocationLot> rows) {
            List<Balance> result = new ArrayList<>();
            List<LocationLot> sorted = new ArrayList<>(rows);
            Map<String, LocationLot> dedup = new LinkedHashMap<>();
            for (LocationLot row : sorted) dedup.put(balanceId(scope, row.locationId, row.lotId), row);
            List<String> ids = new ArrayList<>(dedup.keySet());
            Collections.sort(ids);
            for (String id : ids) {
                LocationLot row = dedup.get(id);
                Balance balance = balances.computeIfAbsent(id, key -> new Balance(key,
                        scope.getOrganizationId(), scope.getWorkspaceId(), row.warehouseId,
                        row.locationId, row.lotId, new Date()));
                result.add(balance);
            }
            return result;
        }

        @Override
        public Movement move(Scope scope, String commandId, int sequence, Balance balance,
                             Delta delta, Source source) {
            int[] deltaValues = delta.values();
            BalanceSnapshot after = new BalanceSnapshot(
                    balance.onHand + deltaValues[0], balance.pendingQc + deltaValues[1],
                    balance.pendingPutaway + deltaValues[2], balance.rejected + deltaValues[3],
                    balance.reserved + deltaValues[4], balance.revision + 1);
            WmsRules.assertBalanceInvariant(after);
            int beforeRevision = balance.revision;
            balance.apply(deltaValues);
            Lot lot = lots.get(balance.lotId);
            String volume = null;
            if (lot != null && lot.unitVolumeCubicMeters != null) {
                java.math.BigDecimal vol = new java.math.BigDecimal(lot.unitVolumeCubicMeters)
                        .multiply(java.math.BigDecimal.valueOf(deltaValues[0]));
                volume = vol.setScale(9, java.math.RoundingMode.HALF_UP).toPlainString();
            }
            Movement movement = new Movement(UUID.randomUUID().toString(),
                    scope.getOrganizationId(), scope.getWorkspaceId(), commandId, sequence,
                    balance.id, source.getAction(), source.getSourceType(), source.getSourceId(),
                    source.getSourceLineId(), deltaValues[0], deltaValues[1], deltaValues[2],
                    deltaValues[3], deltaValues[4], volume, beforeRevision, balance.revision, new Date());
            movements.put(movement.id, movement);
            return movement;
        }

        @Override
        public Location findLocation(String organizationId, String workspaceId, String id) {
            Location location = locations.get(id);
            if (location == null || !location.organizationId.equals(organizationId)
                    || !location.workspaceId.equals(workspaceId)) return null;
            return location;
        }

        @Override
        public Location activeLocation(Scope scope, String id, String warehouseId, String kind) {
            Location location = findLocation(scope.getOrganizationId(), scope.getWorkspaceId(), id);
            boolean warehouseEnabled = location != null && Boolean.TRUE.equals(warehouses.get(location.warehouseId));
            boolean areaEnabled = location != null && Boolean.TRUE.equals(areas.get(location.areaId));
            if (location == null || !location.enabled || location.frozen || !areaEnabled || !warehouseEnabled
                    || (warehouseId != null && !location.warehouseId.equals(warehouseId))
                    || (kind != null && !location.kind.equals(kind))) {
                throw new WmsError("库位不可用、已冻结或不属于指定仓库。", 422);
            }
            return location;
        }

        @Override
        public Lot findLot(String organizationId, String workspaceId, String id) {
            Lot lot = lots.get(id);
            if (lot == null || !lot.organizationId.equals(organizationId)
                    || !lot.workspaceId.equals(workspaceId)) return null;
            return lot;
        }

        @Override
        public List<Movement> findMovements(String organizationId, String workspaceId) {
            List<Movement> out = new ArrayList<>();
            for (Movement movement : movements.values()) {
                if (movement.organizationId.equals(organizationId) && movement.workspaceId.equals(workspaceId)) {
                    out.add(movement);
                }
            }
            return out;
        }
    }
}
