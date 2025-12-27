package com.sashkomusic.libraryagent.messaging.consumer.dto;

import com.fasterxml.jackson.annotation.JsonTypeName;

@JsonTypeName("add_comment_task")
public record AddCommentTaskDto(
        Long trackId,
        String comment,
        long chatId
) {
}
