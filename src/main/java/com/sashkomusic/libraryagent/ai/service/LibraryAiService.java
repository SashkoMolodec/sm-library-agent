package com.sashkomusic.libraryagent.ai.service;

import com.sashkomusic.libraryagent.domain.model.TrackMatch;
import dev.langchain4j.service.spring.AiService;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

@AiService
public interface LibraryAiService {

    @SystemMessage("""
            You are a music librarian helping to match downloaded music files to official track titles.

            Your task is to match ALL downloaded files (which may have messy or incorrect names)
            to the correct tracks from an official album tracklist.

            Consider:
            - Track numbers in filenames:
              * Regular numeric: 01, 1, 02, 2, etc.
              * Vinyl notation: A1, A2, B1, B2, C1, etc.
                (A1 = track 1, A2 = track 2, B1 = track 3, B2 = track 4, and so on)
                Side A contains first half of tracks, Side B contains second half
            - Similar track names (typos, different spelling, transliteration)
            - File order in the list
            - Duration hints if available

            Return ONLY a valid JSON array with one entry per file in the same order:
            [
              {"trackNumber": 1, "artist": "Artist Name", "trackTitle": "Title 1"},
              {"trackNumber": 2, "artist": "Artist Name", "trackTitle": "Title 2"}
            ]

            Rules:
            - Return one match per file in the exact same order as files are listed
            - trackNumber must be a valid integer from the tracklist (1, 2, 3, etc.)
            - artist must exactly match the artist from the tracklist for that track
            - trackTitle must exactly match the title from the tracklist for that track
            - The tracklist format is "Artist - Title" per line, extract both parts
            - For vinyl notation (A1, B2): convert to sequential track numbers
            - Always return valid JSON array, never explain or add extra text
            - IMPORTANT: Use only straight quotes " in JSON, never use typographic quotes like „ " " ' '
            - Do not wrap JSON in markdown code blocks
            """)
    @UserMessage("""
            Album: {{artist}} - {{album}}

            Official Tracklist:
            {{tracklist}}

            Downloaded files (match each one in order):
            {{files}}

            Return JSON array with matches in the same order.
            """)
    java.util.List<TrackMatch> matchAllFilesToTracks(
            @V("artist") String artist,
            @V("album") String album,
            @V("tracklist") String tracklist,
            @V("files") String filesFormatted
    );
}