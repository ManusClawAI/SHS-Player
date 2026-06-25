package dev.anilbeesetti.nextplayer.core.data.repository

import dev.anilbeesetti.nextplayer.core.database.dao.PlaylistDao
import dev.anilbeesetti.nextplayer.core.database.entities.PlaylistEntity
import dev.anilbeesetti.nextplayer.core.database.entities.PlaylistTrackEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phase 2.3 — Repository wrapper around [PlaylistDao].
 *
 * Exposes Flow-based queries so the UI recomposes automatically when
 * playlists or tracks change.
 */
@Singleton
class PlaylistRepository @Inject constructor(
    private val playlistDao: PlaylistDao,
) {
    fun getAllPlaylists(): Flow<List<PlaylistEntity>> = playlistDao.getAllPlaylists()

    fun getTracksForPlaylist(playlistId: Long): Flow<List<PlaylistTrackEntity>> =
        playlistDao.getTracksForPlaylist(playlistId)

    suspend fun createPlaylist(name: String): Long =
        playlistDao.insertPlaylist(
            PlaylistEntity(name = name.trim(), createdAt = System.currentTimeMillis()),
        )

    suspend fun renamePlaylist(id: Long, newName: String) =
        playlistDao.renamePlaylist(id, newName.trim())

    suspend fun deletePlaylist(id: Long) = playlistDao.deletePlaylistById(id)

    suspend fun addTrackToPlaylist(
        playlistId: Long,
        uri: String,
        title: String,
        artist: String?,
        durationMs: Long,
    ) = playlistDao.addTrackToEnd(
        playlistId,
        PlaylistTrackEntity(
            playlistId = playlistId,
            uri = uri,
            title = title,
            artist = artist,
            durationMs = durationMs,
            order = 0, // DAO overwrites with the correct next-order
        ),
    )

    suspend fun removeTrackFromPlaylist(playlistId: Long, uri: String) =
        playlistDao.removeTrack(playlistId, uri)

    suspend fun clearPlaylist(playlistId: Long) = playlistDao.clearPlaylist(playlistId)

    suspend fun getTracksOnce(playlistId: Long): List<PlaylistTrackEntity> =
        playlistDao.getTracksForPlaylistOnce(playlistId)
}
