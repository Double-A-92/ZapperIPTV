package com.zapperiptv.parser

import android.util.Log
import com.zapperiptv.model.Channel
import java.io.BufferedReader
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

    fun parse(inputStream: InputStream, sourceId: String): List<Channel> {
        val channels = mutableListOf<Channel>()
        
        try {
            val reader = BufferedReader(InputStreamReader(inputStream, "UTF-8"))
            var line: String? = reader.readLine()
            
            // Handle UTF-8 BOM
            if (line != null && line.startsWith("\uFEFF")) {
                line = line.substring(1)
            }
            
            if (line == null || !line.trim().startsWith("#EXTM3U")) {
                Log.w(TAG, "Not a valid M3U file, missing #EXTM3U header for $sourceId")
                return channels
            }

            var currentTvgName: String? = null
            var currentTvgLogo: String? = null
            var currentGroup: String? = null
            var currentTvgChNo: Int? = null
            var currentName = ""

            line = reader.readLine()
            while (line != null) {
                val currentLine = line.trim()
                if (currentLine.isEmpty()) {
                    line = reader.readLine()
                    continue
                }

                if (currentLine.startsWith("#EXTINF:")) {
                    currentTvgName = extractAttribute(TVG_NAME_PATTERN, currentLine)
                    currentTvgLogo = extractAttribute(TVG_LOGO_PATTERN, currentLine)
                    currentGroup = extractAttribute(TVG_GROUP_PATTERN, currentLine)
                    val chNoStr = extractAttribute(TVG_CHNO_PATTERN, currentLine)
                    currentTvgChNo = chNoStr?.toIntOrNull()

                    val split = currentLine.split(",")
                    currentName = if (split.size > 1) split[1].trim() else "Unknown Channel"
                } else if (!currentLine.startsWith("#")) {
                    if (currentLine.isNotEmpty()) {
                        if (currentName.isEmpty()) {
                            // Some M3Us have the URL immediately after the #EXTINF without a name in the tag
                            currentName = "Channel ${channels.size + 1}"
                        }
                        channels.add(
                            Channel(
                                name = currentName,
                                streamUrl = currentLine,
                                group = currentGroup ?: "Other",
                                logoUrl = currentTvgLogo,
                                sourceId = sourceId,
                                tvgName = currentTvgName,
                                tvgChNo = currentTvgChNo,
                                displayNumber = currentTvgChNo ?: (channels.size + 1)
                            )
                        )
                    }
                    // Reset properties for next channel
                    currentTvgName = null
                    currentTvgLogo = null
                    currentGroup = null
                    currentTvgChNo = null
                    currentName = ""
                }
                line = reader.readLine()
            }
            reader.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing M3U: ${e.message}", e)
        }
        
        return channels
    }

    private fun extractAttribute(pattern: Pattern, line: String): String? {
        val matcher = pattern.matcher(line)
        return if (matcher.find()) {
            matcher.group(1)
        } else {
            null
        }
    }
}
