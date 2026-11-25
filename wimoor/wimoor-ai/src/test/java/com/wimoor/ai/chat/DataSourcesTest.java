package com.wimoor.ai.chat;

import com.wimoor.ai.chat.ChatTypes.ChatScope;
import com.wimoor.ai.chat.DataSources.AmazonCredential;
import com.wimoor.ai.chat.DataSources.ChatDataSourceSnapshot;
import com.wimoor.ai.chat.DataSources.ChatDataSourceStatus;
import com.wimoor.ai.chat.DataSources.ChatDataProvider;
import com.wimoor.ai.chat.DataSources.ChatDataSourceException;
import com.wimoor.ai.chat.DataSources.CredentialSet;
import com.wimoor.ai.chat.DataSources.InMemoryCredentialInspector;
import com.wimoor.ai.chat.DataSources.InMemoryDataSourceConfigStore;
import com.wimoor.ai.chat.DataSources.ScopeItem;
import com.wimoor.ai.chat.DataSources.SellerSpriteSettings;
import com.wimoor.ai.chat.DataSources.SellfoxCredential;
import com.wimoor.ai.chat.DataSources.Store;
import com.wimoor.ai.chat.DataSources.XiyouSettings;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 覆盖 data-sources.ts：凭证ACTIVE/过期判定、范围摘要、付费标记、启停门禁。 */
class DataSourcesTest {

    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-11-25T00:00:00Z"), ZoneOffset.UTC);
    private static final Instant FUTURE = Instant.parse("2030-01-01T00:00:00Z");
    private static final Instant PAST = Instant.parse("2020-01-01T00:00:00Z");
    private final ChatScope scope = new ChatScope("org", "ws", "user");

    private InMemoryCredentialInspector inspector() {
        return new InMemoryCredentialInspector(new CredentialSet(
                List.of(new AmazonCredential("amz-active", "ACTIVE", FUTURE,
                        Arrays.asList("t1", "t2"), Arrays.asList(
                                new ScopeItem("S1", Arrays.asList("US", "DE")),
                                new ScopeItem("S2", Arrays.asList("DE", "UK", "JP")))),
                        new AmazonCredential("amz-expired", "ACTIVE", PAST, List.of("old"), List.of())),
                List.of(new SellfoxCredential("sf-active", "ACTIVE", FUTURE,
                        List.of("t1"), Arrays.asList("s1", "s2"))),
                Arrays.asList(new Store("s1", "店铺一", "US"), new Store("s2", "店铺二", "DE")),
                new SellerSpriteSettings(true, "key", "https://seller.example", "US"),
                new XiyouSettings(true, "token", "https://xiyou.example")));
    }

    @Test
    void inspectsConfiguredSourcesAndScopeSummaries() {
        List<ChatDataSourceStatus> sources = DataSources.inspectChatDataSources(scope, inspector(), FIXED);
        assertEquals(4, sources.size());
        assertEquals("amazon", sources.get(0).getProvider().wire());
        assertTrue(sources.get(0).isConfigured());
        assertEquals(2, sources.get(0).getToolCount());
        assertEquals("2 个卖家账号 · 4 个站点", sources.get(0).getScopeSummary());
        assertEquals("店铺一（US）、店铺二（DE）", sources.get(1).getScopeSummary());
        assertTrue(sources.get(2).isPaidExternal());
        assertEquals(9, sources.get(2).getToolCount());
        assertTrue(sources.get(3).isConfigured());
        assertEquals(DataSources.XIYOU_READ_TOOLS.size(), sources.get(3).getToolCount());
        assertTrue(sources.stream().allMatch(ChatDataSourceStatus::isReadOnly));
    }

    @Test
    void expiredCredentialIsNotConfigured() {
        InMemoryCredentialInspector expired = new InMemoryCredentialInspector(new CredentialSet(
                List.of(new AmazonCredential("old", "ACTIVE", PAST, List.of("t"), List.of())),
                null, null, null, null));
        ChatDataSourceStatus amazon = DataSources.inspectChatDataSources(scope, expired, FIXED).get(0);
        assertFalse(amazon.isConfigured());
        assertEquals("未配置只读 MCP 凭证", amazon.getScopeSummary());
    }

    @Test
    void saveOnlyEnablesProvidersWithActiveCredentials() {
        InMemoryDataSourceConfigStore store = new InMemoryDataSourceConfigStore();
        ChatDataSourceSnapshot snapshot = DataSources.saveChatDataSourceConfig(
                scope, Arrays.asList("amazon", "sellfox", "bogus", "amazon"), inspector(), store, FIXED);
        assertEquals(Arrays.asList(ChatDataProvider.AMAZON, ChatDataProvider.SELLFOX),
                snapshot.getEnabledProviders());
        assertTrue(snapshot.isReady());
        assertEquals(Arrays.asList("amazon", "sellfox"), store.load(scope).getEnabledProviders());
    }

    @Test
    void emptySaveMarksNotReady() {
        InMemoryDataSourceConfigStore store = new InMemoryDataSourceConfigStore();
        ChatDataSourceSnapshot snapshot = DataSources.saveChatDataSourceConfig(
                scope, List.of(), inspector(), store, FIXED);
        assertFalse(snapshot.isReady());
        assertTrue(snapshot.getEnabledProviders().isEmpty());
    }

    @Test
    void requireEnabledThrowsWithCodeWhenNotConfigured() {
        ChatDataSourceException ex = assertThrows(ChatDataSourceException.class, () ->
                DataSources.requireEnabledChatDataSources(scope, inspector(),
                        new InMemoryDataSourceConfigStore(), FIXED));
        assertEquals("MCP_NOT_CONFIGURED", ex.getCode());
        assertTrue(ex.getMessage().contains("尚未配置数据源"));
    }

    @Test
    void snapshotFiltersConfiguredAgainstStoredSelection() {
        InMemoryDataSourceConfigStore store = new InMemoryDataSourceConfigStore();
        store.upsert(scope, Arrays.asList("sellfox", "amazon"), FIXED.instant());
        ChatDataSourceSnapshot snapshot = DataSources.getChatDataSourceSnapshot(scope, inspector(), store, FIXED);
        assertEquals(Arrays.asList("sellfox", "amazon"),
                Arrays.asList(snapshot.getEnabledProviders().get(0).wire(), snapshot.getEnabledProviders().get(1).wire()));
    }
}
