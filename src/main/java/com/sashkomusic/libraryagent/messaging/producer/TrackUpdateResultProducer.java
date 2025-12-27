package com.sashkomusic.libraryagent.messaging.producer;

import com.sashkomusic.libraryagent.messaging.producer.dto.TrackUpdateResultDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class TrackUpdateResultProducer {

    private final KafkaTemplate<String, TrackUpdateResultDto> kafkaTemplate;
    private static final String TOPIC = "track-update-results";

    public void send(TrackUpdateResultDto result) {
        log.info("Sending track update result: trackId={}, field={}, value={}, success={}",
                result.trackId(), result.fieldUpdated(), result.value(), result.success());
        kafkaTemplate.send(TOPIC, result);
    }
}
