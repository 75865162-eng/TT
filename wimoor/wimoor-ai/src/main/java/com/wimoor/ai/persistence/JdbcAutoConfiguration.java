package com.wimoor.ai.persistence;

import com.wimoor.ai.chat.Billing.PointsLedger;
import com.wimoor.ai.chat.ConversationRepository;
import com.wimoor.ai.research.LiliTransport.HttpFetcher;
import com.wimoor.ai.skill.LlmClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * 真实 IO 自动装配：仅当 {@code ai.persistence=jdbc} 时生效。
 * 默认（memory）不加载此类，wimoor-ai 保持纯内存低内存启动，200 测试与冒烟不受影响。
 */
@Configuration
@ConditionalOnProperty(name = "ai.persistence", havingValue = "jdbc")
public class JdbcAutoConfiguration {

    @Bean
    public DataSource aiDataSource(Environment env) {
        String url = env.getProperty("ai.datasource.url", "jdbc:mysql://127.0.0.1:13306/db_ai?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai");
        String user = env.getProperty("ai.datasource.username", "root");
        String pass = env.getProperty("ai.datasource.password", "");
        String driver = env.getProperty("ai.datasource.driver", "com.mysql.cj.jdbc.Driver");
        return DataSourceBuilder.create().url(url).username(user).password(pass).driverClassName(driver).build();
    }

    @Bean
    public JdbcTemplate aiJdbcTemplate(DataSource aiDataSource) {
        return new JdbcTemplate(aiDataSource);
    }

    @Bean
    public ConversationRepository jdbcConversationRepository(JdbcTemplate aiJdbcTemplate) {
        return new JdbcConversationRepository(aiJdbcTemplate);
    }

    @Bean
    public com.wimoor.ai.research.ResearchStore jdbcResearchStore(JdbcTemplate aiJdbcTemplate) {
        return new JdbcResearchStore(aiJdbcTemplate);
    }

    @Bean
    public PointsLedger jdbcPointsLedger(JdbcTemplate aiJdbcTemplate, Environment env) {
        String org = env.getProperty("ai.points.defaultOrg", "system");
        String user = env.getProperty("ai.points.defaultUser", "system");
        return new JdbcPointsLedger(aiJdbcTemplate, org, user);
    }

    @Bean
    public LlmClient httpLlmClient(Environment env) {
        long timeoutMs = Long.parseLong(env.getProperty("ai.llm.timeoutMs", "60000"));
        return new HttpLlmClient(Duration.ofMillis(timeoutMs));
    }

    @Bean
    public HttpFetcher jdkHttpFetcher(Environment env) {
        String hosts = env.getProperty("ai.http.allowedHosts", "");
        Set<String> set = new HashSet<>();
        if (!hosts.isEmpty()) set.addAll(Arrays.asList(hosts.split(",")));
        long timeoutMs = Long.parseLong(env.getProperty("ai.http.timeoutMs", "30000"));
        int maxBytes = Integer.parseInt(env.getProperty("ai.http.maxBodyBytes", "8000000"));
        return new JdkHttpFetcher(Duration.ofMillis(timeoutMs), maxBytes, set);
    }
}
