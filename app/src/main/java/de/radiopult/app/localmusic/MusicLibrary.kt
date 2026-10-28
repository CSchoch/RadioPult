package de.radiopult.app.localmusic

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Ein Musikstück auf dem Handy (aus der Medienbibliothek von Android). */
data class Track(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val mime: String,
    val size: Long,
) {
    val uri: Uri get() = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
}

/** Liest die Musik des Handys, wie UNDOK für „Lokale Musik“: Titel, Künstler, Alben. */
object MusicLibrary {

    suspend fun tracks(context: Context): List<Track> = withContext(Dispatchers.IO) {
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.MIME_TYPE,
            MediaStore.Audio.Media.SIZE,
        )
        val result = mutableListOf<Track>()
        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            "${MediaStore.Audio.Media.IS_MUSIC} != 0",
            null,
            "${MediaStore.Audio.Media.ARTIST}, ${MediaStore.Audio.Media.ALBUM}, ${MediaStore.Audio.Media.TRACK}",
        )?.use { c ->
            while (c.moveToNext()) {
                result += Track(
                    id = c.getLong(0),
                    title = c.getString(1).orEmpty(),
                    artist = c.getString(2)?.takeUnless { it == MediaStore.UNKNOWN_STRING }.orEmpty(),
                    album = c.getString(3).orEmpty(),
                    durationMs = c.getLong(4),
                    mime = c.getString(5) ?: "audio/mpeg",
                    size = c.getLong(6),
                )
            }
        }
        result
    }
}
