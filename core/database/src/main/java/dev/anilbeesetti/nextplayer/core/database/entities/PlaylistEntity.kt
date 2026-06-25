package dev.anilbeesetti.nextplayer.core.database.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Phase 2.3 — Audio playlist entity.
 *
 * One row per user-created playlist. Playlists contain audio tracks via the
 * [PlaylistTrackEntity] cross-ref table.
 */
@Entity(
    tableName = "audio_playlists",
    indices = [Index(value = ["name"], unique = true)],
)
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

/**
 * Phase 2.3 — Track entry inside a playlist.
 *
 * Each row is one audio file in one playlist. The `order` field preserves
 * the user's chosen ordering. We store the URI as a string so playlist
 * entries survive MediaStore URI rotations (reboots, SD card remounts).
 */
@Entity(
    tableName = "audio_playlist_tracks",
    primaryKeys = ["playlistId", "uri"],
    indices = [Index(value = ["playlistId", "order"])],
)
data class PlaylistTrackEntity(
    val playlistId: Long,
    val uri: String,
    val title: String,
    val artist: String?,
    val durationMs: Long,
    val order: Int,
)
