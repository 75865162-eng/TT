package com.wimoor.ai.lili;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 移植自 src/lib/lili/skill-bundle.ts：把 shipped Skill 说明打成 ZIP。
 * 使用 JDK ZipOutputStream 替代 JSZip（DEFLATE），只包含 SKILL.md 说明，不含凭证或可安装 MCP 配置。
 */
public final class SkillBundle {

    private SkillBundle() {}

    private static final String INSTALL_MD =
        "# Lili 技能说明包\n\n本包保留技能说明，不包含凭证或可自动安装的 MCP 配置。\n\n"
        + "将所需 skills/<slug>/SKILL.md 安装到自己的技能目录前，请先阅读内容。说明中的工具名不代表已接入或已获授权；外部写入仍须审批。\n\n"
        + "主系统个人 Amazon MCP 在同步中心管理，使用独立个人凭证与明确店铺范围。/api/lili/mcp 支持匿名 Skill 发现和已配对 Bearer 设备的数据访问；设置 → AI 与 MCP → Lili MCP 可生成一次性配对码。\n";

    public static byte[] createSkillBundle() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.setMethod(ZipOutputStream.DEFLATED);
            put(zip, "INSTALL.md", INSTALL_MD);
            for (Catalog.CatalogSkill skill : Catalog.listSkills()) {
                String markdown = Catalog.skillMarkdown(skill.slug);
                if (markdown == null) throw new IllegalStateException("LILI_SKILL_BODY_MISSING");
                put(zip, "skills/" + skill.slug + "/SKILL.md", markdown);
            }
        } catch (java.io.IOException e) {
            throw new IllegalStateException("LILI_BUNDLE_FAILED", e);
        }
        return out.toByteArray();
    }

    private static void put(ZipOutputStream zip, String name, String content) throws java.io.IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setMethod(ZipEntry.DEFLATED);
        zip.putNextEntry(entry);
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
