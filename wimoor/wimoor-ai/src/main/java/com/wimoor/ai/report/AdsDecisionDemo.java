package com.wimoor.ai.report;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Ported from {@code amazon-ads/decision-demo.ts}.
 *
 * <p>Isolated UI fixture model — never a server permission or execution gateway. The
 * state machine and conservative gates are preserved: draft -> pending -> approved ->
 * executing -> done/partial_failed/failed; every increase requires business evidence
 * (stock > 0, BuyBox held). Dry-run review is mandatory before submit/execute/finish,
 * and a completed order can only produce one inverse rollback order.
 */
public final class AdsDecisionDemo {

    private AdsDecisionDemo() {}

    public static final Map<String, String> STATUS_LABELS = new LinkedHashMap<>();
    static {
        STATUS_LABELS.put("draft", "草稿");
        STATUS_LABELS.put("pending", "待模拟审批");
        STATUS_LABELS.put("approved", "模拟批准");
        STATUS_LABELS.put("rejected", "模拟拒绝");
        STATUS_LABELS.put("executing", "模拟执行中");
        STATUS_LABELS.put("done", "模拟成功");
        STATUS_LABELS.put("partial_failed", "模拟部分失败");
        STATUS_LABELS.put("failed", "模拟失败");
        STATUS_LABELS.put("rolled_back", "已模拟回滚");
    }

    public static final class Capability {
        public String state;
        public String reason;

        public Capability(String state, String reason) {
            this.state = state;
            this.reason = reason;
        }
    }

    public static final class Entity {
        public String id;
        public String campaign;
        public String group;
        public String name;
        public String kind;
        public String match;
        public String status;
        public double bid;
        public double budget;
        public double rule;
        public List<String> asins = new ArrayList<>();
        public double spend;
        public double sales;

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id); m.put("campaign", campaign); m.put("group", group); m.put("name", name);
            m.put("kind", kind); m.put("match", match); m.put("status", status);
            m.put("bid", bid); m.put("budget", budget); m.put("rule", rule);
            m.put("asins", asins); m.put("spend", spend); m.put("sales", sales);
            return m;
        }
    }

    public static final class Asin {
        public String asin;
        public Integer stock;
        public Integer inbound;
        public Boolean buyBox;
        public Integer totalSales;
        public double adSales;
        public double spend;
    }

    public static final class Action {
        public String type;
        public String entityId;
        public double value;
        public String reason;
        public String keyword;
        public String match;
    }

    public static final class Item {
        public Action action;
        public Entity before;
        public Entity after;
        public String result = "waiting";
        public String error;
    }

    public static final class Order {
        public String id;
        public String status = "draft";
        public String source;
        public String tool;
        public boolean reviewed;
        public String inverseOf;
        public final List<Item> items = new ArrayList<>();
    }

    public static final class State {
        public String scope;
        public String market;
        public final List<Entity> entities = new ArrayList<>();
        public final List<Asin> asins = new ArrayList<>();
        public final List<Order> orders = new ArrayList<>();
        public Capability write;
        public Capability business;
    }

    public static State create(String account, String market) {
        State state = new State();
        state.scope = account + "/" + market;
        state.market = market;
        boolean us = "US".equals(market);
        state.write = new Capability(us ? "supported" : "blocked",
                us ? "仅 US SP 演示策略，非官方限额" : "缺少此站点已核验策略，演示写操作关闭");
        state.business = new Capability("northstar".equals(account) ? "supported" : "unsupported",
                "此演示账户没有 SP-API 经营能力");
        String[] ids = {"healthy", "empty", "lost", "unknown"};
        String[] asinIds = {"B0DEMO0001", "B0DEMO0002", "B0DEMO0003", "B0DEMO0004"};
        for (int i = 0; i < ids.length; i++) {
            state.entities.add(entity(state.scope, ids[i], "keyword " + ids[i], asinIds[i], "enabled"));
        }
        state.entities.add(entity(state.scope, "archived", "old basket", "B0DEMO0001", "archived"));
        Entity target = entity(state.scope, "target", "asin=\"B0DEMO0001\"", "B0DEMO0001", "enabled");
        target.kind = "target";
        target.match = "ASIN";
        state.entities.add(target);
        for (int i = 1; i <= 2; i++) {
            Entity campaign = entity(state.scope, "campaign-" + i, "Campaign 0" + i,
                    asinIds[i - 1], "enabled");
            campaign.kind = "campaign";
            campaign.group = "—";
            campaign.budget = i == 1 ? 30 : 45;
            campaign.rule = i == 1 ? 10 : 15;
            state.entities.add(campaign);
        }
        state.asins.add(asin("B0DEMO0001", 100, 20, true, 800, 240, 80));
        state.asins.add(asin("B0DEMO0002", 0, 40, true, null, 0, 60));
        state.asins.add(asin("B0DEMO0003", 5, 0, false, 200, 100, 50));
        state.asins.add(asin("B0DEMO0004", null, null, null, null, 0, 0));
        return state;
    }

    private static Entity entity(String scope, String id, String name, String asin, String status) {
        Entity entity = new Entity();
        entity.id = scope + "/" + id;
        entity.campaign = "DEMO SP " + id;
        entity.group = "Core keywords";
        entity.name = name;
        entity.kind = "keyword";
        entity.match = "EXACT";
        entity.status = status;
        entity.bid = 1;
        entity.budget = 30;
        entity.rule = 10;
        entity.asins.add(asin);
        entity.spend = 80;
        entity.sales = 240;
        return entity;
    }

    private static Asin asin(String id, Integer stock, Integer inbound, Boolean buyBox,
                             Integer totalSales, double adSales, double spend) {
        Asin row = new Asin();
        row.asin = id;
        row.stock = stock;
        row.inbound = inbound;
        row.buyBox = buyBox;
        row.totalSales = totalSales;
        row.adSales = adSales;
        row.spend = spend;
        return row;
    }

    public static String increaseBlockedReason(State state, Entity entity) {
        if (!"supported".equals(state.write.state)) return state.write.reason;
        if ("archived".equals(entity.status)) return "实体已归档";
        if (!"supported".equals(state.business.state)) return "无经营数据能力，不能确认加价安全";
        if (entity.asins.isEmpty()) return "缺少关联 ASIN";
        for (String id : entity.asins) {
            Asin context = null;
            for (Asin row : state.asins) if (id.equals(row.asin)) context = row;
            if (context == null || context.stock == null || context.buyBox == null) return "经营数据未知，需先核实";
            if (context.stock <= 0) return "断货，禁止提价或加预算";
            if (!context.buyBox) return "BuyBox 丢失，禁止提价或加预算";
        }
        return "";
    }

    public static String asinStatus(Asin row) {
        if (row.stock == null || row.buyBox == null) return "数据未知";
        if (row.stock <= 0) return "断货";
        if (!row.buyBox) return "BuyBox 丢失";
        return row.stock < 10 ? "低库存" : "正常";
    }

    private static String field(String type) {
        if ("update_budget".equals(type)) return "budget";
        if ("set_budget_rule".equals(type)) return "rule";
        if ("pause".equals(type)) return "status";
        return "bid";
    }

    private static String identity(Entity entity) {
        return entity.campaign + "|" + entity.group
                + "|" + entity.name.trim().toLowerCase().replaceAll("\\s+", " ") + "|" + entity.match;
    }

    private static Entity validate(State state, Action action) {
        if (!"supported".equals(state.write.state)) throw new IllegalStateException(state.write.reason);
        Entity entity = null;
        for (Entity row : state.entities) if (action.entityId.equals(row.id)) entity = row;
        if (entity == null) throw new IllegalStateException("实体不存在或不属于当前演示范围");
        if ("archived".equals(entity.status)) throw new IllegalStateException("实体已归档");
        if (action.reason == null || action.reason.trim().isEmpty()) throw new IllegalStateException("必须填写依据摘要");
        if (!Double.isFinite(action.value)) throw new IllegalStateException("值必须为有限数字");
        if ("pause".equals(action.type) && action.value != 0 && action.value != 1) throw new IllegalStateException("无效状态值");
        if (("update_bid".equals(action.type) || "create_keyword".equals(action.type))
                && (action.value < 0.02 || action.value > 100)) throw new IllegalStateException("US SP 演示保守闸门：0.02–100 USD；非官方限额");
        if ("update_budget".equals(action.type) && (action.value < 1 || action.value > 100000)) throw new IllegalStateException("预算超出演示范围：1–100000 USD");
        if ("set_budget_rule".equals(action.type) && (action.value < 0 || action.value > 30)) throw new IllegalStateException("规则提高比例超出演示范围：0–30%");
        boolean increase = "create_keyword".equals(action.type)
                || ("update_bid".equals(action.type) && action.value > entity.bid)
                || ("update_budget".equals(action.type) && action.value > entity.budget)
                || ("set_budget_rule".equals(action.type) && action.value > entity.rule)
                || ("pause".equals(action.type) && action.value == 1);
        if (increase) {
            String reason = increaseBlockedReason(state, entity);
            if (!reason.isEmpty()) throw new IllegalStateException(reason);
        }
        return entity;
    }

    private static final Map<String, String> ALLOWED = new HashMap<>();
    static {
        ALLOWED.put("review", "draft");
        ALLOWED.put("submit", "draft");
        ALLOWED.put("approve", "pending");
        ALLOWED.put("reject", "pending");
        ALLOWED.put("execute", "approved");
        ALLOWED.put("finish", "executing");
    }

    /** Applies one state-machine command; returns the new order status. */
    public static String transition(State state, String id, String command, List<String> failIds) {
        Order existing = null;
        for (Order order : state.orders) if (id.equals(order.id)) existing = order;
        if (existing == null) throw new IllegalStateException("变更单不存在");
        if (!id.equals(existing.id) || !ALLOWED.containsKey(command)
                || !ALLOWED.get(command).equals(existing.status)) {
            throw new IllegalStateException("状态已变化，不能重复操作");
        }
        if (Arrays.asList("submit", "execute", "finish").contains(command)) {
            if (!existing.reviewed) throw new IllegalStateException("必须先查看 dry-run 影响");
            for (Item item : existing.items) {
                validate(state, item.action);
                if (item.before != null) {
                    Entity current = null;
                    for (Entity row : state.entities) if (item.before.id.equals(row.id)) current = row;
                    if (current == null || !AdsCanonical.json(current).equals(AdsCanonical.json(item.before))) {
                        throw new IllegalStateException("当前值与 dry-run 快照冲突，请重新生成草稿");
                    }
                }
            }
        }
        if ("review".equals(command)) existing.reviewed = true;
        if ("submit".equals(command)) existing.status = "pending";
        if ("approve".equals(command)) existing.status = "approved";
        if ("reject".equals(command)) existing.status = "rejected";
        if ("execute".equals(command)) existing.status = "executing";
        if (!"finish".equals(command)) return existing.status;
        List<String> failures = failIds == null ? new ArrayList<>() : failIds;
        int success = 0;
        for (Item item : existing.items) {
            if (failures.contains(item.action.entityId)) {
                item.result = "failed";
                item.error = "DEMO_REJECTED：模拟上游拒绝，未应用";
            } else {
                item.result = "success";
                success++;
                if (item.before != null) {
                    for (int i = 0; i < state.entities.size(); i++) {
                        if (item.after.id.equals(state.entities.get(i).id)) {
                            state.entities.set(i, item.after);
                            break;
                        }
                    }
                } else {
                    state.entities.add(item.after);
                }
            }
        }
        existing.status = success == existing.items.size() ? "done" : success > 0 ? "partial_failed" : "failed";
        if (existing.inverseOf != null && "done".equals(existing.status)) {
            for (Order order : state.orders) {
                if (existing.inverseOf.equals(order.id)) order.status = "rolled_back";
            }
        }
        return existing.status;
    }
}
