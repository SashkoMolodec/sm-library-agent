package com.sashkomusic.libraryagent.messaging.consumer;

import com.sashkomusic.libraryagent.domain.service.RateTrackService;
import com.sashkomusic.libraryagent.messaging.consumer.dto.SetEnergyTaskDto;
import com.sashkomusic.libraryagent.messaging.producer.TrackUpdateResultProducer;
import com.sashkomusic.libraryagent.messaging.producer.dto.TrackUpdateResultDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class SetEnergyListener {

    private final RateTrackService rateTrackService;
    private final TrackUpdateResultProducer resultProducer;

    @KafkaListener(topics = "set-energy-tasks", groupId = "library-agent-group")
    public void handleSetEnergy(SetEnergyTaskDto task) {
        log.info("Received set energy task: trackId={}, energy={}, chatId={}",
                task.trackId(), task.energy(), task.chatId());

        try {
            RateTrackService.RateResult result = rateTrackService.setEnergy(task.trackId(), task.energy());

            TrackUpdateResultDto resultDto = new TrackUpdateResultDto(
                    task.trackId(),
                    "energy",
                    task.energy(),
                    result.success(),
                    result.message(),
                    task.chatId()
            );

            resultProducer.send(resultDto);

        } catch (Exception ex) {
            log.error("Error setting energy: {}", ex.getMessage(), ex);

            TrackUpdateResultDto errorDto = new TrackUpdateResultDto(
                    task.trackId(),
                    "energy",
                    task.energy(),
                    false,
                    "критична помилка: " + ex.getMessage(),
                    task.chatId()
            );

            resultProducer.send(errorDto);
        }
    }
}
