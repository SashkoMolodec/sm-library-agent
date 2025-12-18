package com.sashkomusic.libraryagent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "path.mapping")
public class PathMappingConfig {
    private boolean enabled;
    private String source;
    private String target;
}
