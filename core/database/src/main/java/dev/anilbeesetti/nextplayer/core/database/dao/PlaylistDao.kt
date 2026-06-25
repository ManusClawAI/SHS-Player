package dev.anilbeesetti.nextplayer.core.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import dev.anilbeesetti.nextplayer.core.database.entities.PlaylistEntity
import dev.anilbeesetti.nextplayer.core.database.entities.PlaylistTrackEntity
import kotlinx.coroutines.flow.Flow

/**
 * Phase 2.3 — DAO for audio playlists and their tracks.
 *
 * Supports create / rename / delete playlists, add / remove tracks, and
 * fetch the ordered track list as a Flow so the UI recomposes on changes.
 */
@Dao
interface PlaylistDao {

    // ── Playlists ────────────────────────────────────────────────────────
    @Query("SELECT * FROM audio_playlists ORDER BY updatedAt DESC")
    fun getAllPlaylists(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM audio_playlists WHERE id = :id")
    suspend fun getPlaylistById(id: Long): PlaylistEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertPlaylist(playlist: PlaylistEntity): Long

    @Update
    suspend fun updatePlaylist(playlist: PlaylistEntity)

    @Query("UPDATE audio_playlists SET name = :name, updatedAt = :updatedAt WHERE id = :id")
    suspend fun renamePlaylist(id: Long, name: String, updatedAt: Long = System.currentTimeMillis())

    @Delete
    suspend fun deletePlaylist(playlist: PlaylistEntity)

    @Query("DELETE FROM audio_playlists WHERE id = :id")
    suspend fun deletePlaylistById(id: Long)

    // ── Tracks ───────────────────────────────────────────────────────────
    @Query("SELECT * FROM audio_playlist_tracks WHERE playlistId = :playlistId ORDER BY `order` ASC")
    fun getTracksForPlaylist(playlistId: Long): Flow<List<PlaylistTrackEntity>>

    @Query("SELECT * FROM audio_playlist_tracks WHERE playlistId = :playlistId ORDER BY `order` ASC")
    suspend fun getTracksForPlaylistOnce(playlistId: Long): List<PlaylistTrackEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTrack(track: PlaylistTrackEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTracks(tracks: List<PlaylistTrackEntity>)

    @Query("DELETE FROM audio_playlist_tracks WHERE playlistId = :playlistId AND uri = :uri")
    suspend fun removeTrack(playlistId: Long, uri: String)

    @Query("DELETE FROM audio_playlist_tracks WHERE playlistId = :playlistId")
    suspend fun clearPlaylist(playlistId: Long)

    @Query("SELECT MAX(`order`) FROM audio_playlist_tracks WHERE playlistId = :playlistId")
    suspend fun getMaxOrder(playlistId: Long): Int?

    @Transaction
    suspend fun addTrackToEnd(playlistId: Long, track: PlaylistTrackEntity) {
        val nextOrder = (getMaxOrder(playlistId) ?: -1) + 1
        insertTrack(track.copy(playlistId = playlistId, order = nextOrder))
    }
}
