package com.sashkomusic.libraryagent.messaging.producer;

import com.sashkomusic.libraryagent.messaging.producer.dto.AnalyzeTrackTaskDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class AnalyzeTrackProducer {

    private final KafkaTemplate<String, AnalyzeTrackTaskDto> kafkaTemplate;
    private static final String TOPIC = "analyze-track-tasks";

    public void sendAnalysisTask(AnalyzeTrackTaskDto task) {
        log.info("Sending track analysis task for trackId={}, path={}",
                task.trackId(), task.localPath());

        kafkaTemplate.send(TOPIC, String.valueOf(task.trackId()), task);
    }
}
