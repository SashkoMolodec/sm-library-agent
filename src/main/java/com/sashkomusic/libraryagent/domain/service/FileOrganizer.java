package com.sashkomusic.libraryagent.domain.service;

import com.sashkomusic.libraryagent.domain.model.ProcessedFile;
import com.sashkomusic.libraryagent.domain.model.ReleaseMetadata;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
public class FileOrganizer {

    private static final int MAX_FOLDER_NAME_LENGTH = 200;

    public OrganizationResult organize(
            ReleaseMetadata metadata,
            String currentDirectory,
            List<ProcessedFile> processedFiles,
            String libraryRootPath
    ) {
        try {
            // Determine folder structure
            String artistFolder = sanitizeFolderName(metadata.artist());
            String format = detectAudioFormat(processedFiles);
            Integer year = extractYear(metadata);
            String albumFolder = buildAlbumFolderName(metadata.title(), year, format);

            // Create target directory path
            Path libraryRoot = Paths.get(libraryRootPath);
            Path targetDir = libraryRoot
                    .resolve(artistFolder)
                    .resolve(albumFolder);

            // Create directories if they don't exist
            Files.createDirectories(targetDir);
            log.info("Created/verified directory: {}", targetDir);

            // Move audio files
            List<OrganizedFile> organizedFiles = new ArrayList<>();
            for (ProcessedFile file : processedFiles) {
                Path sourcePath = Paths.get(file.newPath());
                Path targetPath = targetDir.resolve(sourcePath.getFileName());

                Files.move(sourcePath, targetPath, StandardCopyOption.ATOMIC_MOVE);
                log.info("Moved file: {} -> {}", sourcePath.getFileName(), targetPath);

                organizedFiles.add(new OrganizedFile(
                        file.newPath(),
                        targetPath.toString(),
                        file.trackTitle(),
                        file.trackArtist(),
                        file.trackNumber()
                ));
            }

            // Move cover art if exists
            String coverPath = null;
            Path sourceCover = Paths.get(currentDirectory).resolve("cover.jpg");
            if (Files.exists(sourceCover)) {
                Path targetCover = targetDir.resolve("cover.jpg");
                Files.move(sourceCover, targetCover, StandardCopyOption.REPLACE_EXISTING);
                coverPath = targetCover.toString();
                log.info("Moved cover art to: {}", targetCover);
            }

            return new OrganizationResult(
                    targetDir.toString(),
                    coverPath,
                    organizedFiles
            );

        } catch (IOException e) {
            log.error("Failed to organize files: {}", e.getMessage(), e);
            throw new FileOrganizationException("Failed to organize files into library structure", e);
        }
    }

    private String sanitizeFolderName(String name) {
        if (name == null) {
            return "unknown";
        }

        // Remove illegal filesystem characters
        String sanitized = name.replaceAll("[/\\\\:*?\"<>|]", "").trim();

        // Limit length
        if (sanitized.length() > MAX_FOLDER_NAME_LENGTH) {
            sanitized = sanitized.substring(0, MAX_FOLDER_NAME_LENGTH).trim();
        }

        return sanitized.isEmpty() ? "unknown" : sanitized.toLowerCase();
    }

    private String detectAudioFormat(List<ProcessedFile> files) {
        Set<String> extensions = files.stream()
                .map(ProcessedFile::newPath)
                .map(path -> {
                    int lastDot = path.lastIndexOf('.');
                    return lastDot > 0 ? path.substring(lastDot + 1).toLowerCase() : "";
                })
                .collect(Collectors.toSet());

        if (extensions.size() == 1) {
            String ext = extensions.iterator().next();
            return switch (ext) {
                case "flac" -> "flac";
                case "mp3" -> "mp3";
                case "m4a" -> "m4a";
                case "ogg" -> "ogg";
                case "wav" -> "wav";
                case "opus" -> "opus";
                case "aac" -> "aac";
                default -> "digital";
            };
        }

        return "mixed";
    }

    private Integer extractYear(ReleaseMetadata metadata) {
        if (metadata.years() == null || metadata.years().isEmpty()) {
            return null;
        }

        try {
            return Integer.parseInt(metadata.years().get(0));
        } catch (NumberFormatException e) {
            log.warn("Could not parse year: {}", metadata.years().get(0));
            return null;
        }
    }

    private String buildAlbumFolderName(String title, Integer year, String format) {
        String sanitizedTitle = sanitizeFolderName(title);

        if (year != null) {
            return String.format("%s (%d) [%s]", sanitizedTitle, year, format).toLowerCase();
        } else {
            return String.format("%s [%s]", sanitizedTitle, format).toLowerCase();
        }
    }

    public record OrganizationResult(
            String newDirectoryPath,
            String newCoverPath,
            List<OrganizedFile> files
    ) {}

    public record OrganizedFile(
            String oldPath,
            String newPath,
            String trackTitle,
            String trackArtist,
            int trackNumber
    ) {}

    public static class FileOrganizationException extends RuntimeException {
        public FileOrganizationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
