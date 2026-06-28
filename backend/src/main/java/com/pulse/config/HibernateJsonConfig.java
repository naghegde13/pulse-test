package com.pulse.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.util.Map;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.type.format.jackson.JacksonJsonFormatMapper;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Pins Hibernate's JSON format mapper to a clean Jackson {@link ObjectMapper}.
 *
 * <p>Spark is on the classpath (cobol preview), which transitively provides
 * {@code jackson-module-scala}. Hibernate's default {@code JacksonJsonFormatMapper}
 * auto-discovers that module via the ServiceLoader and deserializes {@code jsonb}
 * columns into Scala immutable collections. The clean Spring MVC ObjectMapper then
 * bean-serializes those collections as {@code {"empty":true,"traversableAgain":true}},
 * corrupting every JSON array/object exposed through a REST DTO.
 *
 * <p>Supplying an explicit no-Scala ObjectMapper forces {@code jsonb} to deserialize
 * into plain {@code java.util} collections, so arrays round-trip as arrays.
 */
@Configuration
public class HibernateJsonConfig {

    @Bean
    public HibernatePropertiesCustomizer jsonFormatMapperCustomizer() {
        ObjectMapper cleanMapper = new ObjectMapper();
        cleanMapper.registerModule(new JavaTimeModule());
        JacksonJsonFormatMapper formatMapper = new JacksonJsonFormatMapper(cleanMapper);
        return (Map<String, Object> properties) ->
                properties.put(AvailableSettings.JSON_FORMAT_MAPPER, formatMapper);
    }
}
