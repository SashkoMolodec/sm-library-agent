package com.sashkomusic.libraryagent.domain.service;

import com.sashkomusic.libraryagent.config.LibraryConfig;
import com.sashkomusic.libraryagent.domain.model.ProcessedFile;
import com.sashkomusic.libraryagent.domain.model.ReleaseMetadata;
import com.sashkomusic.libraryagent.domain.model.TrackMatch;
import com.sashkomusic.libraryagent.domain.model.ValidationResult;
import com.sashkomusic.libraryagent.messaging.consumer.dto.ProcessLibraryTaskDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class LibraryProcessingService {

    private final FileValidator fileValidator;
    private final CoverArtService coverArtService;
    private final TrackMatcher trackMatcher;
    private final FileRenamer fileRenamer;
    private final AudioTagger audioTagger;
    private final ReleaseService releaseService;
    private final FileOrganizer fileOrganizer;
    private final LibraryConfig libraryConfig;

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
        List<ProcessedFile> results = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        List<Path> audioFiles = new ArrayList<>();
        for (String filePath : task.downloadedFiles()) {
            Path file = Paths.get(filePath);
            if (Files.isRegularFile(file) && isAudioFile(file)) {
                audioFiles.add(file);
            } else {
                log.debug("Skipping non-audio file: {}", file.getFileName());
            }
        }

        if (audioFiles.isEmpty()) {
            log.warn("No audio files found to process");
            return ProcessingResult.failure("No audio files found", errors);
        }

        Map<String, TrackMatch> matchMap = trackMatcher.batchMatch(audioFiles, metadata);
        log.info("Batch matched {} files using AI", matchMap.size());

        int fileIndex = 0;
        for (Path file : audioFiles) {
            fileIndex++;
            log.info("Processing file {}/{}: {}", fileIndex, audioFiles.size(), file.getFileName());

            try {
                TrackMatch match = matchMap.get(file.getFileName().toString());
                if (match == null) {
                    log.warn("No batch match found for {}, using fallback", file.getFileName());
                    match = trackMatcher.fallbackMatch(file, metadata);
                }

                ProcessedFile processedFile = processFile(file, metadata, coverArt, match);
                results.add(processedFile);
                log.info("Successfully processed {}/{}: original={}, new={}, track={} - {}",
                        fileIndex, audioFiles.size(),
                        file.getFileName(), Paths.get(processedFile.newPath()).getFileName(),
                        processedFile.trackNumber(), processedFile.trackTitle());

            } catch (Exception ex) {
                log.error("Error processing file {}/{} ({}): {}", fileIndex, audioFiles.size(),
                        file.getFileName(), ex.getMessage(), ex);
                errors.add("Failed to process " + file.getFileName() + ": " + ex.getMessage());
            }
        }

        if (results.isEmpty()) {
            return ProcessingResult.failure("No files were successfully processed", errors);
        }

        if (!errors.isEmpty()) {
            log.warn("Processing completed with {} errors out of {} files",
                    errors.size(), task.downloadedFiles().size());
        }

        String directoryPath = task.directoryPath();
        String coverPath = null;
        List<FileOrganizer.OrganizedFile> organizedFiles;

        if (libraryConfig.getOrganization().isEnabled()) {
            try {
                FileOrganizer.OrganizationResult organized = fileOrganizer.organize(
                        metadata,
                        task.directoryPath(),
                        results,
                        libraryConfig.getRootPath()
                );

                directoryPath = organized.newDirectoryPath();
                coverPath = organized.newCoverPath();
                organizedFiles = organized.files();

                log.info("Files organized into library structure: {}", directoryPath);

            } catch (Exception ex) {
                log.error("Failed to organize files, using original paths: {}", ex.getMessage(), ex);
                errors.add("Failed to organize files: " + ex.getMessage());
                organizedFiles = results.stream()
                        .map(pf -> new FileOrganizer.OrganizedFile(
                                pf.originalPath(),
                                pf.newPath(),
                                pf.trackTitle(),
                                pf.trackNumber()
                        ))
                        .toList();
            }
        } else {
            organizedFiles = results.stream()
                    .map(pf -> new FileOrganizer.OrganizedFile(
                            pf.originalPath(),
                            pf.newPath(),
                            pf.trackTitle(),
                            pf.trackNumber()
                    ))
                    .toList();
        }

        try {
            releaseService.saveRelease(metadata, directoryPath, coverPath, organizedFiles);
            log.info("Release saved to database successfully");

        } catch (Exception ex) {
            log.error("Failed to save release to database: {}", ex.getMessage(), ex);
            errors.add("Failed to save release to database: " + ex.getMessage());
        }

        log.info("Library processing completed successfully: {} files processed", results.size());
        return ProcessingResult.success(directoryPath, results, errors);
    }

    private ProcessedFile processFile(Path file, ReleaseMetadata metadata, byte[] coverArt, TrackMatch match) {
        String originalPath = file.toString();

        log.info("Matched {} to track {}: {} by {}", file.getFileName(), match.trackNumber(), match.trackTitle(), match.artist());

        Path renamedFile = fileRenamer.rename(file, match, match.artist());

        audioTagger.tagFile(renamedFile, metadata, match, coverArt);

        return new ProcessedFile(
                originalPath,
                renamedFile.toString(),
                match.trackTitle(),
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