package com.wimoor.ai.skill;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SkillManifestTest {

    @Test
    void sha256MatchesKnownVector() {
        // 标准 SHA-256 向量：空串与 "abc"
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                SkillManifest.sha256(""));
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                SkillManifest.sha256("abc"));
    }

    @Test
    void buildManifestUsesSha256AsVersion() {
        SkillManifest.Manifest m = SkillManifest.build(
                "amazon-niche-finder", "细分市场发现", "找细分",
                "abc", List.of("meta_search_skills", "data_query"));
        assertEquals("sha256:ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                m.getVersion());
        assertEquals("lili://skills/amazon-niche-finder/SKILL.md", m.getSource());
        assertEquals(2, m.getRequiredTools().size());
    }

    @Test
    void unknownSkillThrows() {
        assertThrows(IllegalArgumentException.class, () -> SkillManifest.build(null, "x", "y", "z", List.of()));
    }
}
