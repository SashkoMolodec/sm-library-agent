package com.sashkomusic.libraryagent.messaging.producer.dto;

import com.fasterxml.jackson.annotation.JsonTypeName;

@JsonTypeName("rate_track_result")
public record RateTrackResultDto(
        Long trackId,
        int rating,
        boolean success,
        String message,
        long chatId
) {
}
