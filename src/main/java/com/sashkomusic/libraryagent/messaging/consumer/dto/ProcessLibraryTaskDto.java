package com.sashkomusic.libraryagent.messaging.consumer.dto;

import com.fasterxml.jackson.annotation.JsonTypeName;
import com.sashkomusic.libraryagent.domain.model.ReleaseMetadata;

import java.util.List;

@JsonTypeName("process_library")
public record ProcessLibraryTaskDto(
        long chatId,
        String directoryPath,
        List<String> downloadedFiles,
        ReleaseMetadata metadata
) {
}