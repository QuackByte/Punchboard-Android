package dev.quackbyte.punchboard.data

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.core.util.AtomicFile
import java.io.File
import java.io.IOException
import java.time.Instant

/**
 * The phone's copy of the time card: one `punchboard.json` in app storage,
 * in the desktop's format, written atomically.
 */
class PunchboardStore(private val file: File) {

    constructor(context: Context) : this(File(context.filesDir, FILE_NAME))

    private val atomic = AtomicFile(file)

    fun exists(): Boolean = file.exists()

    /** The stored document, or null when nothing has been imported or created yet. */
    fun load(): PunchboardDocument? {
        if (!file.exists()) return null
        val text = atomic.readFully().decodeToString()
        return when (val result = DataFileCodec.decode(text)) {
            is DecodeResult.Ok -> result.document
            is DecodeResult.Error -> throw IOException("Stored data is unreadable: ${result.reason} ${result.detail.orEmpty()}")
        }
    }

    fun save(document: PunchboardDocument, now: Instant = Instant.now()) {
        val stamped = document.withData(document.data.copy(savedAt = now.toString()))
        val bytes = DataFileCodec.encode(stamped).encodeToByteArray()
        val stream = atomic.startWrite()
        try {
            stream.write(bytes)
            atomic.finishWrite(stream)
        } catch (error: IOException) {
            atomic.failWrite(stream)
            throw error
        }
    }

    companion object {
        const val FILE_NAME = "punchboard.json"

        /** Largest file we'll read; years of data are well under 1 MB. */
        private const val MAX_IMPORT_BYTES = 20L * 1024 * 1024

        /**
         * Reads a desktop data file picked with the system file picker
         * (Drive, Dropbox, Downloads…). Doesn't store anything: show the
         * summary, then [save] the document once the user confirms.
         */
        fun readForImport(resolver: ContentResolver, uri: Uri): ImportResult {
            val text = try {
                resolver.openInputStream(uri)?.use { input ->
                    val bytes = input.readNBytesCompat(MAX_IMPORT_BYTES + 1)
                    if (bytes.size > MAX_IMPORT_BYTES) return ImportResult.Failed(ImportFailure.TooLarge)
                    bytes.decodeToString()
                } ?: return ImportResult.Failed(ImportFailure.Unreadable)
            } catch (_: IOException) {
                return ImportResult.Failed(ImportFailure.Unreadable)
            } catch (_: SecurityException) {
                return ImportResult.Failed(ImportFailure.Unreadable)
            }
            return ImportResult.of(text)
        }
    }
}

sealed interface ImportResult {
    data class Ready(val document: PunchboardDocument, val summary: ImportSummary) : ImportResult
    data class Failed(val reason: ImportFailure) : ImportResult

    companion object {
        fun of(text: String): ImportResult = when (val decoded = DataFileCodec.decode(text)) {
            is DecodeResult.Ok -> Ready(decoded.document, ImportSummary.of(decoded.document.data))
            is DecodeResult.Error -> Failed(
                when (decoded.reason) {
                    DecodeError.NotJson, DecodeError.NotPunchboard -> ImportFailure.NotPunchboard
                    DecodeError.UnsupportedVersion -> ImportFailure.NewerVersion
                },
            )
        }
    }
}

enum class ImportFailure { Unreadable, TooLarge, NotPunchboard, NewerVersion }

private fun java.io.InputStream.readNBytesCompat(limit: Long): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    var total = 0L
    while (total < limit) {
        val read = read(buffer, 0, minOf(buffer.size.toLong(), limit - total).toInt())
        if (read < 0) break
        out.write(buffer, 0, read)
        total += read
    }
    return out.toByteArray()
}
