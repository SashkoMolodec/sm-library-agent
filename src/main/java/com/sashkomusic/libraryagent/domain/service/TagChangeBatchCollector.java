package com.sashkomusic.libraryagent.domain.service;

import com.sashkomusic.libraryagent.domain.model.TrackTagChanges;
import com.sashkomusic.libraryagent.messaging.producer.dto.TagChangesNotificationDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

@Service
@Slf4j
@RequiredArgsConstructor
public class TagChangeBatchCollector {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    private static final String TOPIC = "tag-changes";

    private final ConcurrentHashMap<Long, TrackTagChanges> pendingChanges = new ConcurrentHashMap<>();
    private final ReentrantLock lock = new ReentrantLock();

    public void collectChanges(TrackTagChanges trackChanges) {

        if (!trackChanges.hasChanges()) {
            return;
        }

        lock.lock();
        try {
            pendingChanges.merge(
                    trackChanges.getTrackId(),
                    trackChanges,
                    (existing, newChanges) -> {
                        // Add new changes to existing track
                        newChanges.getChanges().forEach(existing::addChange);
                        return existing;
                    }
            );

            log.trace("Collected {} changes for track: {}",
                    trackChanges.getChanges().size(), trackChanges.getTrackTitle());

        } finally {
            lock.unlock();
        }
    }

    @Scheduled(fixedDelayString = "${tag-changes.batch.interval:60000}")
    public void sendBatchNotification() {

        lock.lock();
        List<TrackTagChanges> changesToSend;
        try {
            if (pendingChanges.isEmpty()) {
                return;
            }

            changesToSend = new ArrayList<>(pendingChanges.values());
            pendingChanges.clear();

        } finally {
            lock.unlock();
        }

        try {
            TagChangesNotificationDto notification = TagChangesNotificationDto.create(changesToSend);

            log.info("Sending tag changes notification: {} tracks, {} total changes",
                    notification.tracks().size(), notification.totalChanges());

            kafkaTemplate.send(TOPIC, notification);

            log.debug("Tag changes notification sent successfully");

        } catch (Exception e) {
            log.error("Failed to send tag changes notification: {}", e.getMessage(), e);
        }
    }

    public int getPendingChangesCount() {
        return pendingChanges.size();
    }
}
