package com.wimoor.erp.wms.enhance;

/**
 * 盘点打印服务（对应源文件 stocktake-print-service.ts）。
 * 移植了：exportStocktakePrint 的 ID 校验、单据存在性校验、渲染与审计元数据输出。
 * 源端通过 queryWms 读取单据；本实现接收已查询的单据原始数据并调用 StocktakePrint 渲染。
 */
public final class StocktakePrintService {

    private StocktakePrintService() {}

    /** exportStocktakePrint 的核心。 */
    public static StocktakePrint.Rendered export(Object rawStocktake) {
        if (rawStocktake == null) throw new WmsError("盘点单不存在。", 404);
        return StocktakePrint.renderStocktakePrint(rawStocktake);
    }
}
