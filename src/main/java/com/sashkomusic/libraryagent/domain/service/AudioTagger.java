package com.sashkomusic.libraryagent.domain.service;

import com.sashkomusic.libraryagent.domain.model.ReleaseMetadata;
import com.sashkomusic.libraryagent.domain.model.Source;
import com.sashkomusic.libraryagent.domain.model.TrackMatch;
import lombok.extern.slf4j.Slf4j;
import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;
import org.jaudiotagger.tag.id3.AbstractID3v2Tag;
import org.jaudiotagger.tag.id3.ID3v24Frames;
import org.jaudiotagger.tag.id3.framebody.FrameBodyTXXX;
import org.jaudiotagger.tag.id3.ID3v24Frame;
import org.jaudiotagger.tag.images.Artwork;
import org.jaudiotagger.tag.images.ArtworkFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Path;

@Slf4j
@Service
public class AudioTagger {

    public void tagFile(Path audioFile, ReleaseMetadata metadata, TrackMatch match, byte[] coverArt) {
        try {
            log.info("Starting tag operation for: {}", audioFile.getFileName());

            AudioFile f = AudioFileIO.read(audioFile.toFile());
            Tag tag = f.getTagOrCreateAndSetDefault();

            log.debug("Writing tags: album_artist='{}', artist='{}', album='{}', track={} - '{}'",
                    metadata.artist(), match.artist(), metadata.title(), match.trackNumber(), match.trackTitle());

            // Use per-track artist for ARTIST tag
            tag.setField(FieldKey.ARTIST, match.artist());
            // Use album artist for ALBUM_ARTIST tag
            tag.setField(FieldKey.ALBUM_ARTIST, metadata.artist());
            tag.setField(FieldKey.ALBUM, metadata.title());
            tag.setField(FieldKey.TITLE, match.trackTitle());
            tag.setField(FieldKey.TRACK, String.valueOf(match.trackNumber()));

            if (metadata.years() != null && !metadata.years().isEmpty()) {
                tag.setField(FieldKey.YEAR, metadata.years().getFirst());
            }

            if (metadata.tags() != null && !metadata.tags().isEmpty()) {
                String allGenres = String.join(";", metadata.tags());
                tag.setField(FieldKey.GENRE, allGenres);
            }

            if (metadata.types() != null && !metadata.types().isEmpty()) {
                String allTypes = String.join(";", metadata.types());
                tag.setField(FieldKey.GROUPING, allTypes);
            }

            if (metadata.tags() != null && !metadata.tags().isEmpty()) {
                String tagsComment = String.join(", ", metadata.tags());
                tag.setField(FieldKey.COMMENT, tagsComment);
            }

            if (tag instanceof AbstractID3v2Tag id3Tag) {
                if (metadata.id() != null && !metadata.id().isEmpty()) {
                    addCustomTextField(id3Tag, "RELEASEID", metadata.id());
                }

                Source source = metadata.source();
                if (source != null) {
                    addCustomTextField(id3Tag, "SOURCE", source.name());
                }

                if (metadata.years() != null && metadata.years().size() > 1) {
                    String allYears = String.join(";", metadata.years());
                    addCustomTextField(id3Tag, "RELEASEYEARS", allYears);
                }
            }

            if (coverArt != null && coverArt.length > 0) {
                try {
                    Artwork artwork = ArtworkFactory.createArtworkFromFile(new File(audioFile.getParent().toString(), "cover.jpg"));
                    tag.deleteArtworkField();
                    tag.setField(artwork);
                } catch (Exception ex) {
                    log.warn("Failed to embed cover art in {}: {}", audioFile.getFileName(), ex.getMessage());
                }
            }

            f.commit();

            log.info("Successfully tagged and saved: {} - {}", match.trackNumber(), match.trackTitle());

        } catch (Exception ex) {
            log.error("Error tagging file {}: {}", audioFile.getFileName(), ex.getMessage(), ex);
            throw new TaggingException("Failed to tag file: " + audioFile.getFileName(), ex);
        }
    }

    public static class TaggingException extends RuntimeException {
        public TaggingException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private void addCustomTextField(AbstractID3v2Tag tag, String description, String value) {
        try {
            FrameBodyTXXX frameBody = new FrameBodyTXXX();
            frameBody.setDescription(description);
            frameBody.setText(value);

            ID3v24Frame frame = new ID3v24Frame(ID3v24Frames.FRAME_ID_USER_DEFINED_INFO);
            frame.setBody(frameBody);

            tag.setFrame(frame);
            log.debug("Added custom field: {}={}", description, value);
        } catch (Exception e) {
            log.warn("Failed to add custom field {}: {}", description, e.getMessage());
        }
    }
}
