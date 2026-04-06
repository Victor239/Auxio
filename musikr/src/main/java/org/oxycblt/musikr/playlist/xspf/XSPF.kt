/*
 * Copyright (c) 2024 Auxio Project
 * XSPF.kt is part of Auxio.
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

package org.oxycblt.musikr.playlist.xspf

import android.content.Context
import android.net.Uri
import android.util.Xml
import java.io.InputStream
import org.oxycblt.musikr.Music
import org.oxycblt.musikr.fs.Components
import org.oxycblt.musikr.fs.Path
import org.oxycblt.musikr.fs.Volume
import org.oxycblt.musikr.fs.path.VolumeManager
import org.oxycblt.musikr.playlist.ImportedPlaylist
import org.oxycblt.musikr.playlist.ImportedTrack
import org.oxycblt.musikr.playlist.PossiblePaths

/**
 * Minimal XSPF (XML Shareable Playlist Format) file format implementation.
 *
 * Supports the `<identifier>` element per track: if the value parses as a valid [Music.UID], it is
 * used for direct song lookup before falling back to `<location>`-based path matching.
 *
 * @author Victor239
 */
abstract class XSPF {
    /**
     * Reads an XSPF file from the given [stream] and returns an [ImportedPlaylist].
     *
     * @param stream The stream to read the XSPF file from.
     * @param workingDirectory The directory that the XSPF file is contained in. Used to resolve
     *   relative `<location>` URIs.
     * @return An [ImportedPlaylist], or null if the file could not be parsed.
     */
    internal abstract fun read(stream: InputStream, workingDirectory: Path): ImportedPlaylist?

    companion object {
        /** The MIME type used for XSPF files. */
        const val MIME_TYPE = "application/xspf+xml"

        internal fun from(context: Context): XSPF = XSPFImpl(VolumeManager.from(context))
    }
}

private class XSPFImpl(private val volumeManager: VolumeManager) : XSPF() {
    override fun read(stream: InputStream, workingDirectory: Path): ImportedPlaylist? {
        val volumes = volumeManager.getVolumes()
        val parser = Xml.newPullParser()
        parser.setFeature("http://xmlpull.org/v1/doc/features.html#process-namespaces", false)
        parser.setInput(stream, null)

        var playlistName: String? = null
        val tracks = mutableListOf<ImportedTrack>()

        // State for the current <track> being parsed
        var inTrackList = false
        var inTrack = false
        var currentIdentifier: String? = null
        var currentLocation: String? = null

        var eventType = parser.eventType
        while (eventType != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                org.xmlpull.v1.XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "trackList" -> inTrackList = true
                        "track" ->
                            if (inTrackList) {
                                inTrack = true
                                currentIdentifier = null
                                currentLocation = null
                            }
                        "title" ->
                            if (!inTrack) {
                                // Playlist-level <title>, not inside a <track>
                                val text = parser.nextText().trim()
                                if (text.isNotEmpty() && playlistName == null) {
                                    playlistName = text
                                }
                                // nextText() advances past END_TAG, re-read current event
                                eventType = parser.eventType
                                continue
                            }
                        "identifier" ->
                            if (inTrack) {
                                currentIdentifier = parser.nextText().trim()
                                eventType = parser.eventType
                                continue
                            }
                        "location" ->
                            if (inTrack) {
                                currentLocation = parser.nextText().trim()
                                eventType = parser.eventType
                                continue
                            }
                    }
                }
                org.xmlpull.v1.XmlPullParser.END_TAG -> {
                    when (parser.name) {
                        "track" ->
                            if (inTrack) {
                                inTrack = false
                                val uid = currentIdentifier?.let(Music.UID::fromString)
                                val paths =
                                    currentLocation?.let {
                                        resolveLocation(it, workingDirectory, volumes)
                                    } ?: emptyList()
                                // Only add a track if we have at least a UID or some paths to try
                                if (uid != null || paths.isNotEmpty()) {
                                    tracks.add(ImportedTrack(uid, paths))
                                }
                                currentIdentifier = null
                                currentLocation = null
                            }
                        "trackList" -> inTrackList = false
                    }
                }
            }
            eventType = parser.next()
        }

        return if (tracks.isNotEmpty()) {
            ImportedPlaylist(playlistName, tracks)
        } else {
            null
        }
    }

    /**
     * Converts an XSPF `<location>` value to a list of candidate [Path]s.
     *
     * Handles `file://` URIs (stripping the scheme and URL-decoding the path) and relative paths.
     * Non-`file://` absolute URIs (e.g. `http://`) yield an empty list.
     */
    private fun resolveLocation(
        location: String,
        workingDirectory: Path,
        volumes: List<Volume>,
    ): PossiblePaths {
        val rawPath =
            when {
                location.startsWith("file://", ignoreCase = true) -> {
                    // Uri.parse handles percent-decoding of the path component
                    Uri.parse(location).path ?: return emptyList()
                }
                location.startsWith("http://", ignoreCase = true) ||
                    location.startsWith("https://", ignoreCase = true) ||
                    location.contains("://") -> {
                    // Non-file URI scheme — cannot map to a local path
                    return emptyList()
                }
                else -> location
            }

        return expandPath(rawPath, workingDirectory, volumes)
    }

    /**
     * Expands a raw path string into a list of candidate [Path]s by trying both absolute and
     * relative interpretations against the known volumes.
     */
    private fun expandPath(
        rawPath: String,
        workingDirectory: Path,
        volumes: List<Volume>,
    ): PossiblePaths {
        val components = Components.parseUnix(rawPath)
        val likelyAbsolute = rawPath.startsWith('/')

        val absoluteInterpretation = Path(workingDirectory.volume, components)
        val relativeComponents = resolveRelative(components, workingDirectory.components)
        val relativeInterpretation = Path(workingDirectory.volume, relativeComponents)
        val volumeExactMatch = volumes.find { it.components?.contains(components) == true }
        val volumeInterpretation =
            volumeExactMatch?.let {
                val stripped = checkNotNull(volumeExactMatch.components).containing(components)
                Path(volumeExactMatch, stripped)
            }

        return if (likelyAbsolute) {
            listOfNotNull(volumeInterpretation, absoluteInterpretation, relativeInterpretation)
        } else {
            listOfNotNull(relativeInterpretation, volumeInterpretation, absoluteInterpretation)
        }
    }

    /**
     * Resolves [components] as a relative path against [workingDirectory], handling `.` and `..`
     * segments.
     */
    private fun resolveRelative(components: Components, workingDirectory: Components): Components {
        var result = workingDirectory
        for (part in components.components) {
            result =
                when (part) {
                    ".." -> result.parent()
                    "." -> result
                    else -> result.child(part)
                }
        }
        return result
    }
}
