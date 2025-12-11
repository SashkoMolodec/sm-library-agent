package com.sashkomusic.libraryagent.domain.service;

import com.sashkomusic.libraryagent.ai.service.LibraryAiService;
import com.sashkomusic.libraryagent.domain.model.ReleaseMetadata;
import com.sashkomusic.libraryagent.domain.model.TrackMatch;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class TrackMatcher {

    private final LibraryAiService aiService;

    public Map<String, TrackMatch> batchMatch(List<Path> audioFiles, ReleaseMetadata metadata) {
        Map<String, TrackMatch> matchMap = new HashMap<>();

        Map<String, TrackMatch> tagBasedMatches = tryMatchFromExistingTags(audioFiles, metadata);
        if (!tagBasedMatches.isEmpty() && tagBasedMatches.size() == audioFiles.size()) {
            log.info("Successfully matched all {} files using existing track numbers from tags", audioFiles.size());
            return tagBasedMatches;
        }

        try {
            String tracklist = formatTracklist(metadata.tracks());

            StringBuilder filesFormatted = new StringBuilder();
            for (int i = 0; i < audioFiles.size(); i++) {
                filesFormatted.append(String.format("%d. %s\n", i + 1, audioFiles.get(i).getFileName().toString()));
            }

            log.info("Batch matching {} files with AI", audioFiles.size());
            List<TrackMatch> aiMatches = aiService.matchAllFilesToTracks(
                    metadata.artist(),
                    metadata.title(),
                    tracklist,
                    filesFormatted.toString()
            );

            log.info("AI returned {} matches", aiMatches != null ? aiMatches.size() : 0);

            if (aiMatches != null && aiMatches.size() == audioFiles.size()) {
                for (int i = 0; i < audioFiles.size(); i++) {
                    TrackMatch aiMatch = aiMatches.get(i);
                    String filename = audioFiles.get(i).getFileName().toString();

                    TrackMatch normalizedMatch = normalizeMatch(aiMatch, metadata);
                    matchMap.put(filename, normalizedMatch);

                    log.debug("Batch matched {}: track {} - {} by {}", filename,
                            normalizedMatch.trackNumber(), normalizedMatch.trackTitle(), normalizedMatch.artist());
                }
            } else {
                log.warn("AI batch match failed: expected {} matches, got {}. Will use fallback for all files.",
                        audioFiles.size(), aiMatches != null ? aiMatches.size() : 0);
            }

        } catch (Exception ex) {
            log.error("Batch AI matching failed: {}", ex.getMessage(), ex);
        }

        return matchMap;
    }

    private Map<String, TrackMatch> tryMatchFromExistingTags(List<Path> audioFiles, ReleaseMetadata metadata) {
        Map<String, TrackMatch> matchMap = new HashMap<>();

        if (metadata.tracks() == null || metadata.tracks().isEmpty()) {
            return matchMap;
        }

        int successfulMatches = 0;
        for (Path file : audioFiles) {
            try {
                AudioFile audioFile = AudioFileIO.read(file.toFile());
                Tag tag = audioFile.getTag();
                if (tag == null) {
                    continue;
                }

                String trackNumberStr = tag.getFirst(FieldKey.TRACK);
                if (trackNumberStr == null || trackNumberStr.isEmpty()) {
                    continue;
                }

                // Parse track number (handle formats like "1", "01", "1/12", etc.)
                int trackNumber = parseTrackNumber(trackNumberStr);
                if (trackNumber <= 0 || trackNumber > metadata.tracks().size()) {
                    log.debug("Track number {} from {} is out of range (1-{}), skipping",
                            trackNumber, file.getFileName(), metadata.tracks().size());
                    continue;
                }

                // Create match using track number from tag
                var trackMetadata = metadata.tracks().get(trackNumber - 1);
                TrackMatch match = new TrackMatch(trackNumber, trackMetadata.artist(), trackMetadata.title());
                matchMap.put(file.getFileName().toString(), match);
                successfulMatches++;

                log.debug("Matched {} using existing track number {} from tags", file.getFileName(), trackNumber);

            } catch (Exception ex) {
                log.debug("Could not read track number from {}: {}", file.getFileName(), ex.getMessage());
            }
        }

        if (successfulMatches > 0 && successfulMatches == audioFiles.size()) {
            log.info("All {} files have valid track numbers in tags, using them instead of AI", successfulMatches);
        } else if (successfulMatches > 0) {
            log.info("Only {}/{} files have valid track numbers, will use AI matching", successfulMatches, audioFiles.size());
            matchMap.clear(); // Don't use partial results
        }

        return matchMap;
    }

    private int parseTrackNumber(String trackNumberStr) {
        // Handle formats like "1", "01", "1/12", "01/12"
        String[] parts = trackNumberStr.split("[/\\\\]");
        try {
            return Integer.parseInt(parts[0].trim());
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    public TrackMatch fallbackMatch(Path file, ReleaseMetadata metadata) {
        String filename = file.getFileName().toString();

        // Remove extension and clean filename for matching
        String cleanFilename = filename.replaceAll("\\.[^.]+$", "")
                .toLowerCase()
                .replaceAll("[^а-яa-z0-9\\s]", " ") // Replace non-alphanumeric with spaces
                .replaceAll("\\s+", " ") // Normalize spaces
                .trim();

        // Try to extract track number from filename first
        Integer trackNumberFromFilename = extractTrackNumber(filename, metadata);

        // Try to match filename to track metadata
        if (metadata.tracks() != null && !metadata.tracks().isEmpty()) {
            // First try: exact match with track number from filename
            if (trackNumberFromFilename != null && trackNumberFromFilename > 0 && trackNumberFromFilename <= metadata.tracks().size()) {
                var trackMetadata = metadata.tracks().get(trackNumberFromFilename - 1);
                log.info("Fallback: Using track number {} from filename", trackNumberFromFilename);
                return new TrackMatch(trackNumberFromFilename, trackMetadata.artist(), trackMetadata.title());
            }

            // Second try: find track title that matches filename (ignoring case, partial match)
            int bestMatchIndex = -1;
            int bestMatchScore = 0;

            for (int i = 0; i < metadata.tracks().size(); i++) {
                var trackMetadata = metadata.tracks().get(i);
                String trackTitle = trackMetadata.title();
                String cleanTrackTitle = trackTitle.toLowerCase()
                        .replaceAll("[^а-яa-z0-9\\s]", " ") // Replace non-alphanumeric with spaces
                        .replaceAll("\\s+", " ") // Normalize spaces
                        .trim();

                // Calculate match score (number of matching words)
                String[] filenameWords = cleanFilename.split("\\s+");
                String[] titleWords = cleanTrackTitle.split("\\s+");

                int matchScore = 0;
                for (String fileWord : filenameWords) {
                    if (fileWord.length() < 3) continue; // Skip short words
                    for (String titleWord : titleWords) {
                        if (fileWord.equals(titleWord)) {
                            matchScore++;
                        } else if (fileWord.contains(titleWord) || titleWord.contains(fileWord)) {
                            matchScore++;
                        }
                    }
                }

                if (matchScore > bestMatchScore) {
                    bestMatchScore = matchScore;
                    bestMatchIndex = i;
                }
            }

            if (bestMatchIndex >= 0 && bestMatchScore >= 2) {
                var trackMetadata = metadata.tracks().get(bestMatchIndex);
                log.info("Fallback: Found match with score {} at position {}: {}", bestMatchScore, bestMatchIndex + 1, trackMetadata.title());
                return new TrackMatch(bestMatchIndex + 1, trackMetadata.artist(), trackMetadata.title());
            }

            // Third try: use first unmatched track
            log.warn("Could not match {} to any track title, using first track", filename);
            var firstTrack = metadata.tracks().get(0);
            return new TrackMatch(1, firstTrack.artist(), firstTrack.title());
        }

        log.warn("No track metadata available for {}", filename);
        return new TrackMatch(1, metadata.artist(), "Track 1");
    }

    private TrackMatch normalizeMatch(TrackMatch aiMatch, ReleaseMetadata metadata) {
        if (metadata.tracks() == null || aiMatch.trackTitle() == null) {
            return aiMatch;
        }

        String normalizedAiTitle = aiMatch.trackTitle().trim().toLowerCase();
        for (int i = 0; i < metadata.tracks().size(); i++) {
            var trackMetadata = metadata.tracks().get(i);
            String normalizedMetadataTitle = trackMetadata.title().trim().toLowerCase();
            if (normalizedMetadataTitle.equals(normalizedAiTitle)) {
                // Use the exact title and artist from metadata
                return new TrackMatch(i + 1, trackMetadata.artist(), trackMetadata.title());
            }
        }

        return aiMatch;
    }

    private String formatTracklist(List<com.sashkomusic.libraryagent.domain.model.TrackMetadata> tracks) {
        if (tracks == null || tracks.isEmpty()) {
            return "No tracklist available";
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < tracks.size(); i++) {
            var track = tracks.get(i);
            sb.append(String.format("%d. %s - %s\n", i + 1, track.artist(), track.title()));
        }
        return sb.toString();
    }

    /**
     * Extract track number from filename, supporting both numeric and vinyl notation (A1, B2, etc.)
     */
    private Integer extractTrackNumber(String filename, ReleaseMetadata metadata) {
        // Try vinyl notation first (A1, A2, B1, B2, C1, etc.)
        if (filename.matches("^[A-Z]\\d+.*")) {
            char side = filename.charAt(0);
            String numberStr = filename.substring(1).replaceAll("[^0-9].*", "");

            try {
                int trackOnSide = Integer.parseInt(numberStr);
                int totalTracks = metadata.tracks() != null ? metadata.tracks().size() : 0;

                // Calculate absolute track number based on side
                // A = side 0, B = side 1, C = side 2, etc.
                int sideIndex = side - 'A';

                // Estimate tracks per side (assume equal distribution)
                int tracksPerSide = totalTracks > 0 ? (totalTracks + 1) / 2 : 10;

                int absoluteTrackNumber = (sideIndex * tracksPerSide) + trackOnSide;

                log.debug("Vinyl notation {}{} → track number {}", side, trackOnSide, absoluteTrackNumber);
                return absoluteTrackNumber;
            } catch (NumberFormatException ex) {
                // Fall through to numeric extraction
            }
        }

        // Try regular numeric track number
        String numberPart = filename.replaceAll("[^0-9].*", "");
        try {
            return Integer.parseInt(numberPart);
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
