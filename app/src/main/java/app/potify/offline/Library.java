package app.potify.offline;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/** The offline library: playlists, tracks, likes and where their files live. */
public final class Library extends SQLiteOpenHelper {
    public static final String QUEUED = "queued";
    public static final String ACTIVE = "active";
    public static final String SAVED = "saved";
    public static final String FAILED = "failed";
    /** The user deleted this track's audio; it stays listed but is not downloaded again. */
    public static final String REMOVED = "removed";

    public static final String YOUTUBE = "youtube";
    public static final String LOCAL = "local";
    /** A playlist or album imported from a Spotify link; its songs were matched to YouTube videos. */
    public static final String SPOTIFY = "spotify";
    /** Pseudo playlist id for the Liked songs list. */
    public static final String LIKED = "liked";
    /** Pseudo playlist id for All songs: every song in any playlist or in Liked songs. */
    public static final String ALL = "all";
    /** Your playlist that collects songs added from single video links. */
    public static final String ADDED = "local:added";
    /** Title shown for a single video until its details are fetched. */
    public static final String PLACEHOLDER_TITLE = "YouTube video";

    public record Playlist(String id, String title, String kind, String status, String error,
                           int total, int saved, int failed, int pending, long bytes, long seconds,
                           List<String> thumbs) {
        public boolean isLocal() { return LOCAL.equals(kind); }
        public boolean isSpotify() { return SPOTIFY.equals(kind); }
    }

    /** A song matched from Spotify: the YouTube video to download plus Spotify's own details. */
    public record ImportedSong(String videoId, String title, String artist, long seconds, String sourceId) {}

    public record Track(String id, String title, String artist, String status, String file, String error,
                        long seconds, String thumb, long bytes, boolean liked) {
        public boolean isSaved() { return SAVED.equals(status) && file != null; }
    }

    public record DownloadSummary(int queued, int active, int failed, int readingPlaylists) {
        public int waiting() { return queued + active; }
    }

    private static final String TRACK_COLUMNS =
            "t.id,t.title,t.artist,t.status,t.file,t.error,t.duration,t.thumb,t.size,t.liked";
    private static final String IN_LIBRARY =
            "(t.liked IS NOT NULL OR EXISTS(SELECT 1 FROM playlist_items i WHERE i.track_id=t.id))";

    private static Library instance;
    public final File audioDir;
    public final File thumbDir;
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();

    public static synchronized Library get(Context context) {
        if (instance == null) instance = new Library(context.getApplicationContext());
        return instance;
    }

    private Library(Context context) {
        super(context, "library.db", null, 4);
        audioDir = new File(context.getFilesDir(), "audio");
        thumbDir = new File(context.getFilesDir(), "thumbs");
        setWriteAheadLoggingEnabled(true);
    }

    @Override
    public void onConfigure(SQLiteDatabase db) {
        db.setForeignKeyConstraintsEnabled(true);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE playlists(id TEXT PRIMARY KEY, title TEXT NOT NULL, "
                + "status TEXT NOT NULL, error TEXT, updated INTEGER NOT NULL, "
                + "kind TEXT NOT NULL DEFAULT 'youtube')");
        db.execSQL("CREATE TABLE tracks(id TEXT PRIMARY KEY, title TEXT NOT NULL, "
                + "artist TEXT, status TEXT NOT NULL, file TEXT, error TEXT, "
                + "duration INTEGER, thumb TEXT, size INTEGER, liked INTEGER)");
        db.execSQL("CREATE TABLE playlist_items(playlist_id TEXT REFERENCES playlists(id), "
                + "position INTEGER, track_id TEXT REFERENCES tracks(id), source_id TEXT, "
                + "PRIMARY KEY(playlist_id, position))");
        db.execSQL("CREATE INDEX items_by_track ON playlist_items(track_id)");
        createKnownSources(db);
    }

    /**
     * Spotify songs a playlist already covers without an item of their own: two Spotify entries
     * matched to one video, or a duplicate merged into a copy the playlist already had. A refresh
     * treats them as saved instead of offering them again.
     */
    private static void createKnownSources(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS known_sources(playlist_id TEXT NOT NULL, "
                + "source_id TEXT NOT NULL, PRIMARY KEY(playlist_id, source_id))");
        // Videos merged into another copy of the same song: when a playlist refresh, a link or a
        // Spotify match brings one back, the kept copy is used instead.
        db.execSQL("CREATE TABLE IF NOT EXISTS aliases(video_id TEXT PRIMARY KEY, track_id TEXT NOT NULL)");
    }

    /** The song to use for a video: the kept copy if the video was merged into one, else the video. */
    private static String resolve(SQLiteDatabase db, String videoId) {
        try (Cursor c = db.rawQuery("SELECT a.track_id FROM aliases a JOIN tracks t ON t.id=a.track_id "
                + "WHERE a.video_id=?", new String[]{videoId})) {
            return c.moveToFirst() ? c.getString(0) : videoId;
        }
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE playlists ADD COLUMN kind TEXT NOT NULL DEFAULT 'youtube'");
            db.execSQL("ALTER TABLE tracks ADD COLUMN duration INTEGER");
            db.execSQL("ALTER TABLE tracks ADD COLUMN thumb TEXT");
            db.execSQL("ALTER TABLE tracks ADD COLUMN size INTEGER");
            db.execSQL("ALTER TABLE tracks ADD COLUMN liked INTEGER");
        }
        if (oldVersion < 3) {
            // The Spotify track id an item was matched from, so a refresh knows what's new.
            db.execSQL("ALTER TABLE playlist_items ADD COLUMN source_id TEXT");
        }
        if (oldVersion < 4) createKnownSources(db);
    }

    // ---- change notifications -------------------------------------------------------------

    public void addListener(Runnable listener) { listeners.add(listener); }
    public void removeListener(Runnable listener) { listeners.remove(listener); }

    private void changed() {
        for (Runnable listener : listeners) listener.run();
    }

    // ---- download queue -------------------------------------------------------------------

    public void enqueue(String id) {
        SQLiteDatabase db = getWritableDatabase();
        var values = new ContentValues();
        values.put("id", id);
        values.put("title", "YouTube playlist");
        values.put("status", QUEUED);
        values.put("kind", YOUTUBE);
        values.put("updated", System.currentTimeMillis());
        db.insertWithOnConflict("playlists", null, values, SQLiteDatabase.CONFLICT_IGNORE);
        values.clear();
        values.put("updated", System.currentTimeMillis());
        db.update("playlists", values, "id=?", new String[]{id});
        retryFailed(id);
        db.update("playlists", state(QUEUED, null), "id=? AND status=?", new String[]{id, SAVED});
        changed();
    }

    /** Queues failed tracks of one playlist (or of Liked songs) again. */
    public void retry(String playlist) {
        retryFailed(playlist);
        changed();
    }

    private void retryFailed(String playlist) {
        SQLiteDatabase db = getWritableDatabase();
        db.execSQL("UPDATE playlists SET status=?, error=NULL WHERE id=? AND status=?",
                new Object[]{QUEUED, playlist, FAILED});
        if (LIKED.equals(playlist)) {
            db.execSQL("UPDATE tracks SET status=?, error=NULL WHERE status=? AND liked IS NOT NULL",
                    new Object[]{QUEUED, FAILED});
        } else {
            db.execSQL("UPDATE tracks SET status=?, error=NULL WHERE status=? AND "
                            + "id IN (SELECT track_id FROM playlist_items WHERE playlist_id=?)",
                    new Object[]{QUEUED, FAILED, playlist});
        }
    }

    public void recoverInterrupted() {
        SQLiteDatabase db = getWritableDatabase();
        db.execSQL("UPDATE playlists SET status=? WHERE status=?", new Object[]{QUEUED, ACTIVE});
        db.execSQL("UPDATE tracks SET status=? WHERE status=?", new Object[]{QUEUED, ACTIVE});
        changed();
    }

    public String nextPlaylist() {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT id FROM playlists WHERE status=? AND kind=? ORDER BY updated LIMIT 1",
                new String[]{QUEUED, YOUTUBE})) {
            return c.moveToFirst() ? c.getString(0) : null;
        }
    }

    /** Marks the oldest queued YouTube playlist as being read and returns its id, or null. */
    public synchronized String claimNextPlaylist() {
        String id = nextPlaylist();
        if (id != null) playlistStatus(id, ACTIVE, null);
        return id;
    }

    /** Marks the next queued track as downloading and returns it, or null when none are queued. */
    public synchronized Track claimNextTrack() {
        List<Track> next = nextTracks(1);
        if (next.isEmpty()) return null;
        Track track = next.get(0);
        getWritableDatabase().update("tracks", state(ACTIVE, null), "id=?", new String[]{track.id()});
        changed();
        return track;
    }

    public List<Track> nextTracks(int limit) {
        return readTracks("SELECT " + TRACK_COLUMNS + " FROM tracks t WHERE t.status=? AND " + IN_LIBRARY
                + " ORDER BY t.rowid LIMIT " + limit, QUEUED);
    }

    public boolean hasWork() {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT 1 FROM playlists WHERE status IN (?,?) "
                        + "UNION ALL SELECT 1 FROM tracks t WHERE status IN (?,?) AND " + IN_LIBRARY + " LIMIT 1",
                new String[]{QUEUED, ACTIVE, QUEUED, ACTIVE})) {
            return c.moveToFirst();
        }
    }

    public DownloadSummary downloadSummary() {
        int queued = 0, active = 0, failed = 0, reading = 0;
        try (Cursor c = getReadableDatabase().rawQuery("SELECT "
                + "COALESCE(SUM(t.status='queued'),0), COALESCE(SUM(t.status='active'),0), "
                + "COALESCE(SUM(t.status='failed'),0) FROM tracks t WHERE " + IN_LIBRARY, null)) {
            if (c.moveToFirst()) {
                queued = c.getInt(0);
                active = c.getInt(1);
                failed = c.getInt(2);
            }
        }
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM playlists WHERE status IN (?,?)", new String[]{QUEUED, ACTIVE})) {
            if (c.moveToFirst()) reading = c.getInt(0);
        }
        return new DownloadSummary(queued, active, failed, reading);
    }

    public void importPlaylist(String id, YouTubeSource.Playlist playlist) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("playlist_items", "playlist_id=?", new String[]{id});
            int position = 0;
            var listed = new HashSet<String>();
            for (YouTubeSource.Entry original : playlist.entries()) {
                // A YouTube playlist can hold the same video twice; list it once.
                String trackId = resolve(db, original.id());
                if (!listed.add(trackId)) continue;
                var entry = new YouTubeSource.Entry(trackId, original.title(), original.artist(), original.seconds());
                var track = new ContentValues();
                track.put("id", entry.id());
                track.put("title", entry.title() == null ? "Untitled video" : entry.title());
                track.put("artist", entry.artist());
                track.put("status", QUEUED);
                if (entry.seconds() > 0) track.put("duration", entry.seconds());
                db.insertWithOnConflict("tracks", null, track, SQLiteDatabase.CONFLICT_IGNORE);
                if (entry.seconds() > 0) {
                    db.execSQL("UPDATE tracks SET duration=? WHERE id=? AND (duration IS NULL OR duration<=0)",
                            new Object[]{entry.seconds(), entry.id()});
                }
                var item = new ContentValues();
                item.put("playlist_id", id);
                item.put("position", position++);
                item.put("track_id", entry.id());
                db.insertOrThrow("playlist_items", null, item);
            }
            var values = state(SAVED, null);
            values.put("title", playlist.title() == null ? "YouTube playlist" : playlist.title());
            db.update("playlists", values, "id=?", new String[]{id});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        changed();
    }

    public void playlistStatus(String id, String status, String error) {
        getWritableDatabase().update("playlists", state(status, error), "id=?", new String[]{id});
        changed();
    }

    public void trackStatus(String id, String status, String file, String error) {
        var values = state(status, error);
        values.put("file", file);
        if (file == null) values.putNull("size");
        else values.put("size", audioFile(file).length());
        getWritableDatabase().update("tracks", values, "id=?", new String[]{id});
        changed();
    }

    // ---- reading ----------------------------------------------------------------------------

    public List<Playlist> playlists() {
        var results = new ArrayList<Playlist>();
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor c = db.rawQuery(
                "SELECT p.id,p.title,p.kind,p.status,p.error,COUNT(i.track_id),"
                        + "COALESCE(SUM(t.status='saved'),0),COALESCE(SUM(t.status='failed'),0),"
                        + "COALESCE(SUM(t.status IN ('queued','active')),0),"
                        + "COALESCE(SUM(CASE WHEN t.status='saved' THEN t.size ELSE 0 END),0),"
                        + "COALESCE(SUM(t.duration),0) "
                        + "FROM playlists p LEFT JOIN playlist_items i ON i.playlist_id=p.id "
                        + "LEFT JOIN tracks t ON t.id=i.track_id GROUP BY p.id ORDER BY p.updated DESC", null)) {
            while (c.moveToNext()) {
                String id = c.getString(0);
                results.add(new Playlist(id, c.getString(1), c.getString(2), c.getString(3), c.getString(4),
                        c.getInt(5), c.getInt(6), c.getInt(7), c.getInt(8), c.getLong(9), c.getLong(10),
                        coverThumbs(db, id)));
            }
        }
        return results;
    }

    private List<String> coverThumbs(SQLiteDatabase db, String playlist) {
        var thumbs = new ArrayList<String>(4);
        try (Cursor c = db.rawQuery("SELECT t.thumb FROM playlist_items i JOIN tracks t ON t.id=i.track_id "
                + "WHERE i.playlist_id=? AND t.thumb IS NOT NULL AND t.thumb<>'' "
                + "ORDER BY i.position LIMIT 4", new String[]{playlist})) {
            while (c.moveToNext()) thumbs.add(c.getString(0));
        }
        return thumbs;
    }

    public List<Track> tracks(String playlist) {
        if (LIKED.equals(playlist)) return likedTracks();
        if (ALL.equals(playlist)) return allTracks();
        return readTracks("SELECT " + TRACK_COLUMNS + " FROM playlist_items i "
                + "JOIN tracks t ON i.track_id=t.id WHERE i.playlist_id=? ORDER BY i.position", playlist);
    }

    public List<Track> likedTracks() {
        return readTracks("SELECT " + TRACK_COLUMNS + " FROM tracks t WHERE t.liked IS NOT NULL "
                + "ORDER BY t.liked DESC");
    }

    /** Every song in the library, A to Z. */
    public List<Track> allTracks() {
        return readTracks("SELECT " + TRACK_COLUMNS + " FROM tracks t WHERE " + IN_LIBRARY
                + " ORDER BY t.title COLLATE NOCASE, t.rowid");
    }

    public int songCount() {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM tracks t WHERE " + IN_LIBRARY, null)) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    public Track track(String id) {
        var found = readTracks("SELECT " + TRACK_COLUMNS + " FROM tracks t WHERE t.id=?", id);
        return found.isEmpty() ? null : found.get(0);
    }

    public Map<String, Track> tracksByIds(List<String> ids) {
        var found = new HashMap<String, Track>();
        for (int start = 0; start < ids.size(); start += 400) {
            List<String> chunk = ids.subList(start, Math.min(ids.size(), start + 400));
            String marks = String.join(",", Collections.nCopies(chunk.size(), "?"));
            for (Track track : readTracks("SELECT " + TRACK_COLUMNS + " FROM tracks t WHERE t.id IN ("
                    + marks + ")", chunk.toArray(new String[0]))) {
                found.put(track.id(), track);
            }
        }
        return found;
    }

    public List<Track> search(String text) {
        String pattern = "%" + text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        return readTracks("SELECT " + TRACK_COLUMNS + " FROM tracks t WHERE "
                + "(t.title LIKE ? ESCAPE '\\' OR t.artist LIKE ? ESCAPE '\\') AND " + IN_LIBRARY
                + " ORDER BY (t.status='saved') DESC, t.title COLLATE NOCASE LIMIT 100", pattern, pattern);
    }

    private List<Track> readTracks(String query, String... args) {
        var tracks = new ArrayList<Track>();
        try (Cursor c = getReadableDatabase().rawQuery(query, args)) {
            while (c.moveToNext()) tracks.add(new Track(
                    c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5),
                    c.isNull(6) ? 0 : c.getLong(6), c.getString(7), c.isNull(8) ? 0 : c.getLong(8), !c.isNull(9)));
        }
        return tracks;
    }

    // ---- single videos ----------------------------------------------------------------------------

    /**
     * Queues one video and adds it to the Added songs playlist (created when needed).
     * Returns true when the song was already downloaded.
     */
    public synchronized boolean addVideo(String video) {
        SQLiteDatabase db = getWritableDatabase();
        boolean saved;
        long now = System.currentTimeMillis();
        db.beginTransaction();
        try {
            String videoId = resolve(db, video);
            var playlist = new ContentValues();
            playlist.put("id", ADDED);
            playlist.put("title", "Added songs");
            playlist.put("status", SAVED);
            playlist.put("kind", LOCAL);
            playlist.put("updated", now);
            db.insertWithOnConflict("playlists", null, playlist, SQLiteDatabase.CONFLICT_IGNORE);
            db.execSQL("UPDATE playlists SET updated=? WHERE id=?", new Object[]{now, ADDED});
            var track = new ContentValues();
            track.put("id", videoId);
            track.put("title", PLACEHOLDER_TITLE);
            track.put("status", QUEUED);
            db.insertWithOnConflict("tracks", null, track, SQLiteDatabase.CONFLICT_IGNORE);
            db.execSQL("UPDATE tracks SET status=?, error=NULL WHERE id=? AND status IN (?,?)",
                    new Object[]{QUEUED, videoId, REMOVED, FAILED});
            try (Cursor c = db.rawQuery("SELECT status FROM tracks WHERE id=?", new String[]{videoId})) {
                saved = c.moveToFirst() && SAVED.equals(c.getString(0));
            }
            boolean listed;
            try (Cursor c = db.rawQuery("SELECT 1 FROM playlist_items WHERE playlist_id=? AND track_id=?",
                    new String[]{ADDED, videoId})) {
                listed = c.moveToFirst();
            }
            if (!listed) {
                int position = 0;
                try (Cursor c = db.rawQuery("SELECT COALESCE(MAX(position)+1,0) FROM playlist_items WHERE playlist_id=?",
                        new String[]{ADDED})) {
                    if (c.moveToFirst()) position = c.getInt(0);
                }
                var item = new ContentValues();
                item.put("playlist_id", ADDED);
                item.put("position", position);
                item.put("track_id", videoId);
                db.insertOrThrow("playlist_items", null, item);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        changed();
        return saved;
    }

    /** Replaces a single video's placeholder title, channel and length once they're known. */
    public void fillTrackInfo(String trackId, String title, String artist, long seconds) {
        SQLiteDatabase db = getWritableDatabase();
        int updated = 0;
        if (title != null && !title.isBlank()) {
            var values = new ContentValues();
            values.put("title", title);
            values.put("artist", artist);
            updated += db.update("tracks", values, "id=? AND title=?", new String[]{trackId, PLACEHOLDER_TITLE});
        }
        if (seconds > 0) {
            var values = new ContentValues();
            values.put("duration", seconds);
            updated += db.update("tracks", values, "id=? AND (duration IS NULL OR duration<=0)", new String[]{trackId});
        }
        if (updated > 0) changed();
    }

    // ---- Spotify imports ---------------------------------------------------------------------------

    /**
     * Saves songs matched from a Spotify link and queues their downloads. A playlist or album
     * becomes (or grows) a Spotify playlist; a single song goes to Added songs. Songs keep
     * Spotify's title and artists. Returns how many songs were newly added to the playlist.
     */
    public synchronized int addImportedSongs(String playlistId, String title, String kind, List<ImportedSong> songs) {
        SQLiteDatabase db = getWritableDatabase();
        long now = System.currentTimeMillis();
        int added = 0;
        db.beginTransaction();
        try {
            var playlist = new ContentValues();
            playlist.put("id", playlistId);
            playlist.put("title", title);
            playlist.put("status", SAVED);
            playlist.put("kind", kind);
            playlist.put("updated", now);
            db.insertWithOnConflict("playlists", null, playlist, SQLiteDatabase.CONFLICT_IGNORE);
            db.execSQL("UPDATE playlists SET updated=? WHERE id=?", new Object[]{now, playlistId});
            if (SPOTIFY.equals(kind)) {
                db.execSQL("UPDATE playlists SET title=? WHERE id=?", new Object[]{title, playlistId});
            }
            int position = 0;
            try (Cursor c = db.rawQuery("SELECT COALESCE(MAX(position)+1,0) FROM playlist_items WHERE playlist_id=?",
                    new String[]{playlistId})) {
                if (c.moveToFirst()) position = c.getInt(0);
            }
            for (ImportedSong matched : songs) {
                String videoId = resolve(db, matched.videoId());
                var song = new ImportedSong(videoId, matched.title(), matched.artist(), matched.seconds(), matched.sourceId());
                var track = new ContentValues();
                track.put("id", song.videoId());
                track.put("title", song.title());
                track.put("artist", song.artist());
                track.put("status", QUEUED);
                if (song.seconds() > 0) track.put("duration", song.seconds());
                db.insertWithOnConflict("tracks", null, track, SQLiteDatabase.CONFLICT_IGNORE);
                // A video saved before under YouTube's placeholder title gets Spotify's details.
                db.execSQL("UPDATE tracks SET title=?, artist=? WHERE id=? AND title=?",
                        new Object[]{song.title(), song.artist(), song.videoId(), PLACEHOLDER_TITLE});
                db.execSQL("UPDATE tracks SET status=?, error=NULL WHERE id=? AND status IN (?,?)",
                        new Object[]{QUEUED, song.videoId(), REMOVED, FAILED});
                boolean listed;
                try (Cursor c = db.rawQuery("SELECT 1 FROM playlist_items WHERE playlist_id=? AND track_id=?",
                        new String[]{playlistId, song.videoId()})) {
                    listed = c.moveToFirst();
                }
                if (listed) {
                    rememberSource(db, playlistId, song.sourceId());
                    continue;
                }
                var item = new ContentValues();
                item.put("playlist_id", playlistId);
                item.put("position", position++);
                item.put("track_id", song.videoId());
                item.put("source_id", song.sourceId());
                db.insertOrThrow("playlist_items", null, item);
                added++;
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        changed();
        return added;
    }

    /** Spotify track ids already in a playlist, so a refresh only reviews the new songs. */
    public Set<String> importedSourceIds(String playlistId) {
        var ids = new HashSet<String>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT source_id FROM playlist_items WHERE playlist_id=? AND source_id IS NOT NULL "
                        + "UNION SELECT source_id FROM known_sources WHERE playlist_id=?",
                new String[]{playlistId, playlistId})) {
            while (c.moveToNext()) ids.add(c.getString(0));
        }
        return ids;
    }

    private static void rememberSource(SQLiteDatabase db, String playlistId, String sourceId) {
        if (sourceId == null) return;
        var values = new ContentValues();
        values.put("playlist_id", playlistId);
        values.put("source_id", sourceId);
        db.insertWithOnConflict("known_sources", null, values, SQLiteDatabase.CONFLICT_IGNORE);
    }

    /**
     * The library's song for a Spotify track imported before (from any playlist), so a new import
     * reuses that video instead of downloading another copy. Songs whose download failed are left
     * out: their video may be gone.
     */
    public Track trackForSource(String sourceId) {
        var found = readTracks("SELECT " + TRACK_COLUMNS + " FROM playlist_items i JOIN tracks t ON t.id=i.track_id "
                + "WHERE i.source_id=? AND t.status<>? ORDER BY (t.status='saved') DESC LIMIT 1", sourceId, FAILED);
        return found.isEmpty() ? null : found.get(0);
    }

    // ---- likes and your own playlists ----------------------------------------------------------

    public void setLiked(String trackId, boolean liked) {
        var values = new ContentValues();
        if (liked) values.put("liked", System.currentTimeMillis());
        else values.putNull("liked");
        getWritableDatabase().update("tracks", values, "id=?", new String[]{trackId});
        if (!liked) pruneOrphans();
        changed();
    }

    public String createPlaylist(String title) {
        String id = "local:" + UUID.randomUUID();
        var values = new ContentValues();
        values.put("id", id);
        values.put("title", title);
        values.put("status", SAVED);
        values.put("kind", LOCAL);
        values.put("updated", System.currentTimeMillis());
        getWritableDatabase().insertOrThrow("playlists", null, values);
        changed();
        return id;
    }

    public void renamePlaylist(String id, String title) {
        var values = new ContentValues();
        values.put("title", title);
        getWritableDatabase().update("playlists", values, "id=? AND kind=?", new String[]{id, LOCAL});
        changed();
    }

    /** Adds a track to one of your playlists. Returns false if it was already there. */
    public boolean addToPlaylist(String playlistId, String trackId) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            try (Cursor c = db.rawQuery("SELECT 1 FROM playlist_items WHERE playlist_id=? AND track_id=?",
                    new String[]{playlistId, trackId})) {
                if (c.moveToFirst()) return false;
            }
            int position = 0;
            try (Cursor c = db.rawQuery("SELECT COALESCE(MAX(position)+1,0) FROM playlist_items WHERE playlist_id=?",
                    new String[]{playlistId})) {
                if (c.moveToFirst()) position = c.getInt(0);
            }
            var item = new ContentValues();
            item.put("playlist_id", playlistId);
            item.put("position", position);
            item.put("track_id", trackId);
            db.insertOrThrow("playlist_items", null, item);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        changed();
        return true;
    }

    public void removeFromPlaylist(String playlistId, String trackId) {
        getWritableDatabase().delete("playlist_items", "playlist_id=? AND track_id=?",
                new String[]{playlistId, trackId});
        pruneOrphans();
        changed();
    }

    // ---- duplicates ------------------------------------------------------------------------------

    /** Titles of the playlists each song is in, keyed by track id. */
    public Map<String, List<String>> playlistTitlesByTrack() {
        var titles = new HashMap<String, List<String>>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT i.track_id, p.title FROM playlist_items i "
                + "JOIN playlists p ON p.id=i.playlist_id ORDER BY p.updated DESC", null)) {
            while (c.moveToNext()) {
                List<String> list = titles.computeIfAbsent(c.getString(0), k -> new ArrayList<>());
                if (!list.contains(c.getString(1))) list.add(c.getString(1));
            }
        }
        return titles;
    }

    /** Songs that came from a Spotify import, so their title and artists are Spotify's clean ones. */
    public Set<String> spotifyTrackIds() {
        var ids = new HashSet<String>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT DISTINCT track_id FROM playlist_items WHERE source_id IS NOT NULL", null)) {
            while (c.moveToNext()) ids.add(c.getString(0));
        }
        return ids;
    }

    /**
     * Merges copies of one song into the copy that's kept: every playlist that had a removed copy
     * now has the kept one in its place (or keeps just the one it already had), a like carries
     * over, and the removed copies are deleted with their audio and artwork.
     */
    public synchronized void mergeTracks(String keep, List<String> remove) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            Long liked = null;
            try (Cursor c = db.rawQuery("SELECT liked FROM tracks WHERE id=?", new String[]{keep})) {
                if (!c.moveToFirst()) return; // The kept song is gone; change nothing.
                if (!c.isNull(0)) liked = c.getLong(0);
            }
            for (String id : remove) {
                if (id.equals(keep)) continue;
                var items = new ArrayList<String[]>();
                try (Cursor c = db.rawQuery("SELECT playlist_id, position, source_id FROM playlist_items WHERE track_id=?",
                        new String[]{id})) {
                    while (c.moveToNext()) items.add(new String[]{c.getString(0), c.getString(1), c.getString(2)});
                }
                for (String[] item : items) {
                    boolean hasKept;
                    try (Cursor c = db.rawQuery("SELECT 1 FROM playlist_items WHERE playlist_id=? AND track_id=?",
                            new String[]{item[0], keep})) {
                        hasKept = c.moveToFirst();
                    }
                    if (hasKept) {
                        db.delete("playlist_items", "playlist_id=? AND position=?", new String[]{item[0], item[1]});
                        rememberSource(db, item[0], item[2]);
                    } else {
                        var values = new ContentValues();
                        values.put("track_id", keep);
                        db.update("playlist_items", values, "playlist_id=? AND position=?", new String[]{item[0], item[1]});
                    }
                }
                try (Cursor c = db.rawQuery("SELECT liked FROM tracks WHERE id=?", new String[]{id})) {
                    // The earliest like wins, so the song keeps its place in Liked songs.
                    if (c.moveToFirst() && !c.isNull(0) && (liked == null || c.getLong(0) < liked)) liked = c.getLong(0);
                }
                db.execSQL("UPDATE tracks SET liked=NULL WHERE id=?", new Object[]{id});
                db.execSQL("UPDATE aliases SET track_id=? WHERE track_id=?", new Object[]{keep, id});
                db.execSQL("INSERT OR REPLACE INTO aliases(video_id, track_id) VALUES(?,?)", new Object[]{id, keep});
            }
            db.execSQL("DELETE FROM aliases WHERE video_id=?", new Object[]{keep});
            if (liked != null) db.execSQL("UPDATE tracks SET liked=? WHERE id=?", new Object[]{liked, keep});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        pruneOrphans();
        changed();
    }

    // ---- deleting and storage ------------------------------------------------------------------

    public void deletePlaylist(String id) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("playlist_items", "playlist_id=?", new String[]{id});
            db.delete("known_sources", "playlist_id=?", new String[]{id});
            db.delete("playlists", "id=?", new String[]{id});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        pruneOrphans();
        changed();
    }

    /** Deletes a track's audio from the phone but keeps it listed so it can be downloaded again. */
    public void removeDownload(String trackId) {
        Track track = track(trackId);
        if (track == null) return;
        if (track.file() != null) audioFile(track.file()).delete();
        var values = state(REMOVED, null);
        values.putNull("file");
        values.putNull("size");
        getWritableDatabase().update("tracks", values, "id=?", new String[]{trackId});
        changed();
    }

    public void downloadAgain(String trackId) {
        getWritableDatabase().update("tracks", state(QUEUED, null), "id=? AND status IN (?,?)",
                new String[]{trackId, REMOVED, FAILED});
        changed();
    }

    /** Removes tracks that are in no playlist and not liked, together with their files. */
    private void pruneOrphans() {
        SQLiteDatabase db = getWritableDatabase();
        var orphans = new ArrayList<String[]>();
        try (Cursor c = db.rawQuery("SELECT t.id,t.file,t.thumb FROM tracks t WHERE NOT " + IN_LIBRARY, null)) {
            while (c.moveToNext()) orphans.add(new String[]{c.getString(0), c.getString(1), c.getString(2)});
        }
        for (String[] orphan : orphans) {
            db.delete("tracks", "id=?", new String[]{orphan[0]});
            if (orphan[1] != null) audioFile(orphan[1]).delete();
            if (orphan[2] != null && !orphan[2].isEmpty()) thumbFile(orphan[2]).delete();
        }
    }

    /**
     * Deletes files that no track refers to. Unfinished downloads (.part) are only removed when
     * {@code includePartial} is true, i.e. when no download is running. Returns the bytes freed.
     */
    public long cleanStorage(boolean includePartial) {
        pruneOrphans();
        Set<String> referenced = new HashSet<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT file, thumb FROM tracks", null)) {
            while (c.moveToNext()) {
                if (c.getString(0) != null) referenced.add(c.getString(0));
                if (c.getString(1) != null) referenced.add(c.getString(1));
            }
        }
        long freed = 0;
        for (File dir : new File[]{audioDir, thumbDir}) {
            File[] files = dir.listFiles();
            if (files == null) continue;
            for (File file : files) {
                String name = file.getName();
                boolean partial = name.endsWith(".part") || name.endsWith(".tmp");
                if (referenced.contains(name) || (partial && !includePartial)) continue;
                long size = file.length();
                if (file.delete()) freed += size;
            }
        }
        changed();
        return freed;
    }

    /** Bytes used by downloaded audio and by artwork. */
    public long[] storageUsed() {
        return new long[]{directorySize(audioDir), directorySize(thumbDir)};
    }

    private static long directorySize(File dir) {
        File[] files = dir.listFiles();
        long total = 0;
        if (files != null) for (File file : files) total += file.length();
        return total;
    }

    // ---- artwork and metadata --------------------------------------------------------------------

    public List<String> tracksMissingThumb(int limit) {
        var ids = new ArrayList<String>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT t.id FROM tracks t WHERE t.thumb IS NULL AND "
                + IN_LIBRARY + " ORDER BY t.rowid LIMIT " + limit, null)) {
            while (c.moveToNext()) ids.add(c.getString(0));
        }
        return ids;
    }

    /** Records a track's artwork file name, or "" when the video has no artwork. */
    public void setThumb(String trackId, String name) {
        var values = new ContentValues();
        values.put("thumb", name);
        getWritableDatabase().update("tracks", values, "id=?", new String[]{trackId});
        changed();
    }

    public List<Track> savedMissingMetadata() {
        return readTracks("SELECT " + TRACK_COLUMNS + " FROM tracks t WHERE t.status=? AND t.file IS NOT NULL "
                + "AND (t.size IS NULL OR t.duration IS NULL OR t.duration<=0)", SAVED);
    }

    public void setMetadata(String trackId, long size, long seconds) {
        var values = new ContentValues();
        values.put("size", size);
        if (seconds > 0) values.put("duration", seconds);
        getWritableDatabase().update("tracks", values, "id=?", new String[]{trackId});
        changed();
    }

    private static ContentValues state(String status, String error) {
        var values = new ContentValues();
        values.put("status", status);
        values.put("error", error);
        return values;
    }

    public File audioFile(String name) {
        return new File(audioDir, name);
    }

    public File thumbFile(String name) {
        return new File(thumbDir, name);
    }
}
