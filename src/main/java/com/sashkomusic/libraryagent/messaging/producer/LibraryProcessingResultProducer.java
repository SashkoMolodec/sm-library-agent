package com.sashkomusic.libraryagent.messaging.producer;

import com.sashkomusic.libraryagent.messaging.producer.dto.LibraryProcessingCompleteDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class LibraryProcessingResultProducer {

    private final KafkaTemplate<String, LibraryProcessingCompleteDto> kafkaTemplate;
    private static final String TOPIC = "library-processing-complete";

    public void send(LibraryProcessingCompleteDto message) {
        log.info("Sending library processing result to Kafka: chatId={}, success={}",
                message.chatId(), message.success());

        kafkaTemplate.send(TOPIC, message);
    }
}