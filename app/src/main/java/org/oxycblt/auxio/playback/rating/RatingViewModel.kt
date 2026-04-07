/*
 * Copyright (c) 2024 Auxio Project
 * RatingViewModel.kt is part of Auxio.
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
 
package org.oxycblt.auxio.playback.rating

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.oxycblt.auxio.music.MusicRepository
import org.oxycblt.musikr.Music
import org.oxycblt.musikr.Song
import org.oxycblt.musikr.TagWriter

@HiltViewModel
class RatingViewModel
@Inject
constructor(private val tagWriter: TagWriter, private val musicRepository: MusicRepository) :
    ViewModel() {
    // In-memory overlay: set immediately after a successful write so the UI
    // reflects the new rating without waiting for a library rescan.
    private val _overlay = MutableStateFlow<Map<Music.UID, Int?>>(emptyMap())

    // Song currently shown in the rating picker dialog.
    private val _currentSong = MutableStateFlow<Song?>(null)
    val currentSong: StateFlow<Song?> = _currentSong.asStateFlow()

    /** Look up [uid] from the library and expose it via [currentSong]. */
    fun setSong(uid: Music.UID) {
        _currentSong.value = musicRepository.library?.findSong(uid)
    }

    /** Returns the effective rating byte for [song] (overlay takes precedence over file tag). */
    fun getEffectiveRating(song: Song): Int? = _overlay.value[song.uid] ?: song.rawPopmRating

    /**
     * Returns a [StateFlow] that emits the effective rating byte whenever either [songFlow] or the
     * in-memory overlay changes.
     */
    fun ratingFor(songFlow: StateFlow<Song?>): StateFlow<Int?> =
        combine(songFlow, _overlay) { song, overlay ->
                song?.let { overlay[it.uid] ?: it.rawPopmRating }
            }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    // ---------- Navigation state ----------

    private val _showPicker = MutableStateFlow<Song?>(null)

    /** Non-null while the rating picker dialog should be shown for this song. */
    val showPicker: StateFlow<Song?> = _showPicker.asStateFlow()

    fun openRatingPicker(song: Song) {
        _showPicker.value = song
    }

    fun dismissPicker() {
        _showPicker.value = null
    }

    // ---------- Write ----------

    /**
     * Write [halfStars] (0 = remove rating, 0.5–5.0 = rated) to the file and update the overlay.
     */
    fun setRating(context: Context, song: Song, halfStars: Float) {
        val ratingByte =
            if (halfStars <= 0f) 0 else (halfStars / 5f * 255f).roundToInt().coerceIn(1, 255)
        viewModelScope.launch {
            val success = tagWriter.writeRating(context, song, ratingByte)
            if (success) {
                _overlay.update { map ->
                    if (ratingByte > 0) map + (song.uid to ratingByte) else map + (song.uid to null)
                }
            }
        }
    }
}
