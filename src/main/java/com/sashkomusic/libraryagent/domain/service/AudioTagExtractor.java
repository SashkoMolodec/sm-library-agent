package com.sashkomusic.libraryagent.domain.service;

import lombok.extern.slf4j.Slf4j;
import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;
import org.jaudiotagger.tag.id3.AbstractID3v2Frame;
import org.jaudiotagger.tag.id3.AbstractID3v2Tag;
import org.jaudiotagger.tag.id3.framebody.FrameBodyTXXX;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

@Service
@Slf4j
public class AudioTagExtractor {

    public Map<String, String> extractAllTags(Path audioFile) {
        Map<String, String> tags = new HashMap<>();

        try {
            AudioFile audio = AudioFileIO.read(audioFile.toFile());
            Tag tag = audio.getTag();

            if (tag == null) {
                log.debug("No tags found in file: {}", audioFile);
                return tags;
            }

            extractStandardTags(tag, tags);

            if (tag instanceof AbstractID3v2Tag) {
                extractCustomTags((AbstractID3v2Tag) tag, tags);
            }

            log.debug("Extracted {} tags from: {}", tags.size(), audioFile.getFileName());

        } catch (Exception e) {
            log.error("Failed to extract tags from {}: {}", audioFile, e.getMessage());
        }

        return tags;
    }

    private void extractStandardTags(Tag tag, Map<String, String> tags) {
        addTagIfPresent(tag, FieldKey.TITLE, "TIT2", tags);
        addTagIfPresent(tag, FieldKey.ARTIST, "TPE1", tags);
        addTagIfPresent(tag, FieldKey.ALBUM, "TALB", tags);
        addTagIfPresent(tag, FieldKey.ALBUM_ARTIST, "TPE2", tags);
        addTagIfPresent(tag, FieldKey.YEAR, "TDRC", tags);
        addTagIfPresent(tag, FieldKey.GENRE, "TCON", tags);
        addTagIfPresent(tag, FieldKey.COMMENT, "COMM", tags);
        addTagIfPresent(tag, FieldKey.COMPOSER, "TCOM", tags);
        addTagIfPresent(tag, FieldKey.GROUPING, "GRP1", tags);

        addTagIfPresent(tag, FieldKey.BPM, "TBPM", tags);
        addTagIfPresent(tag, FieldKey.KEY, "TKEY", tags);

        extractInitialKey(tag, tags);
        extractRating(tag, tags);
        extractLabel(tag, tags);

        addTagIfPresent(tag, FieldKey.TRACK, "TRCK", tags);
        addTagIfPresent(tag, FieldKey.DISC_NO, "TPOS", tags);
        addTagIfPresent(tag, FieldKey.ISRC, "TSRC", tags);
        addTagIfPresent(tag, FieldKey.MUSICBRAINZ_TRACK_ID, "UFID", tags);
    }

    private void extractCustomTags(AbstractID3v2Tag id3Tag, Map<String, String> tags) {
        try {
            var fields = id3Tag.getFields();
            while (fields.hasNext()) {
                var field = fields.next();

                if (field instanceof AbstractID3v2Frame) {
                    AbstractID3v2Frame frame = (AbstractID3v2Frame) field;

                    // Check if it's a TXXX frame (user-defined text)
                    if ("TXXX".equals(frame.getId()) && frame.getBody() instanceof FrameBodyTXXX) {
                        FrameBodyTXXX body = (FrameBodyTXXX) frame.getBody();
                        String description = body.getDescription();
                        String value = body.getText();

                        if (description != null && !description.isEmpty() && value != null && !value.isEmpty()) {
                            tags.put("TXXX:" + description.toUpperCase(), value);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to extract custom TXXX frames: {}", e.getMessage());
        }
    }

    private void addTagIfPresent(Tag tag, FieldKey fieldKey, String frameName, Map<String, String> tags) {
        try {
            String value = tag.getFirst(fieldKey);
            if (value != null && !value.isEmpty()) {
                tags.put(frameName, value);
            }
        } catch (Exception e) {
            log.trace("Failed to extract {}: {}", frameName, e.getMessage());
        }
    }

    /**
     * Extracts INITIALKEY tag (Traktor Pro key notation like "1m" for D minor)
     */
    private void extractInitialKey(Tag tag, Map<String, String> tags) {
        try {
            String initialKey = tag.getFirst("INITIALKEY");
            if (initialKey != null && !initialKey.isEmpty()) {
                tags.put("INITIALKEY", initialKey);
            }
        } catch (Exception e) {
            log.trace("Failed to extract INITIALKEY: {}", e.getMessage());
        }
    }

    /**
     * Extracts rating, preferring "RATING WMP" (Traktor/WMP format with values 0, 51, 102, 153, 204, 255)
     * Falls back to standard RATING field if RATING WMP is not present.
     * Used for FLAC and M4A files where both tags may exist.
     */
    private void extractRating(Tag tag, Map<String, String> tags) {
        String rating = null;
        try {
            rating = tag.getFirst("RATING WMP");
        } catch (Exception e) {
            log.trace("Failed to extract RATING WMP: {}", e.getMessage());
        }

        if (rating == null || rating.isEmpty()) {
            rating = tag.getFirst(FieldKey.RATING);
        }

        if (rating != null && !rating.isEmpty()) {
            tags.put("RATING", rating);
        }
    }

    /**
     * Extracts label/publisher tag.
     * For FLAC: tries ORGANIZATION first (Traktor-compatible)
     * Falls back to RECORD_LABEL (MP3 TPUB frame)
     */
    private void extractLabel(Tag tag, Map<String, String> tags) {
        String label = null;
        try {
            label = tag.getFirst("ORGANIZATION");
        } catch (Exception e) {
            log.trace("Failed to extract ORGANIZATION: {}", e.getMessage());
        }
        if (label == null || label.isEmpty()) {
            label = tag.getFirst(FieldKey.RECORD_LABEL);
        }
        if (label != null && !label.isEmpty()) {
            tags.put("PUBLISHER", label);
        }
    }

    public Map<String, String> extractSpecificTags(Path audioFile, String... tagNames) {
        Map<String, String> allTags = extractAllTags(audioFile);
        Map<String, String> filteredTags = new HashMap<>();

        for (String tagName : tagNames) {
            if (allTags.containsKey(tagName)) {
                filteredTags.put(tagName, allTags.get(tagName));
            }
        }

        return filteredTags;
    }
}
