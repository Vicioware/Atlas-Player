package com.connorb.omnitv

import android.os.SystemClock
import android.util.Log
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.Reader
import java.io.StringReader
import java.text.Normalizer
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

data class EpgData(
    val programsById: Map<String, List<EpgProgram>>,
    val nameToId: Map<String, String>
) {
    companion object {
        val EMPTY =
            EpgData(
                emptyMap(),
                emptyMap()
            )
    }
}

private val COMBINING_MARKS_REGEX =
    Regex("\\p{Mn}+")

private val WHITESPACE_REGEX =
    Regex("\\s+")

fun normalizeKey(
    text: String
): String {
    val noAccents =
        Normalizer
            .normalize(
                text,
                Normalizer.Form.NFD
            )
            .replace(
                COMBINING_MARKS_REGEX,
                ""
            )

    return noAccents
        .lowercase(Locale.ROOT)
        .trim()
        .replace(
            WHITESPACE_REGEX,
            " "
        )
}

private class BomStrippingReader(
    private val source: Reader
) : Reader() {

    private var firstRead = true

    override fun read(
        buffer: CharArray,
        offset: Int,
        length: Int
    ): Int {
        if (length == 0) return 0

        if (!firstRead) {
            return source.read(buffer, offset, length)
        }

        firstRead = false

        val first = source.read()

        if (first == -1) {
            return -1
        }

        if (first.toChar() == '\uFEFF') {
            return source.read(buffer, offset, length)
        }

        buffer[offset] = first.toChar()

        if (length == 1) {
            return 1
        }

        val remainder = source.read(
            buffer,
            offset + 1,
            length - 1
        )

        return if (remainder == -1) {
            1
        } else {
            remainder + 1
        }
    }

    override fun close() {
        source.close()
    }
}

object XmltvParser {

    private const val TAG = "EPG"

    private const val PAST_RETENTION_MS =
        6L * 60L * 60L * 1000L

    private val formatWithZoneSeconds =
        SimpleDateFormat(
            "yyyyMMddHHmmss Z",
            Locale.US
        ).apply {
            isLenient = false
        }

    private val formatWithZoneMinutes =
        SimpleDateFormat(
            "yyyyMMddHHmm Z",
            Locale.US
        ).apply {
            isLenient = false
        }

    private val formatWithoutZoneSeconds =
        SimpleDateFormat(
            "yyyyMMddHHmmss",
            Locale.US
        ).apply {
            isLenient = false
            timeZone =
                TimeZone.getDefault()
        }

    private val formatWithoutZoneMinutes =
        SimpleDateFormat(
            "yyyyMMddHHmm",
            Locale.US
        ).apply {
            isLenient = false
            timeZone =
                TimeZone.getDefault()
        }

    fun parse(
        xml: String
    ): EpgData {
        return parse(
            StringReader(
                xml.removePrefix("\uFEFF")
            )
        )
    }

    fun parse(
        reader: Reader
    ): EpgData {
        val startedAt =
            SystemClock.elapsedRealtime()

        val programs =
            HashMap<
                String,
                MutableList<EpgProgram>
                >(768)

        val nameToId =
            HashMap<String, String>(768)

        val cutoff =
            System.currentTimeMillis() -
                PAST_RETENTION_MS

        var totalProgrammes = 0
        var discardedProgrammes = 0
        var invalidProgrammes = 0

        return try {
            val parser =
                Xml.newPullParser().apply {
                    setFeature(
                        XmlPullParser
                            .FEATURE_PROCESS_NAMESPACES,
                        false
                    )

                    setInput(
                        BomStrippingReader(reader)
                    )
                }

            var event =
                parser.eventType

            while (
                event !=
                XmlPullParser.END_DOCUMENT
            ) {
                if (
                    event ==
                    XmlPullParser.START_TAG
                ) {
                    when (parser.name) {
                        "channel" -> {
                            readChannel(
                                parser,
                                nameToId
                            )
                        }

                        "programme" -> {
                            totalProgrammes++

                            val channelId =
                                parser
                                    .getAttributeValue(
                                        null,
                                        "channel"
                                    )
                                    ?.trim()

                            val start =
                                parseDate(
                                    parser
                                        .getAttributeValue(
                                            null,
                                            "start"
                                        )
                                )

                            val stop =
                                parseDate(
                                    parser
                                        .getAttributeValue(
                                            null,
                                            "stop"
                                        )
                                )

                            if (
                                stop > 0L &&
                                stop < cutoff
                            ) {
                                discardedProgrammes++
                                skipCurrentElement(
                                    parser
                                )
                            } else if (
                                channelId.isNullOrEmpty()
                            ) {
                                invalidProgrammes++
                                skipCurrentElement(
                                    parser
                                )
                            } else {
                                val program =
                                    readUsefulProgramme(
                                        parser =
                                            parser,
                                        channelId =
                                            channelId,
                                        start =
                                            start,
                                        stop =
                                            stop
                                    )

                                if (program != null) {
                                    programs
                                        .getOrPut(
                                            channelId
                                        ) {
                                            ArrayList()
                                        }
                                        .add(program)
                                } else {
                                    invalidProgrammes++
                                }
                            }
                        }
                    }
                }

                event =
                    parser.next()
            }

            programs.values.forEach {
                channelPrograms ->

                if (
                    !isSortedByStart(
                        channelPrograms
                    )
                ) {
                    channelPrograms.sortBy {
                        it.startMillis
                    }
                }
            }

            val elapsed =
                SystemClock
                    .elapsedRealtime() -
                    startedAt

            val programCount =
                programs.values
                    .sumOf { it.size }

            Log.d(
                TAG,
                "XMLTV correcto en ${elapsed} ms: " +
                    "${programs.size} IDs, " +
                    "${nameToId.size} nombres, " +
                    "$programCount programas útiles, " +
                    "$discardedProgrammes antiguos descartados, " +
                    "$invalidProgrammes inválidos de " +
                    "$totalProgrammes totales"
            )

            EpgData(
                programsById = programs,
                nameToId = nameToId
            )
        } catch (e: Exception) {
            val elapsed =
                SystemClock
                    .elapsedRealtime() -
                    startedAt

            Log.e(
                TAG,
                "Error parseando XMLTV después de " +
                    "${elapsed} ms: " +
                    "${e.javaClass.name}: " +
                    "${e.message}",
                e
            )

            EpgData.EMPTY
        }
    }

    private fun readChannel(
        parser: XmlPullParser,
        nameToId:
            MutableMap<String, String>
    ) {
        val channelId =
            parser
                .getAttributeValue(
                    null,
                    "id"
                )
                ?.trim()
                ?.takeIf {
                    it.isNotEmpty()
                }

        val outerDepth =
            parser.depth

        while (true) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> {
                    if (
                        channelId != null &&
                        parser.name ==
                        "display-name"
                    ) {
                        val displayName =
                            safeNextText(
                                parser
                            ).trim()

                        if (
                            displayName
                                .isNotEmpty()
                        ) {
                            nameToId
                                .putIfAbsent(
                                    normalizeKey(
                                        displayName
                                    ),
                                    channelId
                                )
                        }
                    } else {
                        skipCurrentElement(
                            parser
                        )
                    }
                }

                XmlPullParser.END_TAG -> {
                    if (
                        parser.depth ==
                        outerDepth &&
                        parser.name ==
                        "channel"
                    ) {
                        return
                    }
                }

                XmlPullParser.END_DOCUMENT ->
                    return
            }
        }
    }

    private fun readUsefulProgramme(
        parser: XmlPullParser,
        channelId: String,
        start: Long,
        stop: Long
    ): EpgProgram? {
        val outerDepth =
            parser.depth

        var title: String? = null
        var description: String? = null

        while (true) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "title" -> {
                            if (title == null) {
                                title =
                                    safeNextText(
                                        parser
                                    ).trim()
                            } else {
                                skipCurrentElement(
                                    parser
                                )
                            }
                        }

                        "desc" -> {
                            if (
                                description == null
                            ) {
                                description =
                                    safeNextText(
                                        parser
                                    )
                                        .trim()
                                        .takeIf {
                                            it.isNotEmpty()
                                        }
                            } else {
                                skipCurrentElement(
                                    parser
                                )
                            }
                        }

                        else -> {
                            skipCurrentElement(
                                parser
                            )
                        }
                    }
                }

                XmlPullParser.END_TAG -> {
                    if (
                        parser.depth ==
                        outerDepth &&
                        parser.name ==
                        "programme"
                    ) {
                        break
                    }
                }

                XmlPullParser.END_DOCUMENT ->
                    break
            }
        }

        val validTitle =
            title
                ?.takeIf {
                    it.isNotBlank()
                }
                ?: return null

        return EpgProgram(
            channelId = channelId,
            startMillis = start,
            stopMillis = stop,
            title = validTitle,
            description = description
        )
    }

    private fun skipCurrentElement(
        parser: XmlPullParser
    ) {
        if (
            parser.eventType !=
            XmlPullParser.START_TAG
        ) {
            return
        }

        var level = 1

        while (level > 0) {
            when (parser.next()) {
                XmlPullParser.START_TAG ->
                    level++

                XmlPullParser.END_TAG ->
                    level--

                XmlPullParser.END_DOCUMENT ->
                    return
            }
        }
    }

    private fun safeNextText(
        parser: XmlPullParser
    ): String {
        return try {
            parser.nextText()
        } catch (_: Exception) {
            skipCurrentElement(parser)
            ""
        }
    }

    private fun isSortedByStart(
        list: List<EpgProgram>
    ): Boolean {
        for (
            index in 1 until list.size
        ) {
            if (
                list[index - 1]
                    .startMillis >
                list[index]
                    .startMillis
            ) {
                return false
            }
        }

        return true
    }

    private fun parseDate(
        value: String?
    ): Long {
        if (value.isNullOrBlank()) {
            return 0L
        }

        val clean =
            value.trim()

        val firstSpace =
            clean.indexOf(' ')

        val datePart: String
        val zonePart: String?

        if (firstSpace >= 0) {
            datePart =
                clean.substring(
                    0,
                    firstSpace
                )

            zonePart =
                clean
                    .substring(
                        firstSpace + 1
                    )
                    .trim()
                    .takeIf {
                        it.isNotEmpty()
                    }
        } else {
            datePart = clean
            zonePart = null
        }

        val format:
            SimpleDateFormat

        val input: String

        when {
            datePart.length >= 14 &&
                zonePart != null -> {
                format =
                    formatWithZoneSeconds

                input =
                    datePart.take(14) +
                        " " +
                        zonePart
            }

            datePart.length >= 12 &&
                zonePart != null -> {
                format =
                    formatWithZoneMinutes

                input =
                    datePart.take(12) +
                        " " +
                        zonePart
            }

            datePart.length >= 14 -> {
                format =
                    formatWithoutZoneSeconds

                input =
                    datePart.take(14)
            }

            datePart.length >= 12 -> {
                format =
                    formatWithoutZoneMinutes

                input =
                    datePart.take(12)
            }

            else -> return 0L
        }

        val position =
            ParsePosition(0)

        val date =
            format.parse(
                input,
                position
            )

        return if (
            date != null &&
            position.index ==
            input.length
        ) {
            date.time
        } else {
            0L
        }
    }
}
