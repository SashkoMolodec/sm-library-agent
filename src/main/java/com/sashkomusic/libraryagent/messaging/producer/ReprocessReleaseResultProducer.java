package com.sashkomusic.libraryagent.messaging.producer;

import com.sashkomusic.libraryagent.messaging.producer.dto.ReprocessReleaseResultDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class ReprocessReleaseResultProducer {

    private final KafkaTemplate<String, ReprocessReleaseResultDto> kafkaTemplate;
    private static final String TOPIC = "reprocess-release-complete";

    public void send(ReprocessReleaseResultDto message) {
        log.info("Sending reprocess result to Kafka: chatId={}, success={}, filesProcessed={}",
                message.chatId(), message.success(), message.filesProcessed());

        kafkaTemplate.send(TOPIC, message);
    }
}
