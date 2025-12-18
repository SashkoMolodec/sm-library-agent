package com.sashkomusic.libraryagent.domain.service;

import com.sashkomusic.libraryagent.config.PathMappingConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PathMappingService {

    private final PathMappingConfig pathMappingConfig;

    public String mapPath(String originalPath) {
        if (!pathMappingConfig.isEnabled() || originalPath == null) {
            return originalPath;
        }

        String source = pathMappingConfig.getSource();
        String target = pathMappingConfig.getTarget();

        if (source == null || target == null || source.isEmpty() || target.isEmpty()) {
            return originalPath;
        }

        if (originalPath.startsWith(source)) {
            return originalPath.replaceFirst(java.util.regex.Pattern.quote(source), target);
        }

        return originalPath;
    }
}
