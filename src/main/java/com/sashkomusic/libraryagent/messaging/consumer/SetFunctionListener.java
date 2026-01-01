package com.sashkomusic.libraryagent.messaging.consumer;

import com.sashkomusic.libraryagent.domain.service.tag.RateTrackService;
import com.sashkomusic.libraryagent.messaging.consumer.dto.SetFunctionTaskDto;
import com.sashkomusic.libraryagent.messaging.producer.TrackUpdateResultProducer;
import com.sashkomusic.libraryagent.messaging.producer.dto.TrackUpdateResultDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class SetFunctionListener {

    private final RateTrackService rateTrackService;
    private final TrackUpdateResultProducer resultProducer;

    @KafkaListener(topics = "set-function-tasks", groupId = "library-agent-group")
    public void handleSetFunction(SetFunctionTaskDto task) {
        log.info("Received set function task: trackId={}, function={}, chatId={}",
                task.trackId(), task.function(), task.chatId());

        try {
            RateTrackService.RateResult result = rateTrackService.setFunction(task.trackId(), task.function());

            TrackUpdateResultDto resultDto = new TrackUpdateResultDto(
                    task.trackId(),
                    "function",
                    task.function(),
                    result.success(),
                    result.message(),
                    task.chatId()
            );

            resultProducer.send(resultDto);

        } catch (Exception ex) {
            log.error("Error setting function: {}", ex.getMessage(), ex);

            TrackUpdateResultDto errorDto = new TrackUpdateResultDto(
                    task.trackId(),
                    "function",
                    task.function(),
                    false,
                    "критична помилка: " + ex.getMessage(),
                    task.chatId()
            );

            resultProducer.send(errorDto);
        }
    }
}
