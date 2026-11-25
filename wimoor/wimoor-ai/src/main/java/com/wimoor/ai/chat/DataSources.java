package com.wimoor.ai.chat;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 数据源面板配置与启停状态。移植自 {@code src/lib/ai-chat/data-sources.ts}。
 * 四套凭证只读取本地持久化的启用状态/元数据，绝不在此阶段连接外部付费 API、
 * 不做工具发现，避免预检产生费用或网络副作用。真实 DB/凭证读取抽象为接口 + 内存实现。
 */
public final class DataSources {

    private DataSources() {}

    /** 四个受控只读 provider。 */
    public enum ChatDataProvider {
        AMAZON("amazon"), SELLFOX("sellfox"), SELLERSPRITE("sellersprite"), XIYOU("xiyou");
        private final String wire;
        ChatDataProvider(String wire) { this.wire = wire; }
        public String wire() { return wire; }
        public static ChatDataProvider from(String value) {
            if (value == null) return null;
            for (ChatDataProvider p : values()) if (p.wire.equals(value)) return p;
            return null;
        }
    }

    public static final List<String> CHAT_DATA_PROVIDERS = Arrays.asList(
            "amazon", "sellfox", "sellersprite", "xiyou");

    /** 西柚已审核只读工具白名单规模（等价 xiyouResearchReadTools.size）。 */
    public static final Set<String> XIYOU_READ_TOOLS = Set.of(
            "product_search", "product_detail", "keyword_rank", "market_insight", "review_digest", "traffic_source");

    public static final class ChatDataSourceStatus {
        private final ChatDataProvider provider;
        private final String label;
        private final boolean configured;
        private final String credentialName;
        private final int toolCount;
        private final String scopeSummary;
        private final boolean readOnly = true;
        private final boolean paidExternal;
        public ChatDataSourceStatus(ChatDataProvider provider, String label, boolean configured,
                                    String credentialName, int toolCount, String scopeSummary, boolean paidExternal) {
            this.provider = provider; this.label = label; this.configured = configured;
            this.credentialName = credentialName; this.toolCount = toolCount;
            this.scopeSummary = scopeSummary; this.paidExternal = paidExternal;
        }
        public ChatDataProvider getProvider() { return provider; }
        public String getLabel() { return label; }
        public boolean isConfigured() { return configured; }
        public String getCredentialName() { return credentialName; }
        public int getToolCount() { return toolCount; }
        public String getScopeSummary() { return scopeSummary; }
        public boolean isReadOnly() { return readOnly; }
        public boolean isPaidExternal() { return paidExternal; }
    }

    public static final class ChatDataSourceSnapshot {
        private final List<ChatDataSourceStatus> sources;
        private final List<ChatDataProvider> enabledProviders;
        private final String confirmedAt;
        private final boolean ready;
        public ChatDataSourceSnapshot(List<ChatDataSourceStatus> sources, List<ChatDataProvider> enabledProviders,
                                      String confirmedAt, boolean ready) {
            this.sources = sources; this.enabledProviders = enabledProviders;
            this.confirmedAt = confirmedAt; this.ready = ready;
        }
        public List<ChatDataSourceStatus> getSources() { return sources; }
        public List<ChatDataProvider> getEnabledProviders() { return enabledProviders; }
        public String getConfirmedAt() { return confirmedAt; }
        public boolean isReady() { return ready; }
    }

    /** 凭证/店铺元数据（只读，不含 token 值本身）。 */
    public static final class AmazonCredential {
        private final String name; private final String status; private final Instant expiresAt;
        private final List<String> tools; private final List<ScopeItem> scopes;
        public AmazonCredential(String name, String status, Instant expiresAt, List<String> tools, List<ScopeItem> scopes) {
            this.name = name; this.status = status; this.expiresAt = expiresAt; this.tools = tools; this.scopes = scopes;
        }
        public String getName() { return name; }
        public String getStatus() { return status; }
        public Instant getExpiresAt() { return expiresAt; }
        public List<String> getTools() { return tools; }
        public List<ScopeItem> getScopes() { return scopes; }
    }

    public static final class ScopeItem {
        private final String sellerAccountId;
        private final List<String> marketplaceIds;
        public ScopeItem(String sellerAccountId, List<String> marketplaceIds) {
            this.sellerAccountId = sellerAccountId; this.marketplaceIds = marketplaceIds;
        }
        public String getSellerAccountId() { return sellerAccountId; }
        public List<String> getMarketplaceIds() { return marketplaceIds; }
    }

    public static final class SellfoxCredential {
        private final String name; private final String status; private final Instant expiresAt;
        private final List<String> tools; private final List<String> storeIds;
        public SellfoxCredential(String name, String status, Instant expiresAt, List<String> tools, List<String> storeIds) {
            this.name = name; this.status = status; this.expiresAt = expiresAt; this.tools = tools; this.storeIds = storeIds;
        }
        public String getName() { return name; }
        public String getStatus() { return status; }
        public Instant getExpiresAt() { return expiresAt; }
        public List<String> getTools() { return tools; }
        public List<String> getStoreIds() { return storeIds; }
    }

    public static final class Store {
        private final String id; private final String name; private final String marketplace;
        public Store(String id, String name, String marketplace) {
            this.id = id; this.name = name; this.marketplace = marketplace;
        }
        public String getId() { return id; }
        public String getName() { return name; }
        public String getMarketplace() { return marketplace; }
    }

    public static final class SellerSpriteSettings {
        private final boolean enabled; private final String apiKey; private final String serverUrl;
        private final String marketplace;
        public SellerSpriteSettings(boolean enabled, String apiKey, String serverUrl, String marketplace) {
            this.enabled = enabled; this.apiKey = apiKey; this.serverUrl = serverUrl; this.marketplace = marketplace;
        }
        public boolean isEnabled() { return enabled; }
        public String getApiKey() { return apiKey; }
        public String getServerUrl() { return serverUrl; }
        public String getMarketplace() { return marketplace; }
    }

    public static final class XiyouSettings {
        private final boolean enabled; private final String mcpToken; private final String mcpUrl;
        public XiyouSettings(boolean enabled, String mcpToken, String mcpUrl) {
            this.enabled = enabled; this.mcpToken = mcpToken; this.mcpUrl = mcpUrl;
        }
        public boolean isEnabled() { return enabled; }
        public String getMcpToken() { return mcpToken; }
        public String getMcpUrl() { return mcpUrl; }
    }

    public static final class CredentialSet {
        private final List<AmazonCredential> amazonCreds;
        private final List<SellfoxCredential> sellfoxCreds;
        private final List<Store> stores;
        private final SellerSpriteSettings sellerSprite;
        private final XiyouSettings xiyou;
        public CredentialSet(List<AmazonCredential> amazonCreds, List<SellfoxCredential> sellfoxCreds,
                             List<Store> stores, SellerSpriteSettings sellerSprite, XiyouSettings xiyou) {
            this.amazonCreds = amazonCreds == null ? Collections.emptyList() : amazonCreds;
            this.sellfoxCreds = sellfoxCreds == null ? Collections.emptyList() : sellfoxCreds;
            this.stores = stores == null ? Collections.emptyList() : stores;
            this.sellerSprite = sellerSprite; this.xiyou = xiyou;
        }
        public List<AmazonCredential> getAmazonCreds() { return amazonCreds; }
        public List<SellfoxCredential> getSellfoxCreds() { return sellfoxCreds; }
        public List<Store> getStores() { return stores; }
        public SellerSpriteSettings getSellerSprite() { return sellerSprite; }
        public XiyouSettings getXiyou() { return xiyou; }
    }

    /** 凭证读取边界（ERP 侧由 listMcpCredentials/toolConfigStore 等实现）。 */
    public interface CredentialInspector {
        CredentialSet inspect(ChatTypes.ChatScope scope);
    }

    public static final class InMemoryCredentialInspector implements CredentialInspector {
        private final CredentialSet set;
        public InMemoryCredentialInspector(CredentialSet set) {
            this.set = set == null ? new CredentialSet(null, null, null, null, null) : set;
        }
        @Override public CredentialSet inspect(ChatTypes.ChatScope scope) { return set; }
    }

    public static final class StoredConfig {
        private final List<String> enabledProviders;
        private final Instant confirmedAt;
        public StoredConfig(List<String> enabledProviders, Instant confirmedAt) {
            this.enabledProviders = enabledProviders; this.confirmedAt = confirmedAt;
        }
        public List<String> getEnabledProviders() { return enabledProviders; }
        public Instant getConfirmedAt() { return confirmedAt; }
    }

    /** 用户确认启用的数据源配置存储边界。 */
    public interface DataSourceConfigStore {
        StoredConfig load(ChatTypes.ChatScope scope);
        void upsert(ChatTypes.ChatScope scope, List<String> enabledProviders, Instant confirmedAt);
    }

    public static final class InMemoryDataSourceConfigStore implements DataSourceConfigStore {
        private final Map<String, StoredConfig> configs = new LinkedHashMap<>();
        @Override public StoredConfig load(ChatTypes.ChatScope scope) { return configs.get(scope.key()); }
        @Override public void upsert(ChatTypes.ChatScope scope, List<String> enabledProviders, Instant confirmedAt) {
            configs.put(scope.key(), new StoredConfig(new ArrayList<>(enabledProviders), confirmedAt));
        }
    }

    /** 数据源门禁异常；code=MCP_NOT_CONFIGURED 时由 API 转为 409。 */
    public static final class ChatDataSourceException extends RuntimeException {
        private final String code;
        public ChatDataSourceException(String code, String message) { super(message); this.code = code; }
        public String getCode() { return code; }
    }

    /** ACTIVE 且未过期才视为可用凭证（等价 activeCredential）。 */
    public static <T> T activeCredential(List<T> rows, java.util.function.Function<T, String> status,
                                         java.util.function.Function<T, Instant> expiresAt, Instant now) {
        if (rows == null) return null;
        for (T row : rows) {
            if ("ACTIVE".equals(status.apply(row)) && expiresAt.apply(row) != null
                    && expiresAt.apply(row).isAfter(now)) return row;
        }
        return null;
    }

    static String amazonScopeSummary(List<ScopeItem> scopes) {
        if (scopes == null || scopes.isEmpty()) return "未限定账号/站点（按 ERP 授权）";
        int sellers = 0;
        Set<String> markets = new LinkedHashSet<>();
        for (ScopeItem scope : scopes) {
            if (scope == null) continue;
            if (scope.getSellerAccountId() != null && !scope.getSellerAccountId().isEmpty()) sellers++;
            if (scope.getMarketplaceIds() != null) {
                for (String id : scope.getMarketplaceIds()) markets.add(String.valueOf(id));
            }
        }
        return sellers + " 个卖家账号 · " + markets.size() + " 个站点";
    }

    static String siteCodeForDisplay(String marketplace) {
        if (marketplace == null || marketplace.isEmpty()) return "";
        String value = marketplace;
        if (value.contains("_")) value = value.substring(value.lastIndexOf('_') + 1);
        return value.toUpperCase(Locale.ROOT);
    }

    static String sellfoxScopeSummary(List<String> storeIds, List<Store> stores) {
        if (storeIds == null || storeIds.isEmpty()) return "全部可见店铺（按数据访问授权）";
        List<String> names = new ArrayList<>();
        for (String id : storeIds) {
            Store store = null;
            if (stores != null) for (Store s : stores) if (s.getId().equals(id)) { store = s; break; }
            if (store == null) { names.add(id); continue; }
            String site = siteCodeForDisplay(store.getMarketplace());
            names.add(site.isEmpty() ? store.getName() : store.getName() + "（" + site + "）");
        }
        if (names.size() <= 3) return String.join("、", names);
        return String.join("、", names.subList(0, 3)) + " 等 " + names.size() + " 个店铺";
    }

    /** 卖家精灵已审核研究工具的静态规模估计（仅展示）。 */
    public static int sellerSpriteReadOnlyEstimate() { return 9; }

    /** 读取四套数据源状态（不连接远端、不暴露 token）。 */
    public static List<ChatDataSourceStatus> inspectChatDataSources(
            ChatTypes.ChatScope scope, CredentialInspector inspector, Clock clock) {
        CredentialSet set = inspector.inspect(scope);
        Instant now = clock.instant();
        AmazonCredential amz = activeCredential(set.getAmazonCreds(),
                AmazonCredential::getStatus, AmazonCredential::getExpiresAt, now);
        SellfoxCredential sf = activeCredential(set.getSellfoxCreds(),
                SellfoxCredential::getStatus, SellfoxCredential::getExpiresAt, now);
        SellerSpriteSettings sellerSprite = set.getSellerSprite();
        XiyouSettings xiyou = set.getXiyou();
        boolean sfConfigured = sellerSprite != null && sellerSprite.isEnabled()
                && sellerSprite.getApiKey() != null && !sellerSprite.getApiKey().trim().isEmpty()
                && sellerSprite.getServerUrl() != null && sellerSprite.getServerUrl().startsWith("http");
        boolean xyConfigured = xiyou != null && xiyou.isEnabled()
                && xiyou.getMcpToken() != null && !xiyou.getMcpToken().trim().isEmpty();

        List<ChatDataSourceStatus> sources = new ArrayList<>();
        sources.add(new ChatDataSourceStatus(ChatDataProvider.AMAZON, "Amazon", amz != null,
                amz == null ? null : amz.getName(), amz == null || amz.getTools() == null ? 0 : amz.getTools().size(),
                amz == null ? "未配置只读 MCP 凭证" : amazonScopeSummary(amz.getScopes()), false));
        sources.add(new ChatDataSourceStatus(ChatDataProvider.SELLFOX, "赛狐 Sellfox", sf != null,
                sf == null ? null : sf.getName(), sf == null || sf.getTools() == null ? 0 : sf.getTools().size(),
                sf == null ? "未配置只读 MCP 凭证" : sellfoxScopeSummary(sf.getStoreIds(), set.getStores()), false));
        sources.add(new ChatDataSourceStatus(ChatDataProvider.SELLERSPRITE, "卖家精灵 SellerSprite", sfConfigured,
                "sellersprite_mcp", sfConfigured ? sellerSpriteReadOnlyEstimate() : 0,
                sfConfigured ? "外部付费数据源 · 只读工具在调用时按授权发现 · "
                        + (sellerSprite.getMarketplace() == null || sellerSprite.getMarketplace().isEmpty()
                        ? "US" : sellerSprite.getMarketplace()) + " 站点"
                        : "请先在「账号管理 → MCP 与设备凭证」启用卖家精灵", true));
        sources.add(new ChatDataSourceStatus(ChatDataProvider.XIYOU, "西柚 Xiyou", xyConfigured,
                "xiyou_mcp", xyConfigured ? XIYOU_READ_TOOLS.size() : 0,
                xyConfigured ? "外部付费数据源 · 已审核只读工具 · 调用时按授权发现"
                        : "请先在 Lili 工具配置中启用西柚 MCP", true));
        return sources;
    }

    public static StoredConfig getChatDataSourceConfig(ChatTypes.ChatScope scope, DataSourceConfigStore store) {
        return store.load(scope);
    }

    /** 预检快照：凭证状态 + 已确认启用且凭证可用的 provider + 是否可开始对话。 */
    public static ChatDataSourceSnapshot getChatDataSourceSnapshot(
            ChatTypes.ChatScope scope, CredentialInspector inspector,
            DataSourceConfigStore store, Clock clock) {
        List<ChatDataSourceStatus> sources = inspectChatDataSources(scope, inspector, clock);
        Set<String> configured = new LinkedHashSet<>();
        for (ChatDataSourceStatus s : sources) if (s.isConfigured()) configured.add(s.getProvider().wire());
        StoredConfig config = store.load(scope);
        List<ChatDataProvider> enabled = new ArrayList<>();
        if (config != null && config.getEnabledProviders() != null) {
            for (String p : config.getEnabledProviders()) {
                ChatDataProvider provider = ChatDataProvider.from(p);
                if (provider != null && configured.contains(p) && !enabled.contains(provider)) enabled.add(provider);
            }
        }
        String confirmedAt = config != null && config.getConfirmedAt() != null
                ? config.getConfirmedAt().toString() : null;
        return new ChatDataSourceSnapshot(sources, enabled, confirmedAt, !enabled.isEmpty());
    }

    /** 保存确认启用的数据源：只能启用当前确有 ACTIVE 凭证的 provider；去重、非法值忽略。 */
    public static ChatDataSourceSnapshot saveChatDataSourceConfig(
            ChatTypes.ChatScope scope, Object requested, CredentialInspector inspector,
            DataSourceConfigStore store, Clock clock) {
        List<ChatDataSourceStatus> sources = inspectChatDataSources(scope, inspector, clock);
        Set<String> configured = new LinkedHashSet<>();
        for (ChatDataSourceStatus s : sources) if (s.isConfigured()) configured.add(s.getProvider().wire());
        List<ChatDataProvider> enabled = new ArrayList<>();
        if (requested instanceof Iterable) {
            for (Object item : (Iterable<?>) requested) {
                String p = item == null ? null : String.valueOf(item);
                ChatDataProvider provider = ChatDataProvider.from(p);
                if (provider != null && configured.contains(p) && !enabled.contains(provider)) enabled.add(provider);
            }
        }
        List<String> wires = new ArrayList<>();
        for (ChatDataProvider p : enabled) wires.add(p.wire());
        Instant now = clock.instant();
        store.upsert(scope, wires, now);
        return new ChatDataSourceSnapshot(sources, enabled, now.toString(), !enabled.isEmpty());
    }

    /** 门禁：未配置或凭证不可用时抛 MCP_NOT_CONFIGURED。 */
    public static List<ChatDataProvider> requireEnabledChatDataSources(
            ChatTypes.ChatScope scope, CredentialInspector inspector,
            DataSourceConfigStore store, Clock clock) {
        ChatDataSourceSnapshot snapshot = getChatDataSourceSnapshot(scope, inspector, store, clock);
        if (!snapshot.isReady()) {
            throw new ChatDataSourceException("MCP_NOT_CONFIGURED",
                    "AI 对话尚未配置数据源。请先在对话页确认启用 Amazon / 赛狐 / 卖家精灵 / 西柚 的只读数据源。");
        }
        return snapshot.getEnabledProviders();
    }
}
