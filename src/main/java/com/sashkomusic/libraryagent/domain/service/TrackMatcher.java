package com.sashkomusic.libraryagent.domain.service;

import com.sashkomusic.libraryagent.domain.model.ReleaseMetadata;
import com.sashkomusic.libraryagent.domain.model.TrackMatch;
import lombok.extern.slf4j.Slf4j;
import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.*;
import lombok.RequiredArgsConstructor;

@Slf4j
@Service
@RequiredArgsConstructor
public class TrackMatcher {

    private final AudioTagger audioTagger;

    public Map<String, TrackMatch> match(List<Path> audioFiles, ReleaseMetadata metadata) {
        log.info("Starting matching process for {} files", audioFiles.size());

        Map<String, TrackMatch> matches = matchFromExistingTags(audioFiles, metadata);
        if (matches.size() == audioFiles.size()) {
            log.info("Strategy 1 (Tags): Successfully matched all files.");
            return matches;
        }

        log.info("Strategy 1 (Tags) failed or incomplete. Falling back to Strategy 2 (Filenames).");
        
        Map<String, TrackMatch> filenameMatches = matchFromFilenames(audioFiles, metadata);
        
        List<Path> unmatchedFiles = audioFiles.stream()
                .filter(file -> !filenameMatches.containsKey(file.getFileName().toString()))
                .toList();

        if (!unmatchedFiles.isEmpty()) {
            handleUnmatchedFiles(unmatchedFiles, filenameMatches, metadata, audioFiles.size());
        }

        return filenameMatches;
    }

    private void handleUnmatchedFiles(List<Path> unmatchedFiles, Map<String, TrackMatch> matchMap,
                                      ReleaseMetadata metadata, int totalFiles) {
        boolean completeFailure = matchMap.isEmpty() && unmatchedFiles.size() == totalFiles;

        if (completeFailure) {
            log.warn("Complete matching failure: all {} files unmatched. Starting from track 1", unmatchedFiles.size());
        } else {
            log.info("Found {} unmatched files (bonus tracks)", unmatchedFiles.size());
        }

        int startingTrackNumber = (metadata.tracks() != null && !metadata.tracks().isEmpty()) 
                ? metadata.tracks().size() + 1 : 1;

        if (!matchMap.isEmpty()) {
            startingTrackNumber = matchMap.values().stream()
                    .mapToInt(TrackMatch::trackNumber)
                    .max()
                    .orElse(startingTrackNumber - 1) + 1;
        }

        for (int i = 0; i < unmatchedFiles.size(); i++) {
            Path file = unmatchedFiles.get(i);
            String filename = file.getFileName().toString();
            int trackNumber = startingTrackNumber + i;

            String trackTitle = extractTitleFromFilename(filename);
            String trackArtist = extractArtistFromFilename(file, metadata.artist());

            matchMap.put(filename, new TrackMatch(trackNumber, trackArtist, trackTitle));
            log.info("Assigned unmatched file '{}': track {} - '{}' by '{}'",
                    filename, trackNumber, trackTitle, trackArtist);
        }
    }

    private String extractTitleFromFilename(String filename) {
        String nameWithoutExt = filename.replaceAll("\\.[^.]+$", "");
        nameWithoutExt = nameWithoutExt.replaceAll("^[A-Z]?\\d+[\\s.-]+", "");

        if (nameWithoutExt.contains(" - ")) {
            String[] parts = nameWithoutExt.split(" - ", 2);
            if (parts.length == 2) {
                return parts[1].trim();
            }
        }
        return nameWithoutExt.trim();
    }

    private String extractArtistFromFilename(Path file, String releaseArtist) {
        String filename = file.getFileName().toString();
        String nameWithoutExt = filename.replaceAll("\\.[^.]+$", "");
        nameWithoutExt = nameWithoutExt.replaceAll("^[A-Z]?\\d+[\\s.-]+", "");

        if (nameWithoutExt.contains(" - ")) {
            String[] parts = nameWithoutExt.split(" - ", 2);
            if (parts.length == 2 && !parts[0].trim().isEmpty()) {
                return parts[0].trim();
            }
        }

        try {
            var trackInfo = audioTagger.readTrackInfo(file);
            if (trackInfo != null && trackInfo.artist() != null && !trackInfo.artist().isEmpty()) {
                return trackInfo.artist();
            }
        } catch (Exception e) {
            log.debug("Could not read artist from tags: {}", e.getMessage());
        }

        return releaseArtist;
    }

    
    public Map<String, TrackMatch> tagMatch(List<Path> audioFiles, ReleaseMetadata metadata) {
        log.info("Attempting to match {} audio files to {} tracks using existing tags.",
                audioFiles.size(),
                metadata.tracks() != null ? metadata.tracks().size() : 0);

        Map<String, TrackMatch> tagBasedMatches = matchFromExistingTags(audioFiles, metadata);
        if (tagBasedMatches.size() == audioFiles.size()) {
            log.info("Successfully matched all files using existing tags.");
            return tagBasedMatches;
        }

        log.info("Tag-based matching was incomplete (matched {} / {}).", tagBasedMatches.size(), audioFiles.size());
        return Map.of();
    }

    public Map<String, TrackMatch> matchFromFilenames(List<Path> audioFiles, ReleaseMetadata metadata) {
        List<Path> sortedFiles = new ArrayList<>(audioFiles);
        sortedFiles.sort(Comparator.comparing(p -> p.getFileName().toString()));

        Map<String, TrackMatch> matchMap = new HashMap<>();

        for (Path file : sortedFiles) {
            TrackMatch match = matchSingleFile(file, metadata);
            matchMap.put(file.getFileName().toString(), match);

            log.debug("Matched '{}' -> track {} - '{}'",
                    file.getFileName(), match.trackNumber(), match.trackTitle());
        }
        return matchMap;
    }

    private TrackMatch matchSingleFile(Path file, ReleaseMetadata metadata) {
        String filename = file.getFileName().toString();
        Integer trackNumber = extractTrackNumber(filename, metadata);

        // Case 1: Track number within metadata range - use official track
        if (trackNumber != null && metadata.tracks() != null && !metadata.tracks().isEmpty()
                && trackNumber > 0 && trackNumber <= metadata.tracks().size()) {
            var trackMetadata = metadata.tracks().get(trackNumber - 1);
            log.debug("File '{}' matched to official track {}", filename, trackNumber);
            return new TrackMatch(trackNumber, trackMetadata.artist(), trackMetadata.title());
        }

        // Case 2: Track number beyond metadata - bonus track with extracted title
        if (trackNumber != null && trackNumber > 0) {
            String title = extractTitle(filename);
            log.debug("File '{}' is bonus track {} (beyond metadata)", filename, trackNumber);
            return new TrackMatch(trackNumber, metadata.artist(), title);
        }

        // Case 3: No track number in filename - assign next available number
        int nextTrackNumber = metadata.tracks() != null && !metadata.tracks().isEmpty()
                ? metadata.tracks().size() + 1
                : 1;
        String title = extractTitle(filename);
        log.warn("No track number found in '{}', assigning track {}", filename, nextTrackNumber);
        return new TrackMatch(nextTrackNumber, metadata.artist(), title);
    }

    public Map<String, TrackMatch> matchFromExistingTags(List<Path> audioFiles, ReleaseMetadata metadata) {
        if (metadata.tracks() == null || metadata.tracks().isEmpty()) {
            return Map.of();
        }

        Map<String, TrackMatch> matchMap = new HashMap<>();
        Map<Integer, String> trackNumberUsage = new HashMap<>();

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

                int trackNumber = parseTrackNumberFromTag(trackNumberStr);
                if (trackNumber <= 0 || trackNumber > metadata.tracks().size()) {
                    log.debug("Track number {} from '{}' out of range, skipping tags",
                            trackNumber, file.getFileName());
                    return Map.of();
                }

                // Detect duplicates
                if (trackNumberUsage.containsKey(trackNumber)) {
                    log.warn("Duplicate track number {} in tags ('{}' and '{}'), tags invalid",
                            trackNumber, trackNumberUsage.get(trackNumber), file.getFileName());
                    return Map.of();
                }

                trackNumberUsage.put(trackNumber, file.getFileName().toString());

                var trackMetadata = metadata.tracks().get(trackNumber - 1);
                matchMap.put(file.getFileName().toString(),
                        new TrackMatch(trackNumber, trackMetadata.artist(), trackMetadata.title()));

            } catch (Exception ex) {
                log.debug("Could not read tags from '{}': {}", file.getFileName(), ex.getMessage());
            }
        }

        // Validate completeness - all files must have tags
        if (matchMap.size() != audioFiles.size()) {
            log.debug("Only {}/{} files have valid tags, incomplete", matchMap.size(), audioFiles.size());
            return Map.of();
        }

        // Validate sequential - no gaps in track numbers
        for (int i = 1; i <= audioFiles.size(); i++) {
            if (!trackNumberUsage.containsKey(i)) {
                log.warn("Missing track number {} in tags, sequence invalid", i);
                return Map.of();
            }
        }

        return matchMap;
    }

    private Integer extractTrackNumber(String filename, ReleaseMetadata metadata) {
        // Try vinyl notation first (A1, A2, B1, B2, etc.)
        if (filename.matches("^[A-Z]\\d+.*")) {
            Integer vinylNumber = parseVinylNotation(filename, metadata);
            if (vinylNumber != null) {
                return vinylNumber;
            }
        }

        // Try regular numeric track number (01, 1, etc.)
        String numberPart = filename.replaceAll("[^0-9].*", "");
        if (!numberPart.isEmpty()) {
            try {
                return Integer.parseInt(numberPart);
            } catch (NumberFormatException ex) {
            }
        }

        return null;
    }

    private Integer parseVinylNotation(String filename, ReleaseMetadata metadata) {
        try {
            char side = filename.charAt(0);
            String numberStr = filename.substring(1).replaceAll("[^0-9].*", "");
            int trackOnSide = Integer.parseInt(numberStr);

            int totalTracks = metadata.tracks() != null ? metadata.tracks().size() : 0;
            int sideIndex = side - 'A';
            int tracksPerSide = totalTracks > 0 ? (totalTracks + 1) / 2 : 10;
            int absoluteTrackNumber = (sideIndex * tracksPerSide) + trackOnSide;

            log.debug("Vinyl notation {}{} -> track {}", side, trackOnSide, absoluteTrackNumber);
            return absoluteTrackNumber;
        } catch (Exception ex) {
            return null;
        }
    }

    private String extractTitle(String filename) {
        // Remove extension
        String nameWithoutExt = filename.replaceAll("\\.[^.]+$", "");

        // Remove track number prefix (e.g., "05. ", "A1 ", "01 - ")
        nameWithoutExt = nameWithoutExt.replaceAll("^[A-Z]?\\d+[\\s.-]+", "");

        // Remove Bandcamp artist suffix (e.g., "Die Welt, by Amygdala" -> "Die Welt")
        nameWithoutExt = nameWithoutExt.replaceAll(",\\s*by\\s+[^,]+$", "");

        // If format is "artist - title", extract only title
        if (nameWithoutExt.contains(" - ")) {
            String[] parts = nameWithoutExt.split(" - ", 2);
            if (parts.length == 2 && !parts[1].isEmpty()) {
                return parts[1].trim();
            }
        }

        return nameWithoutExt.trim();
    }

    private int parseTrackNumberFromTag(String trackNumberStr) {
        String[] parts = trackNumberStr.split("[/\\\\]");
        try {
            return Integer.parseInt(parts[0].trim());
        } catch (NumberFormatException ex) {
            return -1;
        }
    }
}