package com.wimoor.ai.skill;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/**
 * Skill manifest（移植自 src/lib/lili/skill-manifest.ts 的 getSkillManifest 核心）。
 * 版本即 SKILL.md 正文的 SHA-256；Skill 是流程文档，绝不是可执行代码。
 */
public final class SkillManifest {

    public static final String LILI_VERSION = "1.4.0";

    public static final class Manifest {
        private final String skillId;
        private final String name;
        private final String description;
        private final String sha256;
        private final String version;
        private final List<String> requiredTools;
        private final String source;

        public Manifest(String skillId, String name, String description, String sha256,
                        List<String> requiredTools) {
            this.skillId = skillId;
            this.name = name;
            this.description = description;
            this.sha256 = sha256;
            this.version = "sha256:" + sha256;
            this.requiredTools = requiredTools;
            this.source = "lili://skills/" + skillId + "/SKILL.md";
        }
        public String getSkillId() { return skillId; }
        public String getName() { return name; }
        public String getDescription() { return description; }
        public String getSha256() { return sha256; }
        public String getVersion() { return version; }
        public List<String> getRequiredTools() { return requiredTools; }
        public String getSource() { return source; }
    }

    /** 由 SKILL.md 正文构建 manifest；requiredTools 是原始引用，不代表当前可用。 */
    public static Manifest build(String skillId, String name, String description,
                                 String skillMarkdown, List<String> requiredTools) {
        if (skillId == null || skillMarkdown == null) throw new IllegalArgumentException("UNKNOWN_SKILL");
        return new Manifest(skillId, name, description, sha256(skillMarkdown), requiredTools);
    }

    /** 与 node:crypto createHash('sha256').update(text,'utf8') 等价。 */
    public static String sha256(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(String.format("%02x", b & 0xff));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
