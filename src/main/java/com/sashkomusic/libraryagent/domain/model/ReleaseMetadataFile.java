package com.sashkomusic.libraryagent.domain.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDateTime;

/**
 * DTO for .release-metadata.json file
 * Contains versioning and identification info for each release
 */
public record ReleaseMetadataFile(
        @JsonProperty("metadata_version")
        int metadataVersion,

        @JsonProperty("source_id")
        String sourceId,

        @JsonProperty("master_id")
        String masterId,

        @JsonProperty("source")
        SearchEngine source,

        @JsonProperty("artist")
        String artist,

        @JsonProperty("title")
        String title,

        @JsonProperty("year")
        Integer year,

        @JsonProperty("processed_at")
        LocalDateTime processedAt,

        @JsonProperty("track_count")
        int trackCount,

        @JsonProperty("label")
        String label,

        @JsonProperty("tags")
        java.util.List<String> tags,

        @JsonProperty("types")
        java.util.List<String> types
) {
    public static ReleaseMetadataFile from(ReleaseMetadata metadata, int version) {
        return new ReleaseMetadataFile(
                version,
                metadata.id(),
                metadata.masterId(),
                metadata.source(),
                metadata.artist(),
                metadata.title(),
                metadata.years() != null && !metadata.years().isEmpty()
                    ? Integer.parseInt(metadata.years().getFirst())
                    : null,
                LocalDateTime.now(),
                metadata.tracks() != null ? metadata.tracks().size() : 0,
                metadata.label() != null ? metadata.label() : "",
                metadata.tags() != null ? metadata.tags() : java.util.List.of(),
                metadata.types() != null ? metadata.types() : java.util.List.of()
        );
    }
}
