package com.sashkomusic.libraryagent.domain.service;

import com.sashkomusic.libraryagent.config.LibraryConfig;
import com.sashkomusic.libraryagent.domain.model.*;
import com.sashkomusic.libraryagent.messaging.consumer.dto.ProcessLibraryTaskDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class LibraryProcessingService {

    @Value("${processing.version:1}")
    private int processingVersion;

    private final FileValidator fileValidator;
    private final CoverArtService coverArtService;
    private final TrackMatcher trackMatcher;
    private final FileRenamer fileRenamer;
    private final AudioTagger audioTagger;
    private final ReleaseService releaseService;
    private final FileOrganizer fileOrganizer;
    private final LibraryConfig libraryConfig;
    private final ReleaseMetadataWriter metadataWriter;

    public ProcessingResult processLibrary(ProcessLibraryTaskDto task) {
        log.info("Starting library processing for chatId={}, directory={}",
                task.chatId(), task.directoryPath());

        ReleaseMetadata metadata = task.metadata();
        log.info("Metadata: artist='{}', title='{}', trackTitles={}",
                metadata.artist(), metadata.title(),
                metadata.trackTitles() != null ? metadata.trackTitles().size() + " tracks" : "NULL");

        ValidationResult validation = fileValidator.validate(task);
        if (!validation.isValid()) {
            log.error("Validation failed: {}", validation.getErrorMessage());
            return ProcessingResult.failure(validation.getErrorMessage(), List.of());
        }

        byte[] coverArt = coverArtService.getCoverArt(metadata, task.directoryPath());
        return processFiles(task, metadata, coverArt);
    }

    private ProcessingResult processFiles(ProcessLibraryTaskDto task, ReleaseMetadata metadata, byte[] coverArt) {
        List<String> errors = new ArrayList<>();

        List<Path> audioFiles = collectAudioFiles(task.downloadedFiles());
        if (audioFiles.isEmpty()) {
            return ProcessingResult.failure("No audio files found", errors);
        }

        Map<String, TrackMatch> matchMap = matchFilesToTracks(audioFiles, metadata);
        List<ProcessedFile> processedFiles = createProcessedFiles(audioFiles, matchMap, errors);

        if (processedFiles.isEmpty()) {
            return ProcessingResult.failure("No files were successfully processed", errors);
        }

        OrganizationContext orgContext = organizeIntoLibrary(processedFiles, metadata, task, coverArt, errors);
        saveToDatabase(metadata, orgContext.directoryPath, orgContext.coverPath, orgContext.organizedFiles, errors);

        log.info("Library processing completed successfully: {} files processed", processedFiles.size());
        return ProcessingResult.success(orgContext.directoryPath, processedFiles, errors);
    }

    private List<Path> collectAudioFiles(List<String> filePaths) {
        List<Path> audioFiles = new ArrayList<>();
        for (String filePath : filePaths) {
            Path file = Paths.get(filePath);
            if (Files.isRegularFile(file) && isAudioFile(file)) {
                audioFiles.add(file);
            } else {
                log.debug("Skipping non-audio file: {}", file.getFileName());
            }
        }
        log.info("Collected {} audio files", audioFiles.size());
        return audioFiles;
    }

    private Map<String, TrackMatch> matchFilesToTracks(List<Path> audioFiles, ReleaseMetadata metadata) {
        return tryTagBasedMatching(audioFiles, metadata)
                .orElseGet(() -> tryFilenameBasedMatching(audioFiles, metadata));
    }

    private Optional<Map<String, TrackMatch>> tryTagBasedMatching(List<Path> audioFiles, ReleaseMetadata metadata) {
        Map<String, TrackMatch> matchMap = trackMatcher.tagMatch(audioFiles, metadata);
        if (matchMap.size() == audioFiles.size()) {
            log.info("Strategy 1 (Tags): Successfully matched all {} files.", audioFiles.size());
            return Optional.of(matchMap);
        }
        log.info("Strategy 1 (Tags) failed to match all files ({} / {}).", matchMap.size(), audioFiles.size());
        return Optional.empty();
    }

    private Map<String, TrackMatch> tryFilenameBasedMatching(List<Path> audioFiles, ReleaseMetadata metadata) {
        log.info("Strategy 3: Falling back to filename-based matching.");

        Map<String, TrackMatch> matchMap = trackMatcher.matchFromFilenames(audioFiles, metadata);

        List<Path> unmatchedFiles = audioFiles.stream()
                .filter(file -> !matchMap.containsKey(file.getFileName().toString()))
                .toList();

        if (!unmatchedFiles.isEmpty()) {
            log.error("This should not happen: {} files still unmatched after filename matching.", unmatchedFiles.size());
            handleUnmatchedFiles(unmatchedFiles, matchMap, metadata, audioFiles.size());
        }
        return matchMap;
    }

    private void handleUnmatchedFiles(List<Path> unmatchedFiles, Map<String, TrackMatch> matchMap,
                                      ReleaseMetadata metadata, int totalFiles) {
        boolean completeFailure = matchMap.isEmpty() && unmatchedFiles.size() == totalFiles;

        if (completeFailure) {
            log.warn("Complete matching failure: all {} files unmatched. Starting from track 1", unmatchedFiles.size());
        } else {
            log.info("Found {} unmatched files (bonus tracks)", unmatchedFiles.size());
        }

        int startingTrackNumber = completeFailure ? 1 :
                (metadata.tracks() != null && !metadata.tracks().isEmpty() ? metadata.tracks().size() + 1 : 1);

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

    private List<ProcessedFile> createProcessedFiles(List<Path> audioFiles, Map<String, TrackMatch> matchMap,
                                                     List<String> errors) {
        List<ProcessedFile> results = new ArrayList<>();
        int fileIndex = 0;

        for (Path file : audioFiles) {
            fileIndex++;
            log.info("Processing file {}/{} : {}", fileIndex, audioFiles.size(), file.getFileName());

            try {
                TrackMatch match = matchMap.get(file.getFileName().toString());
                if (match == null) {
                    throw new IllegalStateException("No match found for file: " + file.getFileName());
                }

                ProcessedFile processedFile = processFile(file, match);
                results.add(processedFile);

            } catch (Exception ex) {
                log.error("Error processing file {}/{} ({}): {}", fileIndex, audioFiles.size(),
                        file.getFileName(), ex.getMessage(), ex);
                errors.add("Failed to process " + file.getFileName() + ": " + ex.getMessage());
            }
        }

        if (!errors.isEmpty()) {
            log.warn("Processing completed with {} errors out of {} files", errors.size(), audioFiles.size());
        }

        return results;
    }

    private OrganizationContext organizeIntoLibrary(List<ProcessedFile> processedFiles, ReleaseMetadata metadata,
                                                    ProcessLibraryTaskDto task, byte[] coverArt, List<String> errors) {
        String directoryPath = task.directoryPath();
        String coverPath = null;
        List<FileOrganizer.OrganizedFile> organizedFiles;

        if (!libraryConfig.getOrganization().isEnabled()) {
            organizedFiles = processedFiles.stream()
                    .map(pf -> new FileOrganizer.OrganizedFile(
                            pf.originalPath(), pf.newPath(), pf.trackTitle(), pf.trackArtist(), pf.trackNumber()))
                    .toList();
            return new OrganizationContext(directoryPath, coverPath, organizedFiles);
        }

        try {
            FileOrganizer.OrganizationResult organized = fileOrganizer.organize(
                    metadata, task.directoryPath(), processedFiles, libraryConfig.getRootPath());

            directoryPath = organized.newDirectoryPath();
            coverPath = organized.newCoverPath();
            organizedFiles = organized.files();

            log.info("Files organized into library structure: {}", directoryPath);

            organizedFiles = renameAndTagInLibrary(organizedFiles, metadata, coverArt, errors);

        } catch (Exception ex) {
            log.error("Failed to organize files: {}", ex.getMessage(), ex);
            errors.add("Failed to organize files: " + ex.getMessage());
            organizedFiles = processedFiles.stream()
                    .map(pf -> new FileOrganizer.OrganizedFile(
                            pf.originalPath(), pf.newPath(), pf.trackTitle(), pf.trackArtist(), pf.trackNumber()))
                    .toList();
        }

        return new OrganizationContext(directoryPath, coverPath, organizedFiles);
    }

    private List<FileOrganizer.OrganizedFile> renameAndTagInLibrary(
            List<FileOrganizer.OrganizedFile> organizedFiles, ReleaseMetadata metadata,
            byte[] coverArt, List<String> errors) {

        List<FileOrganizer.OrganizedFile> finalFiles = new ArrayList<>();

        for (FileOrganizer.OrganizedFile orgFile : organizedFiles) {
            try {
                Path copiedFile = Paths.get(orgFile.newPath());
                TrackMatch match = new TrackMatch(orgFile.trackNumber(), orgFile.trackArtist(), orgFile.trackTitle());

                Path renamedFile = fileRenamer.rename(copiedFile, match, match.artist());
                log.info("Renamed in library: {} -> {}", copiedFile.getFileName(), renamedFile.getFileName());

                audioTagger.tagFile(renamedFile, metadata, match, coverArt);
                log.info("Tagged in library: {}", renamedFile.getFileName());

                finalFiles.add(new FileOrganizer.OrganizedFile(
                        orgFile.oldPath(), renamedFile.toString(), orgFile.trackTitle(),
                        orgFile.trackArtist(), orgFile.trackNumber()));

            } catch (Exception ex) {
                log.error("Failed to rename/tag {}: {}", orgFile.newPath(), ex.getMessage(), ex);
                errors.add("Failed to process " + Paths.get(orgFile.newPath()).getFileName() + ": " + ex.getMessage());
                finalFiles.add(orgFile);
            }
        }

        return finalFiles;
    }

    private void saveToDatabase(ReleaseMetadata metadata, String directoryPath, String coverPath,
                                List<FileOrganizer.OrganizedFile> organizedFiles, List<String> errors) {
        try {
            releaseService.saveRelease(metadata, directoryPath, coverPath, organizedFiles, processingVersion);
            log.info("Release saved to database successfully");
            metadataWriter.writeMetadata(directoryPath, metadata, processingVersion);

        } catch (Exception ex) {
            log.error("Failed to save release to database: {}", ex.getMessage(), ex);
            errors.add("Failed to save release to database: " + ex.getMessage());
        }
    }

    private record OrganizationContext(
            String directoryPath,
            String coverPath,
            List<FileOrganizer.OrganizedFile> organizedFiles
    ) {}

    private ProcessedFile processFile(Path file, TrackMatch match) {
        String originalPath = file.toString();
        log.info("Matched {} to track {}: {} by {}", file.getFileName(), match.trackNumber(), match.trackTitle(), match.artist());

        return new ProcessedFile(
                originalPath,
                originalPath,
                match.trackTitle(),
                match.artist(),
                match.trackNumber()
        );
    }

    private boolean isAudioFile(Path file) {
        String filename = file.getFileName().toString().toLowerCase();
        return filename.endsWith(".mp3") ||
                filename.endsWith(".flac") ||
                filename.endsWith(".m4a") ||
                filename.endsWith(".ogg") ||
                filename.endsWith(".wav") ||
                filename.endsWith(".opus") ||
                filename.endsWith(".aac");
    }

    private String extractTitleFromFilename(String filename) {
        // Remove extension
        String nameWithoutExt = filename.replaceAll("\\.[^.]+$", "");

        // Remove track number prefix (e.g., "05. ", "A1 ", "01 - ")
        nameWithoutExt = nameWithoutExt.replaceAll("^[A-Z]?\\d+[\\s.-]+", "");

        // If format is "artist - title", extract only title
        if (nameWithoutExt.contains(" - ")) {
            String[] parts = nameWithoutExt.split(" - ", 2);
            if (parts.length == 2) {
                return parts[1].trim().toLowerCase();
            }
        }

        return nameWithoutExt.trim().toLowerCase();
    }

    private String extractArtistFromFilename(Path file, String releaseArtist) {
        String filename = file.getFileName().toString();

        // Remove extension and track number
        String nameWithoutExt = filename.replaceAll("\\.[^.]+$", "");
        nameWithoutExt = nameWithoutExt.replaceAll("^[A-Z]?\\d+[\\s.-]+", "");

        // If format is "artist - title", extract artist
        if (nameWithoutExt.contains(" - ")) {
            String[] parts = nameWithoutExt.split(" - ", 2);
            if (parts.length == 2 && !parts[0].trim().isEmpty()) {
                return parts[0].trim();
            }
        }

        // Try reading artist from existing file tags
        try {
            AudioTagger.TrackInfo trackInfo = audioTagger.readTrackInfo(file);
            if (trackInfo != null && trackInfo.artist() != null && !trackInfo.artist().isEmpty()) {
                return trackInfo.artist();
            }
        } catch (Exception e) {
            log.debug("Could not read artist from file tags for {}: {}", filename, e.getMessage());
        }

        // Fallback to release artist
        return releaseArtist;
    }

    public record ProcessingResult(
            boolean success,
            String message,
            String directoryPath,
            List<ProcessedFile> processedFiles,
            List<String> errors
    ) {
        public static ProcessingResult success(String directoryPath, List<ProcessedFile> files, List<String> errors) {
            String msg = String.format("Successfully processed %d files", files.size());
            if (!errors.isEmpty()) {
                msg += String.format(" (with %d errors)", errors.size());
            }
            return new ProcessingResult(true, msg, directoryPath, files, errors);
        }

        public static ProcessingResult failure(String message, List<String> errors) {
            return new ProcessingResult(false, message, "", List.of(), errors);
        }
    }
}