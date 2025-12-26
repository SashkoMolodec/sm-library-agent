package com.sashkomusic.libraryagent.domain.service;

import com.sashkomusic.libraryagent.domain.entity.Track;
import com.sashkomusic.libraryagent.domain.repository.TrackRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Service
@Slf4j
@RequiredArgsConstructor
public class RateTrackService {

    private final TrackRepository trackRepository;
    private final AudioTagExtractor audioTagExtractor;

    public record RateResult(boolean success, String message) {}

    @Transactional
    public RateResult rateTrack(Long trackId, int rating) {
        log.info("Rating track id={} with {} stars", trackId, rating);

        if (rating < 1 || rating > 5) {
            return new RateResult(false, "рейтинг має бути від 1 до 5");
        }

        Track track = trackRepository.findById(trackId).orElse(null);
        if (track == null) {
            return new RateResult(false, "трек не знайдено");
        }

        if (track.getLocalPath() == null || track.getLocalPath().isEmpty()) {
            return new RateResult(false, "файл треку не знайдено");
        }

        Path audioFile = Paths.get(track.getLocalPath());
        if (!Files.exists(audioFile)) {
            return new RateResult(false, "файл не існує");
        }

        // Convert to WMP format for DB
        int ratingWmp = convertStarsToWmpRating(rating);

        track.setTag("RATING", String.valueOf(ratingWmp));
        trackRepository.save(track);

        boolean fileSuccess = audioTagExtractor.writeRating(audioFile, rating);
        if (!fileSuccess) {
            return new RateResult(false, "помилка запису в файл");
        }

        log.info("Successfully rated track: id={}, rating={}", trackId, rating);
        return new RateResult(true, "успішно оцінено");
    }

    private int convertStarsToWmpRating(int stars) {
        return switch (stars) {
            case 1 -> 51;
            case 2 -> 102;
            case 3 -> 153;
            case 4 -> 204;
            case 5 -> 255;
            default -> 0;
        };
    }
}
