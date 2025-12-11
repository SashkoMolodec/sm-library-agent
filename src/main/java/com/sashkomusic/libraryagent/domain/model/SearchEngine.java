package com.sashkomusic.libraryagent.domain.model;

public enum SearchEngine {
    MUSICBRAINZ,
    DISCOGS,
    BANDCAMP;

    public String getName() {
        return name().toLowerCase();
    }
}
