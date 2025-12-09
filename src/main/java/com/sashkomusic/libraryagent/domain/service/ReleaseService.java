package com.sashkomusic.libraryagent.domain.service;

import com.sashkomusic.libraryagent.domain.entity.Artist;
import com.sashkomusic.libraryagent.domain.entity.Release;
import com.sashkomusic.libraryagent.domain.entity.Tag;
import com.sashkomusic.libraryagent.domain.entity.Track;
import com.sashkomusic.libraryagent.domain.model.ReleaseFormat;
import com.sashkomusic.libraryagent.domain.model.ReleaseMetadata;
import com.sashkomusic.libraryagent.domain.model.ReleaseType;
import com.sashkomusic.libraryagent.domain.repository.ArtistRepository;
import com.sashkomusic.libraryagent.domain.repository.ReleaseRepository;
import com.sashkomusic.libraryagent.domain.repository.TagRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReleaseService {

    private final ReleaseRepository releaseRepository;
    private final ArtistRepository artistRepository;
    private final TagRepository tagRepository;

    @Transactional
    public Release saveRelease(
            ReleaseMetadata metadata,
            String directoryPath,
            String coverPath,
            List<FileOrganizer.OrganizedFile> organizedFiles
    ) {
        log.info("Saving release: {} by {} from source {}", metadata.title(), metadata.artist(), metadata.source());

        Optional<Release> existingRelease = releaseRepository.findBySourceId(metadata.id());
        if (existingRelease.isPresent()) {
            log.info("Release with sourceId {} already exists, skipping", metadata.id());
            return existingRelease.get();
        }

        Release release = new Release();
        release.setSourceId(metadata.id());
        release.setMasterId(metadata.masterId());
        release.setSource(metadata.source());
        release.setTitle(metadata.title());
        release.setDirectoryPath(directoryPath);
        release.setCoverPath(coverPath);
        release.setReleaseFormat(ReleaseFormat.DIGITAL); // Default

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

                track.addArtist(artist);

                release.addTrack(track);
            }
        }

        Release savedRelease = releaseRepository.save(release);
        log.info("Successfully saved release with ID: {}", savedRelease.getId());

        return savedRelease;
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
