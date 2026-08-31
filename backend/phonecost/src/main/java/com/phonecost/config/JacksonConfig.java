package com.phonecost.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.math.BigDecimal;

@Configuration
public class JacksonConfig {

    @Bean
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        mapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        mapper.registerModule(new JavaTimeModule());

        // V72: 数据库费用列扩展为 DECIMAL(22,15) 后，读出的 BigDecimal 会带 15 位尾零
        // （如 184.680000000000000）。序列化时统一去掉尾零，保持 JSON 输出与前端显示干净。
        SimpleModule bigDecimalModule = new SimpleModule();
        bigDecimalModule.addSerializer(BigDecimal.class, new JsonSerializer<>() {
            @Override
            public void serialize(BigDecimal value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
                if (value == null) {
                    gen.writeNull();
                    return;
                }
                BigDecimal normalized = value.stripTrailingZeros();
                // stripTrailingZeros 可能把整数值变成科学计数法（如 1E+5），还原为普通数值
                if (normalized.scale() < 0) {
                    normalized = normalized.setScale(0);
                }
                gen.writeNumber(normalized);
            }
        });
        mapper.registerModule(bigDecimalModule);

        return mapper;
    }
}
