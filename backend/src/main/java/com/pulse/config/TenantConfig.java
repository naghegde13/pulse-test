package com.pulse.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
@ConfigurationProperties(prefix = "pulse.tenants")
public class TenantConfig {

    private List<TenantDefinition> definitions = List.of();

    public List<TenantDefinition> getDefinitions() { return definitions; }
    public void setDefinitions(List<TenantDefinition> definitions) { this.definitions = definitions; }

    public static class TenantDefinition {
        private String id;
        private String name;
        private String slug;
        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getSlug() { return slug; }
        public void setSlug(String slug) { this.slug = slug; }
    }
}
