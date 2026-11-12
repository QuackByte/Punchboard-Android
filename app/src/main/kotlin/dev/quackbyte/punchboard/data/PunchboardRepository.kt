package dev.quackbyte.punchboard.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import dev.quackbyte.punchboard.model.DataFile
import dev.quackbyte.punchboard.reminders.ReminderEngine
import dev.quackbyte.punchboard.widget.PunchWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.io.IOException
import java.time.Instant

sealed interface SyncStatus {
    data object Off : SyncStatus
    data object Syncing : SyncStatus
    data class Synced(val at: Instant) : SyncStatus
    data class Failed(val message: String) : SyncStatus
}

/**
 * The single owner of the time card. Every edit goes through [edit]: it's
 * saved to the phone straight away, then synced to the shared file (when
 * one is set) with a three-way merge, so edits made on the desktop in the
 * meantime are kept.
 */
class PunchboardRepository private constructor(private val context: Context) {
    private val store = PunchboardStore(context)
    private val baseFile = File(context.filesDir, "sync-base.json")
    private val prefs = context.getSharedPreferences("sync", Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pendingSync: Job? = null

    private val _document = MutableStateFlow<PunchboardDocument?>(null)
    val document: StateFlow<PunchboardDocument?> = _document.asStateFlow()

    private val _loaded = MutableStateFlow(false)
    /** False until the stored card has been read once. */
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    private val _syncStatus = MutableStateFlow<SyncStatus>(SyncStatus.Off)
    val syncStatus: StateFlow<SyncStatus> = _syncStatus.asStateFlow()

    val syncUri: Uri? get() = prefs.getString(KEY_URI, null)?.let(Uri::parse)
    val syncName: String? get() = prefs.getString(KEY_NAME, null)

    init {
        if (syncUri != null) {
            _syncStatus.value = prefs.getLong(KEY_SYNCED_AT, 0).takeIf { it > 0 }
                ?.let { SyncStatus.Synced(Instant.ofEpochMilli(it)) } ?: SyncStatus.Off
        }
        scope.launch { ensureLoaded() }
    }

    /** Reads the stored card once; everything that touches the card awaits it. */
    suspend fun ensureLoaded() {
        if (_loaded.value) return
        mutex.withLock { loadLocked() }
    }

    private fun loadLocked() {
        if (_loaded.value) return
        _document.value = runCatching { store.load() }.onFailure { Log.w(TAG, "Couldn't read the card", it) }.getOrNull()
        _loaded.value = true
    }

    /** Applies an edit and saves it. Returns the new data (or null with no card yet). */
    suspend fun edit(transform: (DataFile) -> DataFile): DataFile? = withContext(Dispatchers.IO) {
        val updated = mutex.withLock {
            loadLocked()
            val current = _document.value ?: return@withLock null
            val next = transform(current.data)
            if (next == current.data) return@withLock current
            val document = current.withData(next)
            store.save(document)
            _document.value = store.load()
            _document.value
        }
        afterChange(sync = true)
        updated?.data
    }

    /** Replaces the whole card (import, restore, start fresh). */
    suspend fun replace(document: PunchboardDocument) = withContext(Dispatchers.IO) {
        mutex.withLock {
            store.save(document)
            _document.value = store.load()
            _loaded.value = true
        }
        afterChange(sync = true)
    }

    /**
     * Starts syncing with a file the user picked. When [adoptFile] is true
     * the file's contents become the card (first-run import); otherwise the
     * phone's card is merged into it.
     */
    suspend fun connect(uri: Uri, adoptFile: Boolean): SyncStatus = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        prefs.edit().putString(KEY_URI, uri.toString()).putString(KEY_NAME, displayName(uri)).apply()
        mutex.withLock {
            val remoteText = readRemote(uri)
            val remote = remoteText?.takeIf { it.isNotBlank() }?.let { (DataFileCodec.decode(it) as? DecodeResult.Ok)?.document }
            if (adoptFile && remote != null) {
                store.save(remote)
                _document.value = store.load()
                _loaded.value = true
                baseFile.writeText(remoteText)
            } else {
                baseFile.delete()
            }
        }
        sync()
    }

    fun disconnect() {
        val uri = syncUri
        prefs.edit().clear().apply()
        baseFile.delete()
        _syncStatus.value = SyncStatus.Off
        if (uri != null) {
            runCatching {
                context.contentResolver.releasePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
        }
    }

    /**
     * Pulls the shared file and pushes ours: merge what changed on either
     * side since the last sync, then write the result to both.
     */
    suspend fun sync(): SyncStatus = withContext(Dispatchers.IO) {
        val uri = syncUri ?: return@withContext SyncStatus.Off.also { _syncStatus.value = it }
        _syncStatus.value = SyncStatus.Syncing
        val status = try {
            mutex.withLock {
                loadLocked()
                syncLocked(uri)
            }
            val now = Instant.now()
            prefs.edit().putLong(KEY_SYNCED_AT, now.toEpochMilli()).apply()
            SyncStatus.Synced(now)
        } catch (error: Exception) {
            Log.w(TAG, "Sync failed", error)
            SyncStatus.Failed(error.message ?: error.javaClass.simpleName)
        }
        _syncStatus.value = status
        if (status is SyncStatus.Synced) afterChange(sync = false)
        status
    }

    private fun syncLocked(uri: Uri) {
        val local = _document.value
        val remoteText = readRemote(uri)
        val baseText = baseFile.takeIf { it.exists() }?.readText()

        val remote = remoteText?.takeIf { it.isNotBlank() }?.let { text ->
            when (val decoded = DataFileCodec.decode(text)) {
                is DecodeResult.Ok -> decoded.document
                is DecodeResult.Error -> throw IOException("The synced file isn't a Punchboard file (${decoded.reason})")
            }
        }

        val merged: PunchboardDocument = when {
            local == null && remote == null -> return
            local == null -> remote!!
            remote == null -> local
            remoteText == baseText -> local
            else -> {
                val base = baseText?.let { (DataFileCodec.decode(it) as? DecodeResult.Ok)?.document }
                val localJson = DataFileCodec.json.parseToJsonElement(DataFileCodec.encode(local)) as JsonObject
                val remoteJson = DataFileCodec.json.parseToJsonElement(remoteText!!) as JsonObject
                val baseJson = base?.let { DataFileCodec.json.parseToJsonElement(baseText) as JsonObject }
                val localIsNewer = local.data.savedAt >= remote.data.savedAt
                val mergedJson = JsonMerge.merge(baseJson, localJson, remoteJson, localIsNewer)
                (DataFileCodec.decode(mergedJson.toString()) as DecodeResult.Ok).document
            }
        }

        if (merged.data != local?.data) {
            store.save(merged)
            _document.value = store.load()
        }
        val outText = DataFileCodec.encode(_document.value!!)
        if (outText != remoteText) writeRemote(uri, outText)
        baseFile.writeText(outText)
    }

    private fun readRemote(uri: Uri): String? =
        context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }

    private fun writeRemote(uri: Uri, text: String) {
        val resolver = context.contentResolver
        val stream = runCatching { resolver.openOutputStream(uri, "wt") }.getOrNull()
            ?: resolver.openOutputStream(uri, "w")
            ?: throw IOException("Can't write to the synced file")
        stream.use { it.write(text.encodeToByteArray()) }
    }

    private fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()

    private fun afterChange(sync: Boolean) {
        ReminderEngine.check(context)
        PunchWidget.updateAll(context)
        if (sync && syncUri != null) {
            pendingSync?.cancel()
            pendingSync = scope.launch {
                delay(SYNC_DEBOUNCE_MS)
                sync()
            }
        }
    }

    /** Writes the card as JSON, for a backup the user saves somewhere. */
    fun exportText(): String? = _document.value?.let(DataFileCodec::encode)

    companion object {
        private const val TAG = "PunchboardRepository"
        private const val KEY_URI = "uri"
        private const val KEY_NAME = "name"
        private const val KEY_SYNCED_AT = "synced_at"
        private const val SYNC_DEBOUNCE_MS = 1500L

        @Volatile private var instance: PunchboardRepository? = null

        fun get(context: Context): PunchboardRepository =
            instance ?: synchronized(this) {
                instance ?: PunchboardRepository(context.applicationContext).also { instance = it }
            }
    }
}
