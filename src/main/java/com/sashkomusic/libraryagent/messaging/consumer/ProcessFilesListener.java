package com.sashkomusic.libraryagent.messaging.consumer;

import com.sashkomusic.libraryagent.domain.service.LibraryProcessingService;
import com.sashkomusic.libraryagent.messaging.consumer.dto.ProcessLibraryTaskDto;
import com.sashkomusic.libraryagent.messaging.producer.LibraryProcessingResultProducer;
import com.sashkomusic.libraryagent.messaging.producer.dto.LibraryProcessingCompleteDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@Slf4j
@RequiredArgsConstructor
public class ProcessFilesListener {

    private final LibraryProcessingService processingService;
    private final LibraryProcessingResultProducer resultProducer;

    @KafkaListener(topics = "process-library-tasks")
    public void handleProcessFilesTask(ProcessLibraryTaskDto task) {
        log.info("Received library processing task: chatId={}, masterId={}, files={}",
                task.chatId(), task.metadata().masterId(), task.downloadedFiles().size());

        try {
            LibraryProcessingService.ProcessingResult result = processingService.processLibrary(task);

            LibraryProcessingCompleteDto completeDto = new LibraryProcessingCompleteDto(
                    task.chatId(),
                    task.metadata().masterId(),
                    result.directoryPath(),
                    result.processedFiles(),
                    result.success(),
                    result.message(),
                    result.errors()
            );

            resultProducer.send(completeDto);

            log.info("Library processing completed: success={}, directoryPath={}, processedFiles={}",
                    result.success(), result.directoryPath(), result.processedFiles().size());

        } catch (Exception ex) {
            log.error("Fatal error processing library task: {}", ex.getMessage(), ex);

            LibraryProcessingCompleteDto errorDto = new LibraryProcessingCompleteDto(
                    task.chatId(),
                    task.metadata().masterId(),
                    task.directoryPath(),
                    List.of(),
                    false,
                    "Fatal error: " + ex.getMessage(),
                    List.of(ex.getMessage())
            );

            resultProducer.send(errorDto);
        }
    }
}
