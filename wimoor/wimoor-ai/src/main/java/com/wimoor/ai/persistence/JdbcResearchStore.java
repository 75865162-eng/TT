package com.wimoor.ai.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wimoor.ai.research.ResearchStore;
import com.wimoor.ai.research.ResearchTypes.ResearchRun;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/**
 * 真实 MySQL 的选品研究存储。整个 ResearchRun 以 JSON 落 state_json，
 * transaction() 通过先 save 再执行 work 保证"计费请求发出前已落盘"（crash-safe，不重放不确定扣费）。
 */
public final class JdbcResearchStore implements ResearchStore {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();

    public JdbcResearchStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    private String orgOf(ResearchRun run) {
        return run.scope == null ? "system" : String.valueOf(run.scope.organizationId);
    }
    private String userOf(ResearchRun run) {
        return run.scope == null ? "system" : String.valueOf(run.scope.ownerId);
    }

    @Override
    public void create(ResearchRun run) {
        long t = System.currentTimeMillis();
        try {
            String json = mapper.writeValueAsString(run);
            jdbc.update("INSERT INTO ai_research_run(id,org_id,user_id,state_json,stage,status,checkpoint,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?)",
                    run.id, orgOf(run), userOf(run), json, String.valueOf(run.stage),
                    run.status == null ? "RUNNING" : run.status, run.revision, t, t);
        } catch (Exception e) {
            if (e instanceof RuntimeException) throw (RuntimeException) e;
            throw new IllegalStateException("RESEARCH_SERIALIZE_FAILED", e);
        }
    }

    @Override
    public ResearchRun read(String id) {
        List<String> rows = jdbc.queryForList("SELECT state_json FROM ai_research_run WHERE id=?", String.class, id);
        if (rows.isEmpty() || rows.get(0) == null) return null;
        try { return mapper.readValue(rows.get(0), ResearchRun.class); }
        catch (Exception e) { throw new IllegalStateException("RESEARCH_DESERIALIZE_FAILED", e); }
    }

    @Override
    public <T> T transaction(String id, Work<T> work) {
        ResearchRun run = read(id);
        if (run == null) throw new IllegalStateException("RESEARCH_RUN_NOT_FOUND: " + id);
        // Save 实现：立即把当前 run 状态落盘（含 checkpoint 推进），先于任何计费调用
        Save save = () -> {
            try {
                String json = mapper.writeValueAsString(run);
                jdbc.update("UPDATE ai_research_run SET state_json=?,stage=?,status=?,checkpoint=?,updated_at=? WHERE id=?",
                        json, String.valueOf(run.stage), run.status == null ? "RUNNING" : run.status,
                        run.revision, System.currentTimeMillis(), id);
            } catch (Exception e) {
                if (e instanceof RuntimeException) throw (RuntimeException) e;
                throw new IllegalStateException("RESEARCH_SAVE_FAILED", e);
            }
        };
        return work.apply(run, save);
    }
}
