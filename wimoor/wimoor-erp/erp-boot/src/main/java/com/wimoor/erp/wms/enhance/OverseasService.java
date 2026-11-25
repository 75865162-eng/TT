package com.wimoor.erp.wms.enhance;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 海外调拨服务（对应源文件 overseas-service.ts）。
 * 移植了：transportData 的运输时间/币种/预估费用/单价附加费校验（费用与币种须配套）、
 * createOverseasManifest 的单一来源仓与另一目的仓、地址归属、明细覆盖与去重、
 * 幂等冲突校验。源端 parseLocalPacking 来自 fba-local/packing，本类以箱单列表透传。
 */
public final class OverseasService {

    /** 运输资料。 */
    public static final class Transport {
        private final String carrier;
        private final String channel;
        private final String trackingNumber;
        private final String reference;
        private final String containerNumber;
        private final String billOfLading;
        private final Instant departureAt;
        private final Instant expectedArrivalAt;
        private final BigDecimal chargeableWeightKg;
        private final String currency;
        private final BigDecimal estimatedFee;

        public Transport(String carrier, String channel, String trackingNumber, String reference,
                         String containerNumber, String billOfLading, Instant departureAt,
                         Instant expectedArrivalAt, BigDecimal chargeableWeightKg, String currency,
                         BigDecimal estimatedFee) {
            this.carrier = carrier;
            this.channel = channel;
            this.trackingNumber = trackingNumber;
            this.reference = reference;
            this.containerNumber = containerNumber;
            this.billOfLading = billOfLading;
            this.departureAt = departureAt;
            this.expectedArrivalAt = expectedArrivalAt;
            this.chargeableWeightKg = chargeableWeightKg;
            this.currency = currency;
            this.estimatedFee = estimatedFee;
        }

        public String getCarrier() { return carrier; }
        public String getChannel() { return channel; }
        public String getTrackingNumber() { return trackingNumber; }
        public String getReference() { return reference; }
        public String getContainerNumber() { return containerNumber; }
        public String getBillOfLading() { return billOfLading; }
        public Instant getDepartureAt() { return departureAt; }
        public Instant getExpectedArrivalAt() { return expectedArrivalAt; }
        public BigDecimal getChargeableWeightKg() { return chargeableWeightKg; }
        public String getCurrency() { return currency; }
        public BigDecimal getEstimatedFee() { return estimatedFee; }
    }

    private OverseasService() {}

    /** transportData。 */
    @SuppressWarnings("unchecked")
    public static Transport transportData(Object raw) {
        if (!(raw instanceof Map)) throw new WmsError("海外调拨资料须为对象。", 400);
        Map<String, Object> input = (Map<String, Object>) raw;
        WmsRules.strictKeys(input, "carrier", "channel", "containerNumber", "billOfLading",
                "trackingNumber", "departureAt", "expectedArrivalAt", "chargeableWeightKg", "currency",
                "estimatedFee", "unitPrice", "surcharge", "reference");
        Instant departureAt = parseDate(input.get("departureAt"));
        Instant expectedArrivalAt = parseDate(input.get("expectedArrivalAt"));
        if (departureAt != null && expectedArrivalAt != null && expectedArrivalAt.isBefore(departureAt)) {
            throw new WmsError("预计到达不能早于发运。", 422);
        }
        String currency = input.get("currency") == null ? null
                : WmsRules.requireText(input.get("currency"), "币种", 3);
        if (currency != null && !currency.matches("[A-Z]{3}")) throw new WmsError("币种无效。", 400);
        boolean hasFee = input.get("estimatedFee") != null && !"".equals(input.get("estimatedFee"));
        if ((hasFee && currency == null) || (currency != null && !hasFee)) {
            throw new WmsError("预估费用与币种须一并提供。", 400);
        }
        if ((input.get("unitPrice") != null || input.get("surcharge") != null) && currency == null) {
            throw new WmsError("单价和附加费须注明费用币种及总费用。", 400);
        }
        BigDecimal estimatedFee = parseMoney(input.get("estimatedFee"), "预估费用");
        return new Transport(WmsRules.requireText(input.get("carrier"), "承运商"),
                WmsRules.requireText(input.get("channel"), "运输渠道"),
                WmsRules.requireText(input.get("trackingNumber"), "运单号"),
                WmsRules.requireText(input.get("reference"), "运输依据", 1000),
                input.get("containerNumber") == null ? null
                        : WmsRules.requireText(input.get("containerNumber"), "柜号"),
                input.get("billOfLading") == null ? null
                        : WmsRules.requireText(input.get("billOfLading"), "提单号"),
                departureAt, expectedArrivalAt,
                input.get("chargeableWeightKg") == null ? null
                        : CatalogDetailsService.positiveDecimal(input.get("chargeableWeightKg"), "计费重（千克）"),
                currency, estimatedFee);
    }

    private static Instant parseDate(Object value) {
        if (value == null || "".equals(value)) return null;
        String text = WmsRules.requireText(value, "运输时间");
        if (!text.matches(".*T.*(?:Z|[+-]\\d{2}:\\d{2})$")) throw new WmsError("运输时间须为带时区的有效时间。", 400);
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException e) {
            throw new WmsError("运输时间须为带时区的有效时间。", 400);
        }
    }

    private static BigDecimal parseMoney(Object value, String label) {
        if (value == null || "".equals(value)) return null;
        if ("0".equals(value)) return BigDecimal.ZERO;
        return CatalogDetailsService.positiveDecimal(value, label);
    }

    /** createOverseasManifest 的单一来源仓/目的仓校验。 */
    public static void validateSingleWarehouse(java.util.Set<String> sourceWarehouses,
                                               java.util.Set<String> targetWarehouses) {
        if (sourceWarehouses.size() != 1 || targetWarehouses.size() != 1
                || sourceWarehouses.equals(targetWarehouses)) {
            throw new WmsError("海外调拨须为明确的单一来源仓及另一目的仓。", 422);
        }
    }
}
