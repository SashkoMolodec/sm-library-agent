package com.sashkomusic.libraryagent.messaging.consumer;

import com.sashkomusic.libraryagent.infrastructure.client.navidrome.NavidromeClient;
import com.sashkomusic.libraryagent.messaging.producer.dto.LibraryProcessingCompleteDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.Paths;

@Component
@Slf4j
@RequiredArgsConstructor
public class NavidromeNotificationService {

    private final NavidromeClient navidromeClient;

    @Value("${LIBRARY_ROOT_PATH}")
    private String libraryRootPath;

    @KafkaListener(topics = "library-processing-complete")
    public void handleLibraryProcessingComplete(LibraryProcessingCompleteDto event) {
        log.debug("Received library-processing-complete event: chatId={}, masterId={}, success={}, directoryPath={}",
                event.chatId(), event.masterId(), event.success(), event.directoryPath());

        if (!event.success()) {
            log.debug("Skipping Navidrome scan - library processing was not successful");
            return;
        }

        String directoryPath = event.directoryPath();
        if (directoryPath == null || directoryPath.isEmpty()) {
            log.warn("Skipping Navidrome scan - directory path is empty");
            return;
        }

        String relativePath = extractRelativePath(directoryPath);
        if (relativePath == null || relativePath.isEmpty()) {
            log.warn("Skipping Navidrome scan - could not extract relative path from: {}", directoryPath);
            return;
        }

        log.info("Triggering Navidrome scan for newly organized album: {}", relativePath);

        navidromeClient.triggerScan(relativePath);
    }

    private String extractRelativePath(String fullPath) {
        try {
            Path full = Paths.get(fullPath).toAbsolutePath().normalize();
            Path root = Paths.get(libraryRootPath).toAbsolutePath().normalize();

            if (!full.startsWith(root)) {
                log.warn("Directory path {} is not under library root {}", fullPath, libraryRootPath);
                return null;
            }

            Path relative = root.relativize(full);
            return relative.toString();

        } catch (Exception e) {
            log.error("Failed to extract relative path from {}: {}", fullPath, e.getMessage(), e);
            return null;
        }
    }
}
