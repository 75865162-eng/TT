package com.wimoor.ai.persistence;

import com.wimoor.ai.chat.Billing;
import com.wimoor.ai.chat.Billing.LedgerEntry;
import com.wimoor.ai.chat.Billing.PointsLedger;
import com.wimoor.ai.chat.Billing.Settlement;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 真实积分账本：MySQL 持久化，request_key 唯一键保证幂等扣分/释放。
 * 与 InMemoryPointsLedger 语义一致：影子模式只计量不阻断、异常不外抛、单价按 provider。
 */
public final class JdbcPointsLedger implements PointsLedger {

    private final JdbcTemplate jdbc;
    private final String orgId;
    private final String userId;

    public JdbcPointsLedger(JdbcTemplate jdbc, String orgId, String userId) {
        this.jdbc = jdbc;
        this.orgId = orgId;
        this.userId = userId;
        // 余额账户缺省初始化（幂等）
        jdbc.update("INSERT IGNORE INTO ai_points_balance(org_id,user_id,balance,enforce,updated_at) VALUES(?,?,?,0,?)",
                orgId, userId, BigDecimal.ZERO, System.currentTimeMillis());
    }

    private static boolean bool(Object v) {
        if (v == null) return false;
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof Number) return ((Number) v).intValue() != 0;
        return "1".equals(String.valueOf(v));
    }

    @Override
    public BigDecimal getBalance() {
        BigDecimal b = jdbc.queryForObject("SELECT balance FROM ai_points_balance WHERE org_id=? AND user_id=?",
                BigDecimal.class, orgId, userId);
        return b == null ? BigDecimal.ZERO : b;
    }

    @Override
    public boolean isEnforce() {
        Integer e = jdbc.queryForObject("SELECT enforce FROM ai_points_balance WHERE org_id=? AND user_id=?",
                Integer.class, orgId, userId);
        return e != null && e == 1;
    }

    @Override
    public BigDecimal priceFor(String provider) {
        BigDecimal price = Billing.SELECTION_RESEARCH_PRICE.get(provider);
        return price == null ? BigDecimal.ZERO : price;
    }

    @Override
    public Settlement settle(String requestKey, String provider, BigDecimal price) {
        // 幂等：已存在直接返回，不重复扣分
        Integer exists = jdbc.queryForObject("SELECT COUNT(*) FROM ai_points_ledger WHERE request_key=?", Integer.class, requestKey);
        if (exists != null && exists > 0) return new Settlement(false, false, true, BigDecimal.ZERO, requestKey);

        BigDecimal cost = price == null ? BigDecimal.ZERO : price;
        boolean enforce = isEnforce();
        BigDecimal balance = getBalance();
        boolean insufficient = balance.compareTo(cost) < 0;
        long t = System.currentTimeMillis();
        try {
            if (insufficient && enforce) {
                jdbc.update("INSERT INTO ai_points_ledger(request_key,org_id,user_id,provider,points,charged,blocked,released,created_at) VALUES(?,?,?,?,?,0,1,0,?)",
                        requestKey, orgId, userId, provider, cost, t);
                return new Settlement(false, true, false, BigDecimal.ZERO, requestKey);
            }
            jdbc.update("INSERT INTO ai_points_ledger(request_key,org_id,user_id,provider,points,charged,blocked,released,created_at) VALUES(?,?,?,?,?,1,0,0,?)",
                    requestKey, orgId, userId, provider, cost, t);
            jdbc.update("UPDATE ai_points_balance SET balance=balance-?,updated_at=? WHERE org_id=? AND user_id=?",
                    cost, t, orgId, userId);
            return new Settlement(true, false, false, cost, requestKey);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // 并发重放：唯一键冲突 → 幂等返回
            return new Settlement(false, false, true, BigDecimal.ZERO, requestKey);
        } catch (Exception e) {
            // 计量异常绝不成为对话新故障源：影子模式降级为只记不扣
            return new Settlement(false, false, true, BigDecimal.ZERO, requestKey);
        }
    }

    @Override
    public void release(String requestKey) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT charged,released,points FROM ai_points_ledger WHERE request_key=?", requestKey);
        if (rows.isEmpty()) return;
        Map<String, Object> r = rows.get(0);
        if (bool(r.get("released"))) return;
        boolean charged = bool(r.get("charged"));
        if (charged) {
            BigDecimal pts = (BigDecimal) r.get("points");
            jdbc.update("UPDATE ai_points_balance SET balance=balance+?,updated_at=? WHERE org_id=? AND user_id=?",
                    pts, System.currentTimeMillis(), orgId, userId);
        }
        jdbc.update("UPDATE ai_points_ledger SET released=1 WHERE request_key=?", requestKey);
    }

    @Override
    public List<LedgerEntry> entries() {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT request_key,provider,points,charged,blocked,released FROM ai_points_ledger WHERE org_id=? AND user_id=? ORDER BY created_at",
                orgId, userId);
        List<LedgerEntry> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            LedgerEntry e = new LedgerEntry((String) r.get("request_key"), (String) r.get("provider"),
                    (BigDecimal) r.get("points"), bool(r.get("charged")),
                    bool(r.get("blocked")));
            if (bool(r.get("released"))) e.setReleased(true);
            out.add(e);
        }
        return out;
    }
}
