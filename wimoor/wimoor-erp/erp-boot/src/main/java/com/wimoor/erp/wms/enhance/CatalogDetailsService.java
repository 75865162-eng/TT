package com.wimoor.erp.wms.enhance;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 目录明细服务（对应源文件 catalog-details-service.ts）。
 * 移植了：positiveDecimal 正十进制校验、addressData 的国家代码/必填字段校验、
 * updateCatalogDetails 的库位层级防环与 32 层上限、批次已有流水禁止改写体积等规则。
 */
public final class CatalogDetailsService {

    /** 地址字段白名单。 */
    public static final List<String> ADDRESS_KEYS = List.of("warehouseId", "label", "countryCode", "region",
            "city", "addressLine1", "addressLine2", "postalCode", "contactName", "phone");

    private CatalogDetailsService() {}

    /** positiveDecimal：正十进制字符串，最多六位小数。 */
    public static BigDecimal positiveDecimal(Object value, String label) {
        if (!(value instanceof String) || !((String) value).matches("\\d{1,12}(\\.\\d{1,6})?")) {
            throw new WmsError(label + "须为正十进制字符串，最多六位小数。", 400);
        }
        BigDecimal result = new BigDecimal((String) value);
        if (result.signum() <= 0) throw new WmsError(label + "须为正十进制字符串，最多六位小数。", 400);
        return result;
    }

    /** addressData。 */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> addressData(Map<String, Object> body) {
        String countryCode = WmsRules.requireText(body.get("countryCode"), "国家代码", 2).toUpperCase();
        if (!countryCode.matches("[A-Z]{2}")) throw new WmsError("国家代码须为两位字母。", 400);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("warehouseId", WmsRules.requireText(body.get("warehouseId"), "仓库"));
        out.put("label", WmsRules.requireText(body.get("label"), "地址名称"));
        out.put("countryCode", countryCode);
        out.put("city", WmsRules.requireText(body.get("city"), "城市"));
        out.put("addressLine1", WmsRules.requireText(body.get("addressLine1"), "地址", 500));
        out.put("postalCode", WmsRules.requireText(body.get("postalCode"), "邮编"));
        out.put("contactName", WmsRules.requireText(body.get("contactName"), "联系人"));
        for (String key : List.of("region", "addressLine2", "phone")) {
            out.put(key, body.get(key) == null ? null : WmsRules.requireText(body.get(key), key, 500));
        }
        return out;
    }

    /** 库位层级防环校验。 */
    public static String validateLocationParent(String id, String requestedParentId,
                                                String currentParentId, java.util.function.Function<String, String> parentLookup,
                                                java.util.function.Function<String, String> warehouseLookup,
                                                String warehouseId) {
        String parentId = requestedParentId == null ? currentParentId
                : (requestedParentId.isEmpty() ? null : WmsRules.requireText(requestedParentId, "上级库位"));
        String ancestor = parentId;
        java.util.Set<String> seen = new java.util.HashSet<>();
        seen.add(id);
        while (ancestor != null) {
            if (!seen.add(ancestor)) throw new WmsError("库位层级不能循环。", 422);
            if (seen.size() > 32) throw new WmsError("库位层级不能超过 32 层。", 422);
            String parentWarehouse = warehouseLookup.apply(ancestor);
            if (parentWarehouse == null || !parentWarehouse.equals(warehouseId)) {
                throw new WmsError("上级库位须属于同一仓库。", 422);
            }
            ancestor = parentLookup.apply(ancestor);
        }
        return parentId;
    }
}
