package com.zapperiptv.parser

import android.util.Log
import com.zapperiptv.model.Channel
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.util.regex.Pattern

class M3uParser {
    companion object {
        private const val TAG = "M3uParser"
        private val TVG_NAME_PATTERN = Pattern.compile("tvg-name=\"(.*?)\"")
        private val TVG_LOGO_PATTERN = Pattern.compile("tvg-logo=\"(.*?)\"")
        private val TVG_GROUP_PATTERN = Pattern.compile("group-title=\"(.*?)\"")
        private val TVG_CHNO_PATTERN = Pattern.compile("tvg-chno=\"(.*?)\"")
    }

    private data class ParseState(
        var currentTvgName: String? = null,
        var currentTvgLogo: String? = null,
        var currentGroup: String? = null,
        var currentTvgChNo: Int? = null,
        var currentName: String = "",
    )

    fun parse(
        inputStream: InputStream,
        sourceId: String,
    ): List<Channel> {
        val channels = mutableListOf<Channel>()
        try {
            val reader = BufferedReader(InputStreamReader(inputStream, "UTF-8"))
            var line = reader.readLine()
            if (line != null && line.startsWith("\uFEFF")) line = line.substring(1)

            if (line == null || !line.trim().startsWith("#EXTM3U")) {
                Log.w(TAG, "Not a valid M3U file, missing #EXTM3U header for $sourceId")
                return channels
            }

            val state = ParseState()
            line = reader.readLine()
            while (line != null) {
                processLine(line, state, channels, sourceId)
                line = reader.readLine()
            }
            reader.close()
        } catch (e: IOException) {
            Log.e(TAG, "IO error parsing M3U: ${e.message}", e)
        }

        return channels
    }

    private fun processLine(
        line: String,
        state: ParseState,
        channels: MutableList<Channel>,
        sourceId: String,
    ) {
        val currentLine = line.trim()
        if (currentLine.isEmpty()) return

        if (currentLine.startsWith("#EXTINF:")) {
            parseExtInf(currentLine, state)
        } else if (!currentLine.startsWith("#")) {
            addChannelFromLine(currentLine, state, channels, sourceId)
        }
    }

    private fun parseExtInf(
        line: String,
        state: ParseState,
    ) {
        state.currentTvgName = extractAttribute(TVG_NAME_PATTERN, line)
        state.currentTvgLogo = extractAttribute(TVG_LOGO_PATTERN, line)
        state.currentGroup = extractAttribute(TVG_GROUP_PATTERN, line)
        state.currentTvgChNo = extractAttribute(TVG_CHNO_PATTERN, line)?.toIntOrNull()

        val split = line.split(",")
        state.currentName = if (split.size > 1) split[1].trim() else "Unknown Channel"
    }

    private fun addChannelFromLine(
        line: String,
        state: ParseState,
        channels: MutableList<Channel>,
        sourceId: String,
    ) {
        if (line.isEmpty()) return
        val name = state.currentName.ifEmpty { "Channel ${channels.size + 1}" }
        channels.add(
            Channel(
                name = name,
                streamUrl = line,
                group = state.currentGroup ?: "Other",
                logoUrl = state.currentTvgLogo,
                sourceId = sourceId,
                tvgName = state.currentTvgName,
                tvgChNo = state.currentTvgChNo,
                displayNumber = state.currentTvgChNo ?: (channels.size + 1),
            ),
        )
        state.currentTvgName = null
        state.currentTvgLogo = null
        state.currentGroup = null
        state.currentTvgChNo = null
        state.currentName = ""
    }

    private fun extractAttribute(
        pattern: Pattern,
        line: String,
    ): String? {
        val matcher = pattern.matcher(line)
        return if (matcher.find()) {
            matcher.group(1)
        } else {
            null
        }
    }
}
