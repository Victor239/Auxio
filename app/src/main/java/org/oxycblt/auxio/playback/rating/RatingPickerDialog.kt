/*
 * Copyright (c) 2024 Auxio Project
 * RatingPickerDialog.kt is part of Auxio.
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

import android.os.Bundle
import android.view.LayoutInflater
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import dagger.hilt.android.AndroidEntryPoint
import kotlin.math.roundToInt
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.DialogRateBinding
import org.oxycblt.auxio.ui.ViewBindingMaterialDialogFragment
import org.oxycblt.auxio.util.collectImmediately
import org.oxycblt.musikr.Song

/** Dialog that lets the user set or remove a half-star (0.5–5.0) file-embedded rating. */
@AndroidEntryPoint
class RatingPickerDialog : ViewBindingMaterialDialogFragment<DialogRateBinding>() {
    private val ratingModel: RatingViewModel by activityViewModels()
    private val args: RatingPickerDialogArgs by navArgs()

    override fun onCreateBinding(inflater: LayoutInflater) = DialogRateBinding.inflate(inflater)

    override fun onConfigDialog(builder: AlertDialog.Builder) {
        builder
            .setTitle(R.string.lbl_add_rating)
            .setPositiveButton(R.string.lbl_ok) { _, _ ->
                ratingModel.currentSong.value?.let { song ->
                    ratingModel.setRating(requireContext(), song, requireBinding().rateBar.rating)
                }
            }
            .setNeutralButton(R.string.lbl_remove_rating) { _, _ ->
                ratingModel.currentSong.value?.let { song ->
                    ratingModel.setRating(requireContext(), song, 0f)
                }
            }
            .setNegativeButton(R.string.lbl_cancel, null)
    }

    override fun onBindingCreated(binding: DialogRateBinding, savedInstanceState: Bundle?) {
        ratingModel.setSong(args.songUid)
        collectImmediately(ratingModel.currentSong, ::updateSong)
    }

    private fun updateSong(song: Song?) {
        if (song == null) {
            findNavController().navigateUp()
            return
        }
        val rawByte = ratingModel.getEffectiveRating(song)
        requireBinding().rateBar.rating = rawByte?.let { rawPopmToHalfStars(it) } ?: 0f
    }
}

/** Convert a raw 0–255 POPM byte to the nearest 0.5-star float value (clamped to [0.5, 5.0]). */
internal fun rawPopmToHalfStars(rawByte: Int): Float =
    ((rawByte / 255f * 10f).roundToInt() / 2f).coerceIn(0.5f, 5.0f)
