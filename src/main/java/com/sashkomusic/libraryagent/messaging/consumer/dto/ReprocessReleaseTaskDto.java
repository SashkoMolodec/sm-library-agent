package com.sashkomusic.libraryagent.messaging.consumer.dto;

import com.fasterxml.jackson.annotation.JsonTypeName;
import com.sashkomusic.libraryagent.domain.model.ReleaseMetadata;
import com.sashkomusic.libraryagent.domain.model.ReprocessOptions;

@JsonTypeName("reprocess_release")
public record ReprocessReleaseTaskDto(
        long chatId,
        String directoryPath,
        ReleaseMetadata metadata,
        int newMetadataVersion,
        ReprocessOptions options
) {
}
