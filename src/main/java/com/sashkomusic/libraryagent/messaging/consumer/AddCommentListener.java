package com.sashkomusic.libraryagent.messaging.consumer;

import com.sashkomusic.libraryagent.domain.service.RateTrackService;
import com.sashkomusic.libraryagent.messaging.consumer.dto.AddCommentTaskDto;
import com.sashkomusic.libraryagent.messaging.producer.TrackUpdateResultProducer;
import com.sashkomusic.libraryagent.messaging.producer.dto.TrackUpdateResultDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class AddCommentListener {

    private final RateTrackService rateTrackService;
    private final TrackUpdateResultProducer resultProducer;

    @KafkaListener(topics = "add-comment-tasks", groupId = "library-agent-group")
    public void handleAddComment(AddCommentTaskDto task) {
        log.info("Received add comment task: trackId={}, chatId={}",
                task.trackId(), task.chatId());

        try {
            RateTrackService.RateResult result = rateTrackService.addComment(task.trackId(), task.comment());

            TrackUpdateResultDto resultDto = new TrackUpdateResultDto(
                    task.trackId(),
                    "comment",
                    task.comment(),
                    result.success(),
                    result.message(),
                    task.chatId()
            );

            resultProducer.send(resultDto);

        } catch (Exception ex) {
            log.error("Error adding comment: {}", ex.getMessage(), ex);

            TrackUpdateResultDto errorDto = new TrackUpdateResultDto(
                    task.trackId(),
                    "comment",
                    task.comment(),
                    false,
                    "критична помилка: " + ex.getMessage(),
                    task.chatId()
            );

            resultProducer.send(errorDto);
        }
    }
}
