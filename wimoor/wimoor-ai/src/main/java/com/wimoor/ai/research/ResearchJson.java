package com.wimoor.ai.research;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import java.io.IOException;
import java.lang.reflect.Field;

/**
 * 研究状态专用 JSON 映射器（支撑内存深拷贝与本地文件持久化）。
 * ResearchCharge 本身没有 getter，故显式注册序列化/反序列化器，保持"原始收据 + 换算"全部字段。
 */
public final class ResearchJson {

    public static final ObjectMapper MAPPER = create();

    private ResearchJson() {}

    private static ObjectMapper create() {
        SimpleModule module = new SimpleModule();
        module.addSerializer(ResearchCredits.ResearchCharge.class, new JsonSerializer<ResearchCredits.ResearchCharge>() {
            @Override
            public void serialize(ResearchCredits.ResearchCharge value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
                gen.writeStartObject();
                gen.writeNumberField("amount", ChargeAccess.amount(value));
                gen.writeStringField("unit", ChargeAccess.unit(value));
                gen.writeNumberField("cnyPerUnit", ChargeAccess.cnyPerUnit(value));
                gen.writeStringField("rateDate", ChargeAccess.rateDate(value));
                gen.writeStringField("rateSource", ChargeAccess.rateSource(value));
                gen.writeStringField("receipt", ChargeAccess.receipt(value));
                gen.writeStringField("status", ChargeAccess.status(value));
                gen.writeEndObject();
            }
        });
        module.addDeserializer(ResearchCredits.ResearchCharge.class, new JsonDeserializer<ResearchCredits.ResearchCharge>() {
            @Override
            public ResearchCredits.ResearchCharge deserialize(JsonParser p, DeserializationContext ctx) throws IOException {
                JsonNode node = p.getCodec().readTree(p);
                return new ResearchCredits.ResearchCharge(
                        node.path("amount").asDouble(),
                        node.path("unit").asText(),
                        node.path("cnyPerUnit").asDouble(),
                        node.path("rateDate").asText(),
                        node.path("rateSource").asText(),
                        node.path("receipt").asText(),
                        node.path("status").asText());
            }
        });
        return ResearchSchema.MAPPER.copy().registerModule(module);
    }

    /** Jackson 深拷贝，保证事务内的工作副本不会污染已保存状态。 */
    public static <T> T copy(T value) {
        if (value == null) return null;
        @SuppressWarnings("unchecked")
        Class<T> type = (Class<T>) value.getClass();
        T copy = MAPPER.convertValue(value, type);
        normalizeNullNodes(copy, type);
        return copy;
    }

    /** Jackson 会把显式的 null JsonNode 字段还原成 NullNode；统一回 Java null 以对齐源端 undefined。 */
    private static void normalizeNullNodes(Object target, Class<?> type) {
        if (target == null || type == null || type == Object.class) return;
        for (Field field : type.getDeclaredFields()) {
            if (!JsonNode.class.isAssignableFrom(field.getType())) continue;
            try {
                field.setAccessible(true);
                Object value = field.get(target);
                if (value instanceof JsonNode && ((JsonNode) value).isNull()) field.set(target, null);
            } catch (ReflectiveOperationException ignored) {
                // 无法访问的字段保持 Jackson 原值
            }
        }
        normalizeNullNodes(target, type.getSuperclass());
    }
}
