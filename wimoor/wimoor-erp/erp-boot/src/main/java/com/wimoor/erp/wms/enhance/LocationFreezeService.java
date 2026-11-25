package com.wimoor.erp.wms.enhance;

import com.wimoor.erp.wms.enhance.Repository.Location;
import com.wimoor.erp.wms.enhance.WmsContracts.Action;
import com.wimoor.erp.wms.enhance.WmsContracts.CommandInput;
import com.wimoor.erp.wms.enhance.WmsContracts.Scope;

/**
 * 库位冻结服务（对应源文件 location-freeze-service.ts）。
 * 移植了：validateLocationFreeze 的库位存在/版本/冻结状态/父级启用校验，
 * 以及 execute 的冻结状态切换。库位操作锁独立于库存数量桶与预占。
 */
public final class LocationFreezeService {

    private LocationFreezeService() {}

    /** validateLocationFreeze。 */
    public static Location validate(WmsDocumentStore.State state, Scope scope, CommandInput input) {
        Action action = input.getPayload().getAction();
        if (action != Action.LOCATION_FREEZE && action != Action.LOCATION_UNFREEZE) {
            throw new WmsError("非库位冻结动作。", 400);
        }
        Location location = state.repo.findLocation(scope.getOrganizationId(), scope.getWorkspaceId(),
                input.getAggregateId());
        if (location == null) throw new WmsError("库位不存在。", 404);
        if (location.getRevision() != input.getExpectedRevision()) throw new WmsError("库位已变更，请重新申请。");
        if (location.isFrozen() == (action == Action.LOCATION_FREEZE)) {
            throw new WmsError("库位冻结状态已变化，请刷新。");
        }
        boolean parentsEnabled = Boolean.TRUE.equals(
                state.repo.findAreaEnabled(location.getAreaId()))
                && Boolean.TRUE.equals(state.repo.findWarehouseEnabled(location.getWarehouseId()));
        if (action == Action.LOCATION_FREEZE && (!location.isEnabled() || !parentsEnabled)) {
            throw new WmsError("只能冻结已启用的库位。", 422);
        }
        return location;
    }

    /** executeLocationFreeze：返回更新后的版本。 */
    public static int execute(WmsDocumentStore.State state, Scope scope, CommandInput input) {
        Location location = validate(state, scope, input);
        location.setFrozen(input.getPayload().getAction() == Action.LOCATION_FREEZE);
        return location.getRevision();
    }
}
