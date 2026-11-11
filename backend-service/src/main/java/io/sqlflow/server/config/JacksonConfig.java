package io.sqlflow.server.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configures Jackson serialization and multi-pattern {@link Date} deserialization ({@code 01 §2.4}).
 */
@Configuration
public class JacksonConfig {

    private static final List<String> DATE_PATTERNS = List.of(
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd'T'HH:mm:ss.SSS",
            "yyyy-MM-dd'T'HH:mm:ss.SSSX",
            "yyyy-MM-dd'T'HH:mm:ss.SSSZ",
            "yyyy-MM-dd",
            "MM/dd/yyyy",
            "dd/MM/yyyy"
    );

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer sqlflowJacksonCustomizer() {
        return builder -> {
            builder.serializationInclusion(JsonInclude.Include.NON_NULL);
            builder.featuresToDisable(
                    DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                    SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
            builder.simpleDateFormat("yyyy-MM-dd HH:mm:ss");
            builder.deserializerByType(Date.class, new MultiPatternDateDeserializer());
        };
    }

    public static class MultiPatternDateDeserializer extends JsonDeserializer<Date> {
        @Override
        public Date deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            if (p.currentToken().isNumeric()) {
                return new Date(p.getLongValue());
            }
            String text = p.getText();
            if (text == null || text.isBlank()) {
                return null;
            }
            String trimmed = text.trim();
            if (trimmed.chars().allMatch(Character::isDigit)) {
                try {
                    return new Date(Long.parseLong(trimmed));
                } catch (NumberFormatException ignored) {
                    // fall through
                }
            }
            for (String pattern : DATE_PATTERNS) {
                try {
                    SimpleDateFormat fmt = new SimpleDateFormat(pattern);
                    fmt.setLenient(false);
                    return fmt.parse(trimmed);
                } catch (ParseException ignored) {
                    // try next pattern
                }
            }
            throw new IOException("Unparseable date string: '" + trimmed + "'");
        }
    }
}
