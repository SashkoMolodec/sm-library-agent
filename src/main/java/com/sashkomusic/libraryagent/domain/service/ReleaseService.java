package com.sashkomusic.libraryagent.domain.service;

import com.sashkomusic.libraryagent.domain.entity.Artist;
import com.sashkomusic.libraryagent.domain.entity.Label;
import com.sashkomusic.libraryagent.domain.entity.Release;
import com.sashkomusic.libraryagent.domain.entity.Tag;
import com.sashkomusic.libraryagent.domain.entity.Track;
import com.sashkomusic.libraryagent.domain.model.ReleaseFormat;
import com.sashkomusic.libraryagent.domain.model.ReleaseMetadata;
import com.sashkomusic.libraryagent.domain.model.ReleaseType;
import com.sashkomusic.libraryagent.domain.repository.ArtistRepository;
import com.sashkomusic.libraryagent.domain.repository.LabelRepository;
import com.sashkomusic.libraryagent.domain.repository.ReleaseRepository;
import com.sashkomusic.libraryagent.domain.repository.TagRepository;
import com.sashkomusic.libraryagent.domain.service.utils.AudioTagExtractor;
import com.sashkomusic.libraryagent.domain.service.processFolder.FileOrganizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReleaseService {

    private final ReleaseRepository releaseRepository;
    private final ArtistRepository artistRepository;
    private final TagRepository tagRepository;
    private final LabelRepository labelRepository;
    private final AudioTagExtractor tagExtractor;

    @Transactional
    public Release saveRelease(
            ReleaseMetadata metadata,
            String directoryPath,
            String coverPath,
            List<FileOrganizer.OrganizedFile> organizedFiles,
            Integer metadataVersion
    ) {
        log.info("Saving release: {} by {} from source {}", metadata.title(), metadata.artist(), metadata.source());

        Release release = releaseRepository.findBySourceId(metadata.id())
                .orElseGet(Release::new);

        release.setSourceId(metadata.id());
        release.setMasterId(metadata.masterId());
        release.setSource(metadata.source());
        release.setTitle(metadata.title());
        release.setDirectoryPath(directoryPath);
        release.setCoverPath(coverPath);
        release.setReleaseFormat(ReleaseFormat.DIGITAL);
        release.setLastProcessed(java.time.LocalDateTime.now());
        if (metadataVersion != null) {
            release.setMetadataVersion(metadataVersion);
        }

        if (metadata.types() != null && !metadata.types().isEmpty()) {
            ReleaseType releaseType = mapToReleaseType(metadata.types().get(0));
            release.setReleaseType(releaseType);
        }

        if (metadata.types() != null && !metadata.types().isEmpty()) {
            release.setTypes(metadata.types().toArray(new String[0]));
        }

        if (metadata.years() != null && !metadata.years().isEmpty()) {
            try {
                release.setInitialRelease(Integer.parseInt(metadata.years().get(0)));
            } catch (NumberFormatException e) {
                log.warn("Could not parse year: {}", metadata.years().get(0));
            }
        }

        Artist artist = findOrCreateArtist(metadata.artist());
        release.addArtist(artist);

        if (metadata.label() != null && !metadata.label().isEmpty()) {
            Label label = findOrCreateLabel(metadata.label());
            release.setLabel(label);
        }

        if (metadata.tags() != null) {
            for (String tagName : metadata.tags()) {
                Tag tag = findOrCreateTag(tagName);
                release.addTag(tag);
            }
        }

        if (organizedFiles != null && !organizedFiles.isEmpty()) {
            List<FileOrganizer.OrganizedFile> sortedFiles = organizedFiles.stream()
                    .sorted(Comparator.comparingInt(FileOrganizer.OrganizedFile::trackNumber))
                    .toList();

            for (FileOrganizer.OrganizedFile file : sortedFiles) {
                Track track = new Track(file.trackTitle(), file.trackNumber());
                track.setLocalPath(file.newPath());

                Artist trackArtist = resolveTrackArtist(metadata, file);
                track.addArtist(trackArtist);

                extractAndStoreTags(track, file.newPath());

                release.addTrack(track);
            }
        }

        Release savedRelease = releaseRepository.save(release);
        log.info("Successfully saved release with ID: {}", savedRelease.getId());

        return savedRelease;
    }

    private void extractAndStoreTags(Track track, String filePath) {
        try {
            java.nio.file.Path audioFile = java.nio.file.Paths.get(filePath);
            java.util.Map<String, String> tags = tagExtractor.extractAllTags(audioFile);

            if (tags.isEmpty()) {
                log.debug("No tags extracted from file: {}", filePath);
                return;
            }

            for (java.util.Map.Entry<String, String> entry : tags.entrySet()) {
                track.setTag(entry.getKey(), entry.getValue());
            }

            log.debug("Extracted and stored {} tags for track: {}", tags.size(), track.getTitle());

        } catch (Exception e) {
            log.error("Failed to extract tags from {}: {}", filePath, e.getMessage());
        }
    }

    private Artist resolveTrackArtist(ReleaseMetadata metadata, FileOrganizer.OrganizedFile file) {
        String trackArtistName = file.trackArtist() != null && !file.trackArtist().isEmpty()
                ? file.trackArtist()
                : metadata.artist();
        return findOrCreateArtist(trackArtistName);
    }

    private Artist findOrCreateArtist(String name) {
        return artistRepository.findByName(name)
                .orElseGet(() -> {
                    Artist newArtist = new Artist(name);
                    return artistRepository.save(newArtist);
                });
    }

    private Tag findOrCreateTag(String name) {
        return tagRepository.findByName(name)
                .orElseGet(() -> {
                    Tag newTag = new Tag(name);
                    return tagRepository.save(newTag);
                });
    }

    private Label findOrCreateLabel(String name) {
        return labelRepository.findByName(name)
                .orElseGet(() -> {
                    Label newLabel = new Label(name);
                    return labelRepository.save(newLabel);
                });
    }

    private ReleaseType mapToReleaseType(String type) {
        if (type == null) {
            return null;
        }

        String normalized = type.toUpperCase().trim();

        if (normalized.contains("EP")) {
            return ReleaseType.EP;
        } else if (normalized.contains("SINGLE")) {
            return ReleaseType.SINGLE;
        } else if (normalized.contains("COMPILATION")) {
            return ReleaseType.COMPILATION;
        } else if (normalized.contains("ALBUM")) {
            return ReleaseType.ALBUM;
        }

        return ReleaseType.ALBUM;
    }
}
