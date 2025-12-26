package com.sashkomusic.libraryagent.messaging.producer;

import com.sashkomusic.libraryagent.messaging.producer.dto.RateTrackResultDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class RateTrackResultProducer {

    private final KafkaTemplate<String, RateTrackResultDto> kafkaTemplate;
    private static final String TOPIC = "rate-track-results";

    public void send(RateTrackResultDto result) {
        log.info("Sending rate track result: trackId={}, rating={}, success={}",
                result.trackId(), result.rating(), result.success());
        kafkaTemplate.send(TOPIC, result);
    }
}
