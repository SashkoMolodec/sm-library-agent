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

            IMPORTANT: Number of files may not match number of tracks in the official tracklist:
            - MORE files than tracks: bonus tracks, remixes, or duplicates
            - FEWER files than tracks: partial download, missing tracks
            Both situations are NORMAL and you must handle them correctly.

            Matching strategy:
            1. Files are numbered sequentially in order (1, 2, 3, 4, 5, ...)
            2. Match each file to the best matching track from the official tracklist
            3. Look at track number hints in filenames (01, 02, A1, B2, etc.)
            4. Consider track name similarity and file order
            5. For EXTRA files that don't match any track in the official tracklist:
               - Assign them sequential track numbers AFTER the last official track
               - Extract title from filename (remove track number prefix, extension)
               - Use the album artist as the artist
            6. Each file MUST get a unique track number (no duplicates!)

            Consider when matching:
            - Track numbers in filenames:
              * Regular numeric: 01, 1, 02, 2, etc.
              * Vinyl notation: A1, A2, B1, B2, C1, etc.
              * May have duplicates or errors: "1. 1. ay.flac", "02 02 title.flac" - just extract the correct number
            - Similar track names (typos, different spelling, transliteration)
            - File order in the list (files are pre-sorted, follow that order)
            - IMPORTANT: Use file position in list as primary hint, filename track number as secondary

            Return ONLY a valid JSON array with one entry per file in the same order:
            [
              {"trackNumber": 1, "artist": "Artist Name", "trackTitle": "Title 1"},
              {"trackNumber": 2, "artist": "Artist Name", "trackTitle": "Title 2"},
              {"trackNumber": 5, "artist": "Artist Name", "trackTitle": "Bonus Track"}
            ]

            Examples:
            - 5 files, 4 official tracks: match files 1-4 to tracks 1-4, file 5 gets trackNumber=5 with title from filename
            - 3 files, 4 official tracks: match files to best tracks (e.g., 01.flac->track1, 02.flac->track2, 04.flac->track4)
            - Track number hints: "01. Ay.flac" -> track 1, "B2 Randseiter.flac" -> track that B2 points to

            Rules:
            - Return one match per file in the exact same order as files are listed
            - Each trackNumber must be unique (no duplicates!)
            - For official tracks: use exact artist and title from tracklist
            - For extra tracks: use sequential numbers after last official track, extract title from filename
            - Always return valid JSON array, never explain or add extra text
            - IMPORTANT: Use only straight quotes " in JSON, never use typographic quotes
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