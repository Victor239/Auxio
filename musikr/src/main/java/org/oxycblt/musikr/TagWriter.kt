/*
 * Copyright (c) 2024 Auxio Project
 * TagWriter.kt is part of Auxio.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
 
package org.oxycblt.musikr

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.oxycblt.musikr.metadata.TagLibJNI

/** Writes tag data directly into audio files. */
class TagWriter {
    /**
     * Write a POPM-compatible rating to the audio file at [song]'s URI.
     *
     * @param ratingByte Raw 0–255 byte. Use 0 to remove the rating tag.
     * @return true if the write succeeded.
     */
    suspend fun writeRating(context: Context, song: Song, ratingByte: Int): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val mimeType = song.format.mimeType
                context.contentResolver.openFileDescriptor(song.uri, "rw")?.use { pfd ->
                    TagLibJNI.writeRating(pfd, mimeType, ratingByte)
                } ?: false
            } catch (e: Exception) {
                false
            }
        }
}
