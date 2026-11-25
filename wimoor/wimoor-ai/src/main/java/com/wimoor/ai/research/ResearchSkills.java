package com.wimoor.ai.research;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 五个现有 Skill 原文加载（移植自 src/lib/selection-research/skills.ts）。
 * 只读取请求的原文与哈希；不执行其中脚本、不抓取服务端替换版本。
 */
public final class ResearchSkills {

    private static final int MAX_SKILL_BYTES = 256_000;
    private static final Pattern TOOL_LINE = Pattern.compile("^\\s*-\\s+`([a-z][a-z0-9_]+)`\\s*$", Pattern.MULTILINE);

    private ResearchSkills() {}

    public static List<ResearchTypes.SkillSnapshot> loadResearchSkills(String root) {
        try {
            Path resolved = Paths.get(root).toRealPath();
            List<ResearchTypes.SkillSnapshot> out = new ArrayList<>();
            for (String name : ResearchTypes.SKILLS) {
                Path file = resolved.resolve(name).resolve("SKILL.md").toRealPath();
                if (!file.startsWith(resolved)) throw new ResearchError("SKILL_PATH_ESCAPE");
                byte[] bytes = Files.readAllBytes(file);
                if (bytes.length > MAX_SKILL_BYTES) throw new ResearchError("SKILL_TOO_LARGE");
                String markdown = new String(bytes, StandardCharsets.UTF_8);
                List<String> tools = new ArrayList<>();
                Matcher m = TOOL_LINE.matcher(markdown);
                while (m.find()) tools.add(m.group(1));
                out.add(new ResearchTypes.SkillSnapshot(name, file.toString(), sha256(bytes), markdown, tools));
            }
            return out;
        } catch (java.io.IOException e) {
            throw new ResearchError("RESEARCH_SKILL_IO", e);
        }
    }

    /** 部署主机上的内置原文（由宿主从现有 catalog 注入）。 */
    public static List<ResearchTypes.SkillSnapshot> loadBundledResearchSkills(
            Map<String, String> markdowns, Map<String, List<String>> tools) {
        List<ResearchTypes.SkillSnapshot> out = new ArrayList<>();
        for (String name : ResearchTypes.SKILLS) {
            String markdown = markdowns.get(name);
            if (markdown == null) throw new ResearchError("RESEARCH_SKILL_MISSING");
            byte[] bytes = markdown.getBytes(StandardCharsets.UTF_8);
            if (bytes.length > MAX_SKILL_BYTES) throw new ResearchError("SKILL_TOO_LARGE");
            List<String> declared = tools.getOrDefault(name, List.of());
            out.add(new ResearchTypes.SkillSnapshot(name, "lili://skills/" + name + "/SKILL.md",
                    sha256(bytes), markdown, declared));
        }
        return out;
    }

    private static String sha256(byte[] bytes) {
        try {
            StringBuilder sb = new StringBuilder();
            for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes))
                sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new ResearchError("RESEARCH_SKILL_HASH_FAILED", e);
        }
    }
}
