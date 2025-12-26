package com.sashkomusic.libraryagent.messaging.consumer;

import com.sashkomusic.libraryagent.domain.service.RateTrackService;
import com.sashkomusic.libraryagent.messaging.consumer.dto.RateTrackTaskDto;
import com.sashkomusic.libraryagent.messaging.producer.RateTrackResultProducer;
import com.sashkomusic.libraryagent.messaging.producer.dto.RateTrackResultDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class RateTrackListener {

    private final RateTrackService rateTrackService;
    private final RateTrackResultProducer resultProducer;

    @KafkaListener(topics = "rate-track-tasks", groupId = "library-agent-group")
    public void handleRateTrack(RateTrackTaskDto task) {
        log.info("Received rate track task: trackId={}, rating={}, chatId={}",
                task.trackId(), task.rating(), task.chatId());

        try {
            RateTrackService.RateResult result = rateTrackService.rateTrack(task.trackId(), task.rating());

            RateTrackResultDto resultDto = new RateTrackResultDto(
                    task.trackId(),
                    task.rating(),
                    result.success(),
                    result.message(),
                    task.chatId()
            );

            resultProducer.send(resultDto);

        } catch (Exception ex) {
            log.error("Error rating track: {}", ex.getMessage(), ex);

            RateTrackResultDto errorDto = new RateTrackResultDto(
                    task.trackId(),
                    task.rating(),
                    false,
                    "критична помилка: " + ex.getMessage(),
                    task.chatId()
            );

            resultProducer.send(errorDto);
        }
    }
}
