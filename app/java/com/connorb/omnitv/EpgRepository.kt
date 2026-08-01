package com.connorb.omnitv

import android.content.Context
import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Properties
import java.util.concurrent.Executors
import java.io.PushbackInputStream
import java.io.Reader
import java.util.zip.GZIPInputStream

object EpgRepository {

    private const val TAG = "EPG"

    private const val CACHE_TTL_MS =
        6L * 60L * 60L * 1000L

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000
    private const val MAX_REDIRECTS = 5

    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 11) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Mobile Safari/537.36"

    private val executor =
        Executors.newSingleThreadExecutor()

    private val lock = Any()

    @Volatile
    private var cachedData: EpgData = EpgData.EMPTY

    @Volatile
    private var cachedUrl: String? = null

    @Volatile
    private var cachedAt: Long = 0L

    private var loadingUrl: String? = null

    private data class PendingRequest(
        val url: String,
        val callback: (Result<EpgData>) -> Unit
    )

    private val pendingRequests =
        mutableListOf<PendingRequest>()

    private data class CacheFiles(
        val xml: File,
        val temporaryXml: File,
        val metadata: File,
        val temporaryMetadata: File
    )

    private data class CacheMetadata(
        val sourceUrl: String,
        val downloadedAt: Long,
        val etag: String?,
        val lastModified: String?
    )

    private data class DownloadResult(
        val file: File,
        val metadata: CacheMetadata,
        val downloaded: Boolean
    )

    fun load(
        context: Context,
        url: String,
        callback: (Result<EpgData>) -> Unit
    ) {
        val applicationContext =
            context.applicationContext

        val normalizedUrl =
            url.trim()

        if (normalizedUrl.isEmpty()) {
            callback(
                Result.failure(
                    IllegalArgumentException(
                        "La URL del EPG está vacía"
                    )
                )
            )
            return
        }

        var memoryResult: EpgData? = null
        var shouldStartLoading = false

        synchronized(lock) {
            val memoryCacheIsValid =
                cachedUrl == normalizedUrl &&
                    cachedData.programsById.isNotEmpty() &&
                    System.currentTimeMillis() - cachedAt <
                    CACHE_TTL_MS

            if (memoryCacheIsValid) {
                memoryResult = cachedData
            } else {
                pendingRequests.add(
                    PendingRequest(
                        url = normalizedUrl,
                        callback = callback
                    )
                )

                if (loadingUrl == null) {
                    loadingUrl = normalizedUrl
                    shouldStartLoading = true
                }
            }
        }

        memoryResult?.let { data ->
            Log.d(
                TAG,
                "EPG recuperado de memoria: " +
                    "${data.programsById.size} IDs, " +
                    "${data.nameToId.size} nombres, " +
                    "${data.programsById.values.sumOf { it.size }} programas"
            )

            callback(Result.success(data))
            return
        }

        if (shouldStartLoading) {
            startLoad(
                applicationContext,
                normalizedUrl
            )
        } else {
            Log.d(
                TAG,
                "Carga EPG ya en curso; solicitud en espera"
            )
        }
    }

    private fun bomAwareUtf8Reader(
        input: InputStream
    ): Reader {
        val pushback = PushbackInputStream(input, 3)
        val bom = ByteArray(3)
        val count = pushback.read(bom)

        val hasUtf8Bom =
            count == 3 &&
                    bom[0] == 0xEF.toByte() &&
                    bom[1] == 0xBB.toByte() &&
                    bom[2] == 0xBF.toByte()

        if (!hasUtf8Bom && count > 0) {
            pushback.unread(bom, 0, count)
        }

        return InputStreamReader(
            pushback,
            Charsets.UTF_8
        )
    }

    private fun startLoad(
        context: Context,
        url: String
    ) {
        executor.execute {
            val result = runCatching {
                val files = cacheFiles(context, url)
                ensureParentDirectory(files.xml)

                val metadata =
                    readMetadata(files.metadata)

                val now =
                    System.currentTimeMillis()

                val diskCacheIsFresh =
                    files.xml.isFile &&
                        files.xml.length() > 0L &&
                        metadata?.sourceUrl == url &&
                        now - metadata.downloadedAt <
                        CACHE_TTL_MS

                val source = if (diskCacheIsFresh) {
                    Log.d(
                        TAG,
                        "Usando EPG reciente almacenado en disco: " +
                            "${files.xml.length()} bytes"
                    )

                    DownloadResult(
                        file = files.xml,
                        metadata = metadata!!,
                        downloaded = false
                    )
                } else {
                    refreshDiskCache(
                        url = url,
                        files = files,
                        previousMetadata = metadata
                    )
                }

                val parseStartedAt =
                    android.os.SystemClock.elapsedRealtime()

                val parsed = openPossiblyCompressed(
                    source.file
                ).use { input ->
                    bomAwareUtf8Reader(input).use { reader ->
                        XmltvParser.parse(reader)
                    }
                }

                FileInputStream(source.file).use { input ->
                    val bytes = ByteArray(8)
                    val count = input.read(bytes)

                    Log.d(
                        TAG,
                        "Primeros bytes EPG: " +
                                bytes.take(count).joinToString(" ") {
                                    "%02X".format(it.toInt() and 0xFF)
                                }
                    )
                }

                require(
                    parsed.programsById.isNotEmpty()
                ) {
                    "El XMLTV no contiene programas reconocibles"
                }

                val parseElapsed =
                    android.os.SystemClock.elapsedRealtime() -
                        parseStartedAt

                Log.d(
                    TAG,
                    "EPG integrado desde " +
                        if (source.downloaded) {
                            "descarga"
                        } else {
                            "disco"
                        } +
                        " en ${parseElapsed} ms"
                )

                synchronized(lock) {
                    cachedData = parsed
                    cachedUrl = url
                    cachedAt =
                        System.currentTimeMillis()
                }

                parsed
            }

            finishCurrentLoad(
                context = context,
                url = url,
                result = result
            )
        }
    }

    private fun finishCurrentLoad(
        context: Context,
        url: String,
        result: Result<EpgData>
    ) {
        val callbacks:
            List<(Result<EpgData>) -> Unit>

        var nextUrl: String? = null

        synchronized(lock) {
            callbacks = pendingRequests
                .filter { it.url == url }
                .map { it.callback }

            pendingRequests.removeAll {
                it.url == url
            }

            loadingUrl = null

            nextUrl = pendingRequests
                .firstOrNull()
                ?.url

            if (nextUrl != null) {
                loadingUrl = nextUrl
            }
        }

        callbacks.forEach { callback ->
            try {
                callback(result)
            } catch (e: Exception) {
                Log.e(
                    TAG,
                    "Error notificando el resultado EPG",
                    e
                )
            }
        }

        nextUrl?.let {
            startLoad(context, it)
        }
    }

    private fun refreshDiskCache(
        url: String,
        files: CacheFiles,
        previousMetadata: CacheMetadata?
    ): DownloadResult {
        deleteQuietly(files.temporaryXml)
        deleteQuietly(files.temporaryMetadata)

        return try {
            val response = downloadToTemporaryFile(
                url = url,
                temporaryFile = files.temporaryXml,
                previousMetadata = previousMetadata
            )

            if (response.notModified) {
                require(
                    files.xml.isFile &&
                        files.xml.length() > 0L
                ) {
                    "El servidor respondió 304, pero no hay caché local"
                }

                val refreshedMetadata =
                    CacheMetadata(
                        sourceUrl = url,
                        downloadedAt =
                            System.currentTimeMillis(),
                        etag =
                            response.etag
                                ?: previousMetadata?.etag,
                        lastModified =
                            response.lastModified
                                ?: previousMetadata?.lastModified
                    )

                writeMetadataAtomically(
                    refreshedMetadata,
                    files
                )

                Log.d(
                    TAG,
                    "EPG no modificado; se reutiliza la caché local"
                )

                DownloadResult(
                    file = files.xml,
                    metadata = refreshedMetadata,
                    downloaded = false
                )
            } else {
                validateDownloadedXml(
                    files.temporaryXml
                )

                replaceFileAtomically(
                    source = files.temporaryXml,
                    target = files.xml
                )

                val newMetadata =
                    CacheMetadata(
                        sourceUrl = url,
                        downloadedAt =
                            System.currentTimeMillis(),
                        etag = response.etag,
                        lastModified =
                            response.lastModified
                    )

                writeMetadataAtomically(
                    newMetadata,
                    files
                )

                Log.d(
                    TAG,
                    "EPG descargado y almacenado en disco: " +
                        "${files.xml.length()} bytes"
                )

                DownloadResult(
                    file = files.xml,
                    metadata = newMetadata,
                    downloaded = true
                )
            }
        } catch (e: Exception) {
            deleteQuietly(files.temporaryXml)
            deleteQuietly(files.temporaryMetadata)

            val staleCacheAvailable =
                files.xml.isFile &&
                    files.xml.length() > 0L &&
                    previousMetadata?.sourceUrl == url

            if (staleCacheAvailable) {
                Log.w(
                    TAG,
                    "Falló la actualización; se usará la caché antigua",
                    e
                )

                DownloadResult(
                    file = files.xml,
                    metadata = previousMetadata!!,
                    downloaded = false
                )
            } else {
                throw e
            }
        }
    }

    private data class HttpDownloadResponse(
        val notModified: Boolean,
        val etag: String?,
        val lastModified: String?
    )

    private fun openDecodedNetworkStream(
        rawInput: InputStream,
        contentEncoding: String?
    ): InputStream {
        val pushback = PushbackInputStream(rawInput, 2)

        val first = pushback.read()
        val second = pushback.read()

        if (second != -1) {
            pushback.unread(second)
        }
        if (first != -1) {
            pushback.unread(first)
        }

        val hasGzipSignature =
            first == 0x1F && second == 0x8B

        val declaresGzip =
            contentEncoding?.contains(
                "gzip",
                ignoreCase = true
            ) == true

        return if (hasGzipSignature || declaresGzip) {
            GZIPInputStream(
                pushback,
                64 * 1024
            )
        } else {
            pushback
        }
    }

    private fun downloadToTemporaryFile(
        url: String,
        temporaryFile: File,
        previousMetadata: CacheMetadata?
    ): HttpDownloadResponse {
        var currentUrl = url
        var redirects = 0

        while (true) {
            val connection =
                URL(currentUrl)
                    .openConnection() as HttpURLConnection

            connection.connectTimeout =
                CONNECT_TIMEOUT_MS

            connection.readTimeout =
                READ_TIMEOUT_MS

            connection.instanceFollowRedirects =
                false

            connection.setRequestProperty(
                "User-Agent",
                USER_AGENT
            )

            connection.setRequestProperty(
                "Accept-Encoding",
                "gzip"
            )

            previousMetadata
                ?.etag
                ?.takeIf { it.isNotBlank() }
                ?.let {
                    connection.setRequestProperty(
                        "If-None-Match",
                        it
                    )
                }

            previousMetadata
                ?.lastModified
                ?.takeIf { it.isNotBlank() }
                ?.let {
                    connection.setRequestProperty(
                        "If-Modified-Since",
                        it
                    )
                }

            try {
                val code =
                    connection.responseCode

                if (code in 300..399 && code != 304) {
                    val location =
                        connection.getHeaderField(
                            "Location"
                        )
                            ?: throw java.io.IOException(
                                "Redirección HTTP sin Location"
                            )

                    if (redirects >= MAX_REDIRECTS) {
                        throw java.io.IOException(
                            "Demasiadas redirecciones HTTP"
                        )
                    }

                    currentUrl =
                        URL(
                            URL(currentUrl),
                            location
                        ).toString()

                    redirects++
                    continue
                }

                val etag =
                    connection.getHeaderField("ETag")

                val lastModified =
                    connection.getHeaderField(
                        "Last-Modified"
                    )

                if (code == HttpURLConnection.HTTP_NOT_MODIFIED) {
                    return HttpDownloadResponse(
                        notModified = true,
                        etag = etag,
                        lastModified = lastModified
                    )
                }

                if (code !in 200..299) {
                    val errorText =
                        connection.errorStream
                            ?.bufferedReader()
                            ?.use {
                                it.readText().take(1000)
                            }
                            .orEmpty()

                    throw java.io.IOException(
                        "HTTP $code: $errorText"
                    )
                }

                val rawInput = BufferedInputStream(
                    connection.inputStream,
                    64 * 1024
                )

                openDecodedNetworkStream(
                    rawInput = rawInput,
                    contentEncoding = connection.contentEncoding
                ).use { input ->
                    BufferedOutputStream(
                        FileOutputStream(temporaryFile, false),
                        64 * 1024
                    ).use { output ->
                        input.copyTo(
                            output,
                            bufferSize = 64 * 1024
                        )
                        output.flush()
                    }
                }

                return HttpDownloadResponse(
                    notModified = false,
                    etag = etag,
                    lastModified = lastModified
                )
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun validateDownloadedXml(
        file: File
    ) {
        require(file.isFile && file.length() > 0L) {
            "La descarga EPG está vacía"
        }

        openPossiblyCompressed(file).use { input ->
            bomAwareUtf8Reader(input).use { reader ->
                val buffer = CharArray(8192)
                val count = reader.read(buffer)

                require(count > 0) {
                    "No se pudo leer la descarga EPG"
                }

                val beginning = String(
                    buffer,
                    0,
                    count
                ).trimStart()

                require(
                    beginning.startsWith("<?xml", ignoreCase = true) ||
                            beginning.startsWith("<tv", ignoreCase = true) ||
                            beginning.startsWith("<!DOCTYPE", ignoreCase = true)
                ) {
                    val safeBeginning = beginning
                        .take(80)
                        .replace("\n", " ")
                        .replace("\r", " ")

                    "La respuesta no parece XMLTV. Inicio: $safeBeginning"
                }
            }
        }
    }

    private fun cacheFiles(
        context: Context,
        url: String
    ): CacheFiles {
        val directory =
            File(
                context.cacheDir,
                "epg"
            )

        val key =
            sha256(url)

        return CacheFiles(
            xml = File(
                directory,
                "$key.xml"
            ),
            temporaryXml = File(
                directory,
                "$key.xml.tmp"
            ),
            metadata = File(
                directory,
                "$key.properties"
            ),
            temporaryMetadata = File(
                directory,
                "$key.properties.tmp"
            )
        )
    }

    private fun readMetadata(
        file: File
    ): CacheMetadata? {
        if (!file.isFile) return null

        return try {
            val properties =
                Properties()

            FileInputStream(file).use {
                properties.load(it)
            }

            val sourceUrl =
                properties.getProperty(
                    "sourceUrl"
                )
                    ?.takeIf { it.isNotBlank() }
                    ?: return null

            val downloadedAt =
                properties.getProperty(
                    "downloadedAt"
                )
                    ?.toLongOrNull()
                    ?: return null

            CacheMetadata(
                sourceUrl = sourceUrl,
                downloadedAt = downloadedAt,
                etag =
                    properties
                        .getProperty("etag")
                        ?.takeIf {
                            it.isNotBlank()
                        },
                lastModified =
                    properties
                        .getProperty(
                            "lastModified"
                        )
                        ?.takeIf {
                            it.isNotBlank()
                        }
            )
        } catch (e: Exception) {
            Log.w(
                TAG,
                "No se pudieron leer los metadatos EPG",
                e
            )

            null
        }
    }

    private fun writeMetadataAtomically(
        metadata: CacheMetadata,
        files: CacheFiles
    ) {
        val properties =
            Properties().apply {
                setProperty(
                    "sourceUrl",
                    metadata.sourceUrl
                )

                setProperty(
                    "downloadedAt",
                    metadata.downloadedAt.toString()
                )

                metadata.etag?.let {
                    setProperty("etag", it)
                }

                metadata.lastModified?.let {
                    setProperty(
                        "lastModified",
                        it
                    )
                }
            }

        FileOutputStream(
            files.temporaryMetadata,
            false
        ).use {
            properties.store(
                it,
                "EPG cache metadata"
            )

            it.fd.sync()
        }

        replaceFileAtomically(
            source =
                files.temporaryMetadata,
            target =
                files.metadata
        )
    }

    private fun replaceFileAtomically(
        source: File,
        target: File
    ) {
        ensureParentDirectory(target)

        if (
            android.os.Build.VERSION.SDK_INT >=
            android.os.Build.VERSION_CODES.O
        ) {
            java.nio.file.Files.move(
                source.toPath(),
                target.toPath(),
                java.nio.file.StandardCopyOption
                    .REPLACE_EXISTING,
                java.nio.file.StandardCopyOption
                    .ATOMIC_MOVE
            )
        } else {
            if (target.exists() && !target.delete()) {
                throw java.io.IOException(
                    "No se pudo reemplazar ${target.name}"
                )
            }

            if (!source.renameTo(target)) {
                source.copyTo(
                    target,
                    overwrite = true
                )

                if (!source.delete()) {
                    Log.w(
                        TAG,
                        "No se pudo borrar ${source.name}"
                    )
                }
            }
        }
    }

    private fun openPossiblyCompressed(
        file: File
    ): InputStream {
        val buffered =
            BufferedInputStream(
                FileInputStream(file)
            )

        buffered.mark(2)

        val first = buffered.read()
        val second = buffered.read()

        buffered.reset()

        return if (
            first == 0x1f &&
            second == 0x8b
        ) {
            java.util.zip.GZIPInputStream(
                buffered
            )
        } else {
            buffered
        }
    }

    private fun ensureParentDirectory(
        file: File
    ) {
        val parent =
            file.parentFile
                ?: throw java.io.IOException(
                    "El archivo no tiene directorio padre"
                )

        if (!parent.exists() && !parent.mkdirs()) {
            throw java.io.IOException(
                "No se pudo crear ${parent.absolutePath}"
            )
        }
    }

    private fun sha256(
        value: String
    ): String {
        val bytes =
            MessageDigest
                .getInstance("SHA-256")
                .digest(
                    value.toByteArray(
                        Charsets.UTF_8
                    )
                )

        return bytes.joinToString("") {
            "%02x".format(it)
        }
    }

    private fun deleteQuietly(
        file: File
    ) {
        if (file.exists() && !file.delete()) {
            Log.w(
                TAG,
                "No se pudo borrar ${file.absolutePath}"
            )
        }
    }

    fun clearMemoryCache() {
        synchronized(lock) {
            cachedData = EpgData.EMPTY
            cachedUrl = null
            cachedAt = 0L
        }

        Log.d(
            TAG,
            "Caché EPG en memoria eliminada"
        )
    }

    fun clearAllCaches(
        context: Context
    ) {
        clearMemoryCache()

        val directory =
            File(
                context.applicationContext.cacheDir,
                "epg"
            )

        if (
            directory.exists() &&
            !directory.deleteRecursively()
        ) {
            Log.w(
                TAG,
                "No se pudo eliminar toda la caché EPG"
            )
        }
    }
}
