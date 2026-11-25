package com.wimoor.ai.skill;

import com.wimoor.ai.skill.SkillTypes.SkillRunEvent;
import java.util.List;

/**
 * Skill 执行边界。移植说明：runner.ts 的 {@code runSkill} 是 async generator；Java 侧抽象为接口，
 * 由 {@link Runner} 提供真实实现， Skill 工具层只依赖本接口（便于替换/测试），复用执行逻辑。
 */
@FunctionalInterface
public interface SkillRunner {

    /** 执行 Skill 并返回有序事件序列（step/delta/done/error），空报告绝不伪造。 */
    List<SkillRunEvent> run(Runner.RunOptions options, Runner.RunHooks hooks);
}
