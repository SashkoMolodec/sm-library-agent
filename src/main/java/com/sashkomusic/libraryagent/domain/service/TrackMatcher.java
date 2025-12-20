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

    public Map<String, TrackMatch> match(List<Path> audioFiles, ReleaseMetadata metadata) {
        log.info("Starting matching process for {} files", audioFiles.size());

        List<Path> sortedFiles = audioFiles.stream()
                .sorted(Comparator.comparing(Path::toString))
                .toList();

        Map<String, TrackMatch> matches = matchFromExistingTags(sortedFiles, metadata);
        if (matches.size() == sortedFiles.size()) {
            return matches;
        }

        if (metadata.tracks() != null && metadata.tracks().size() == sortedFiles.size()) {
            log.info("File count matches track count. Applying sequential matching based on folder structure.");
            return matchSequentially(sortedFiles, metadata);
        }

        return matchFromFilenames(audioFiles, metadata);
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

    private Map<String, TrackMatch> matchSequentially(List<Path> sortedFiles, ReleaseMetadata metadata) {
        Map<String, TrackMatch> matches = new HashMap<>();
        for (int i = 0; i < sortedFiles.size(); i++) {
            Path file = sortedFiles.get(i);
            var trackMetadata = metadata.tracks().get(i);
            matches.put(file.getFileName().toString(),
                    new TrackMatch(i + 1, trackMetadata.artist(), trackMetadata.title()));
        }
        return matches;
    }

    public Map<String, TrackMatch> matchFromExistingTags(List<Path> audioFiles, ReleaseMetadata metadata) {
        if (metadata.tracks() == null || metadata.tracks().isEmpty()) {
            return Map.of();
        }

        Map<String, TrackMatch> matchMap = new HashMap<>();
        Set<Integer> usedTrackNumbers = new HashSet<>();

        for (Path file : audioFiles) {
            try {
                String currentDir = file.getParent().getFileName().toString();

                AudioFile audioFile = AudioFileIO.read(file.toFile());
                Tag tag = audioFile.getTag();
                if (tag == null) return Map.of();

                String trackStr = tag.getFirst(FieldKey.TRACK);
                int trackNum = parseTrackNumberFromTag(trackStr);

                if (usedTrackNumbers.contains(trackNum)) {
                    log.warn("Duplicate track number {} detected in tags (folder: {}). Tag matching is unreliable.", trackNum, currentDir);
                    return Map.of();
                }

                if (trackNum <= 0 || trackNum > metadata.tracks().size()) return Map.of();

                usedTrackNumbers.add(trackNum);
                var trackMetadata = metadata.tracks().get(trackNum - 1);
                matchMap.put(file.getFileName().toString(),
                        new TrackMatch(trackNum, trackMetadata.artist(), trackMetadata.title()));

            } catch (Exception ex) {
                return Map.of();
            }
        }

        return matchMap.size() == audioFiles.size() ? matchMap : Map.of();
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