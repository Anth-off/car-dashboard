package fr.cockpit.dashboard.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

internal data class TileKey(val zoom: Int, val x: Int, val y: Int) {
    val filename: String get() = "${zoom}_${x}_${y}"
}

/** Only tiles currently visible on a surface are requested; there is no offline download. */
internal class MapTileStore(context: Context, private val onChanged: () -> Unit) {
    private data class MemoryTile(val bitmap: Bitmap, val expiresAt: Long, val transient: Boolean)
    private data class Metadata(val expiresAt: Long, val etag: String?, val lastModified: String?)

    private val cacheDirectory = File(context.applicationContext.cacheDir, "osm_visible_tiles").apply { mkdirs() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val slots = Semaphore(4)
    private val memory = object : LruCache<TileKey, MemoryTile>(12 * 1024 * 1024) {
        override fun sizeOf(key: TileKey, value: MemoryTile): Int = value.bitmap.allocationByteCount
    }
    private val jobs = ConcurrentHashMap<TileKey, kotlinx.coroutines.Job>()
    private val connections = ConcurrentHashMap<TileKey, HttpURLConnection>()
    private val retryAfter = ConcurrentHashMap<TileKey, Long>()
    @Volatile private var visible: Set<TileKey> = emptySet()
    @Volatile private var closed = false

    fun setVisible(keys: Set<TileKey>) {
        visible = keys
        jobs.keys.filter { it !in keys }.forEach { key ->
            jobs.remove(key)?.cancel()
            connections.remove(key)?.disconnect()
        }
        synchronized(memory) {
            memory.snapshot().filter { (key, tile) -> tile.transient && key !in keys }
                .keys.forEach { memory.remove(it) }
        }
        retryAfter.keys.filter { it !in keys }.forEach { retryAfter.remove(it) }
    }

    fun tile(key: TileKey): Bitmap? {
        val now = System.currentTimeMillis()
        synchronized(memory) {
            memory.get(key)?.let { cached ->
                // A no-store image can stay on the current display, but is never reused off-screen.
                if (cached.transient || cached.expiresAt > now) return cached.bitmap
                memory.remove(key)
            }
        }
        if (!closed && key in visible && (retryAfter[key] ?: 0L) <= now && !jobs.containsKey(key)) {
            val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
                val owner = currentCoroutineContext()[kotlinx.coroutines.Job]
                try {
                    slots.withPermit {
                        currentCoroutineContext().ensureActive()
                        if (key !in visible) return@withPermit
                        val loaded = load(key)
                        currentCoroutineContext().ensureActive()
                        if (key in visible && loaded != null) {
                            synchronized(memory) { memory.put(key, loaded) }
                            retryAfter.remove(key)
                        } else if (loaded == null && key in visible) {
                            retryAfter[key] = maxOf(retryAfter[key] ?: 0L, System.currentTimeMillis() + RETRY_DELAY)
                        }
                    }
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    if (key in visible && !closed) retryAfter[key] = System.currentTimeMillis() + RETRY_DELAY
                } finally {
                    if (owner != null) jobs.remove(key, owner)
                    withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) {
                        if (!closed) onChanged()
                    }
                }
            }
            if (jobs.putIfAbsent(key, job) == null) job.start() else job.cancel()
        }
        return null
    }

    fun hasVisibleFailure(): Boolean = visible.any { (retryAfter[it] ?: 0L) > System.currentTimeMillis() }

    private suspend fun load(key: TileKey): MemoryTile? {
        val imageFile = File(cacheDirectory, "${key.filename}.png")
        val metadataFile = File(cacheDirectory, "${key.filename}.json")
        val metadata = readMetadata(metadataFile)
        if (imageFile.isFile && metadata != null && metadata.expiresAt > System.currentTimeMillis()) {
            BitmapFactory.decodeFile(imageFile.absolutePath)?.let { bitmap ->
                imageFile.setLastModified(System.currentTimeMillis())
                return MemoryTile(bitmap, metadata.expiresAt, false)
            }
        }
        currentCoroutineContext().ensureActive()
        val connection = (URL("https://tile.openstreetmap.org/${key.zoom}/${key.x}/${key.y}.png")
            .openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            // Stable application identifier, not a library's generic User-Agent.
            setRequestProperty("User-Agent", "CockpitAndroid/0.1 (fr.cockpit.dashboard; Android personal navigation)")
            setRequestProperty("Accept", "image/png")
            // Do not send no-cache headers: response freshness is respected by our disk cache.
            if (imageFile.isFile && metadata != null) {
                metadata.etag?.let { setRequestProperty("If-None-Match", it) }
                metadata.lastModified?.let { setRequestProperty("If-Modified-Since", it) }
            }
        }
        connections[key] = connection
        try {
            val status = connection.responseCode
            currentCoroutineContext().ensureActive()
            val cacheControl = connection.getHeaderField("Cache-Control").orEmpty()
            val noStore = cacheControl.split(',').any { it.trim().equals("no-store", true) }
            val expiresAt = expiry(connection, cacheControl)
            val refreshedMetadata = Metadata(
                expiresAt,
                connection.getHeaderField("ETag") ?: metadata?.etag,
                connection.getHeaderField("Last-Modified") ?: metadata?.lastModified,
            )
            if (status == HttpURLConnection.HTTP_NOT_MODIFIED && imageFile.isFile) {
                val bitmap = BitmapFactory.decodeFile(imageFile.absolutePath) ?: return null
                if (noStore) {
                    imageFile.delete()
                    metadataFile.delete()
                } else {
                    writeMetadata(metadataFile, refreshedMetadata)
                    imageFile.setLastModified(System.currentTimeMillis())
                }
                return MemoryTile(bitmap, expiresAt, noStore || expiresAt <= System.currentTimeMillis())
            }
            if (status != HttpURLConnection.HTTP_OK) {
                if (status == 429 || status == HttpURLConnection.HTTP_UNAVAILABLE) {
                    val seconds = connection.getHeaderField("Retry-After")?.toLongOrNull()
                        ?.coerceIn(0L, 365L * 24 * 60 * 60)
                    val retryAt = seconds?.let { System.currentTimeMillis() + it * 1_000L }
                        ?: connection.getHeaderFieldDate("Retry-After", 0L)
                    retryAfter[key] = maxOf(retryAt, System.currentTimeMillis() + 60_000L)
                }
                return null
            }
            if (connection.contentLengthLong > MAX_TILE_BYTES) return null
            val bytes = connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8_192)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > MAX_TILE_BYTES) return null
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth != 256 || bounds.outHeight != 256) return null
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            if (noStore) {
                imageFile.delete()
                metadataFile.delete()
            } else {
                synchronized(DISK_LOCK) {
                    atomicWrite(imageFile, bytes)
                    writeMetadata(metadataFile, refreshedMetadata)
                    trimDiskCache()
                }
            }
            return MemoryTile(bitmap, expiresAt, noStore || expiresAt <= System.currentTimeMillis())
        } finally {
            connections.remove(key, connection)
            connection.disconnect()
        }
    }

    private fun expiry(connection: HttpURLConnection, cacheControl: String): Long {
        val now = System.currentTimeMillis()
        val directives = cacheControl.lowercase().split(',').map(String::trim)
        if (directives.any { it == "no-cache" || it.startsWith("no-cache=") }) return now
        val maxAge = directives.firstOrNull { it.startsWith("max-age=") }
            ?.substringAfter('=')?.trim('"')?.toLongOrNull()?.coerceIn(0L, 365L * 24 * 60 * 60)
        if (maxAge != null) {
            val age = connection.getHeaderFieldLong("Age", 0L).coerceAtLeast(0L)
            return now + (maxAge - age).coerceAtLeast(0L) * 1_000L
        }
        val expires = connection.getHeaderFieldDate("Expires", -1L)
        if (expires >= 0L) return expires
        // OSM policy: cache at least seven days if no usable freshness header was supplied.
        return now + DEFAULT_TTL
    }

    private fun readMetadata(file: File): Metadata? = runCatching {
        val json = JSONObject(file.readText())
        Metadata(json.getLong("expiresAt"), json.optString("etag").takeIf(String::isNotEmpty),
            json.optString("lastModified").takeIf(String::isNotEmpty))
    }.getOrNull()

    private fun writeMetadata(file: File, metadata: Metadata) {
        val json = JSONObject().put("expiresAt", metadata.expiresAt)
        metadata.etag?.let { json.put("etag", it) }
        metadata.lastModified?.let { json.put("lastModified", it) }
        atomicWrite(file, json.toString().toByteArray())
    }

    private fun atomicWrite(file: File, bytes: ByteArray) {
        val temporary = File.createTempFile(file.name, ".tmp", cacheDirectory)
        try {
            temporary.writeBytes(bytes)
            if (!temporary.renameTo(file)) throw java.io.IOException("Unable to cache map tile")
        } finally {
            temporary.delete()
        }
    }

    private fun trimDiskCache() {
        val files = cacheDirectory.listFiles().orEmpty()
        var total = files.sumOf(File::length)
        if (total <= MAX_DISK_BYTES) return
        files.filter { it.extension == "png" }.sortedBy(File::lastModified).forEach { image ->
            if (total <= MAX_DISK_BYTES) return
            val metadata = File(cacheDirectory, image.nameWithoutExtension + ".json")
            total -= image.length() + metadata.length()
            image.delete()
            metadata.delete()
        }
    }

    fun close() {
        closed = true
        visible = emptySet()
        connections.values.forEach(HttpURLConnection::disconnect)
        connections.clear()
        scope.cancel()
        synchronized(memory) { memory.evictAll() }
    }

    companion object {
        private val DISK_LOCK = Any()
        private const val DEFAULT_TTL = 7L * 24 * 60 * 60 * 1_000
        private const val RETRY_DELAY = 30_000L
        private const val MAX_TILE_BYTES = 1024 * 1024
        private const val MAX_DISK_BYTES = 40L * 1024 * 1024
    }
}
