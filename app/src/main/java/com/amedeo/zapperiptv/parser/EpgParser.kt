package com.amedeo.zapperiptv.parser

import android.util.Log
import com.amedeo.zapperiptv.model.EpgProgramme
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class EpgParser {
    companion object {
        private const val TAG = "EpgParser"
        private val XMLTV_FORMATTER =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss Z").withZone(ZoneId.systemDefault())
    }

    fun parse(inputStream: InputStream): Map<String, List<EpgProgramme>> {
        val result = mutableMapOf<String, MutableList<EpgProgramme>>()
        try {
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = false
            val parser = factory.newPullParser()
            parser.setInput(inputStream, null)

            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG && parser.name == "programme") {
                    parseProgramme(parser, result)
                }
                eventType = parser.next()
            }

            result.forEach { (_, list) -> list.sortBy { it.startMillis } }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing EPG XML: ${e.message}", e)
        }
        return result
    }

    private fun parseProgramme(
        parser: XmlPullParser,
        result: MutableMap<String, MutableList<EpgProgramme>>,
    ) {
        val channel = parser.getAttributeValue(null, "channel") ?: return
        val startAttr = parser.getAttributeValue(null, "start") ?: return
        val endAttr = parser.getAttributeValue(null, "stop") ?: return

        val startMillis = parseXmltvTime(startAttr)
        val endMillis = parseXmltvTime(endAttr)
        if (startMillis == null || endMillis == null || endMillis <= startMillis) return

        var title = ""
        var depth = 0
        while (true) {
            val eventType = parser.next()
            if (eventType == XmlPullParser.END_TAG && parser.depth == depth) break
            if (eventType == XmlPullParser.START_TAG && parser.name == "title") {
                title = parser.nextText()
                break
            }
        }
        if (title.isBlank()) return

        result.getOrPut(channel) { mutableListOf() }.add(
            EpgProgramme(
                title = title,
                startMillis = startMillis,
                endMillis = endMillis,
            ),
        )
    }

    private fun parseXmltvTime(value: String): Long? =
        try {
            Instant.from(XMLTV_FORMATTER.parse(value)).toEpochMilli()
        } catch (e: Exception) {
            null
        }
}
