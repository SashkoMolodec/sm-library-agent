package com.sashkomusic.libraryagent.domain.service;

import com.sashkomusic.libraryagent.domain.entity.Track;
import com.sashkomusic.libraryagent.domain.model.TagChange;
import com.sashkomusic.libraryagent.domain.model.TrackTagChanges;
import com.sashkomusic.libraryagent.domain.repository.TrackRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
@Slf4j
public class TrackTagSyncService {

    private final TrackRepository trackRepository;
    private final AudioTagExtractor tagExtractor;
    private final TagChangeBatchCollector batchCollector;

    @Value("${sync.enabled:true}")
    private boolean syncEnabled;

    @Value("${sync.library-path:/Users/okravch/my/sm/lib}")
    private String libraryPath;

    public TrackTagSyncService(
            TrackRepository trackRepository,
            AudioTagExtractor tagExtractor,
            TagChangeBatchCollector batchCollector
    ) {
        this.trackRepository = trackRepository;
        this.tagExtractor = tagExtractor;
        this.batchCollector = batchCollector;
    }

    @Scheduled(fixedDelayString = "${sync.interval:300000}")
    @Transactional
    public void syncTracksFromFiles() {
        if (!syncEnabled) {
            log.debug("Track tag sync is disabled");
            return;
        }

        log.info("Starting track tag synchronization from library: {}", libraryPath);
        long startTime = System.currentTimeMillis();

        int totalTracks = 0;
        int updatedTracks = 0;
        int errorTracks = 0;

        try {
            List<Track> allTracks = trackRepository.findAll();
            totalTracks = allTracks.size();
            log.info("Found {} tracks to check", totalTracks);

            for (Track track : allTracks) {
                try {
                    if (syncTrackTags(track)) {
                        updatedTracks++;
                    }
                } catch (Exception e) {
                    errorTracks++;
                    log.error("Failed to sync track {}: {}", track.getLocalPath(), e.getMessage());
                }
            }

            long duration = System.currentTimeMillis() - startTime;
            log.info("Sync completed in {}ms. Total: {}, Updated: {}, Errors: {}",
                    duration, totalTracks, updatedTracks, errorTracks);

        } catch (Exception e) {
            log.error("Track tag synchronization failed: {}", e.getMessage(), e);
        }
    }

    @Transactional
    public boolean syncTrackTags(Track track) {
        if (track.getLocalPath() == null || track.getLocalPath().isEmpty()) {
            return false;
        }

        Path audioFile = Paths.get(track.getLocalPath());

        if (!Files.exists(audioFile)) {
            log.warn("Audio file not found: {}", audioFile);
            return false;
        }

        try {
            // Check if file was modified since last sync
            FileTime lastModified = Files.getLastModifiedTime(audioFile);
            LocalDateTime fileModifiedTime = LocalDateTime.ofInstant(
                    lastModified.toInstant(),
                    ZoneId.systemDefault()
            );

            // Skip if file hasn't been modified
            LocalDateTime lastSyncTime = getLastSyncTime(track);
            if (lastSyncTime != null && fileModifiedTime.isBefore(lastSyncTime)) {
                log.trace("Skipping unchanged file: {}", audioFile.getFileName());
                return false;
            }

            // Extract tags from file
            Map<String, String> fileTags = tagExtractor.extractAllTags(audioFile);

            if (fileTags.isEmpty()) {
                log.debug("No tags found in file: {}", audioFile.getFileName());
                return false;
            }

            // Merge tags (smart update - only changed tags) and collect changes
            TrackTagChanges trackChanges = mergeTagsAndCollectChanges(track, fileTags);

            if (trackChanges.hasChanges()) {
                trackRepository.save(track);

                batchCollector.collectChanges(trackChanges);

                log.debug("Synced {} tag changes for track: {}",
                        trackChanges.getChanges().size(), track.getTitle());
                return true;
            }

            return false;

        } catch (Exception e) {
            log.error("Failed to sync tags for {}: {}", audioFile, e.getMessage());
            return false;
        }
    }

    private TrackTagChanges mergeTagsAndCollectChanges(Track track, Map<String, String> fileTags) {
        String artistName = track.getArtists().stream()
                .findFirst()
                .map(artist -> artist.getName())
                .orElse("невідомий виконавець");

        TrackTagChanges trackChanges = new TrackTagChanges(
                track.getId(),
                track.getTitle(),
                artistName
        );

        for (Map.Entry<String, String> entry : fileTags.entrySet()) {
            String tagName = entry.getKey();
            String newValue = entry.getValue();

            String currentValue = track.getTag(tagName).orElse(null);

            if (!Objects.equals(currentValue, newValue)) {
                trackChanges.addChange(new TagChange(tagName, currentValue, newValue));
                track.setTag(tagName, newValue);

                log.trace("Updated tag {} for track {}: '{}' -> '{}'",
                        tagName, track.getTitle(), currentValue, newValue);
            }
        }

        return trackChanges;
    }

    private LocalDateTime getLastSyncTime(Track track) {
        return track.getTags().stream()
                .map(tag -> tag.getLastSyncedAt())
                .filter(Objects::nonNull)
                .max(LocalDateTime::compareTo)
                .orElse(null);
    }

    /**
     * Sync tags for a single track by file path
     * Used by file watcher for immediate sync when file changes
     */
    @Transactional
    public boolean syncTrackByPath(Path filePath) {
        try {
            Track track = trackRepository.findByLocalPath(filePath.toString()).orElse(null);

            if (track == null) {
                log.debug("No track found for path: {}", filePath);
                return false;
            }

            log.debug("File watcher triggered sync for: {}", filePath.getFileName());
            return syncTrackTags(track);

        } catch (Exception e) {
            log.error("Failed to sync track by path {}: {}", filePath, e.getMessage());
            return false;
        }
    }

    public void triggerManualSync() {
        log.info("Manual sync triggered");
        syncTracksFromFiles();
    }
}
