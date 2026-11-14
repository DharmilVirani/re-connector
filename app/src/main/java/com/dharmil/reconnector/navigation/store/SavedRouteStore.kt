package com.dharmil.reconnector.navigation.store

import android.content.Context
import android.util.AtomicFile
import androidx.annotation.WorkerThread
import com.dharmil.reconnector.navigation.RoutePlan
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets

private val SAVED_ROUTE_ID_PATTERN = Regex("[A-Za-z0-9_-]{1,80}")

data class SavedRouteListing(val routes: List<RoutePlan>, val skippedInvalidCount: Int)

class SavedRouteStore(context: Context) {
    private val directory = File(context.applicationContext.filesDir, ROUTE_DIRECTORY)

    @WorkerThread
    fun save(route: RoutePlan) = synchronized(STORAGE_LOCK) {
        require(route.id.matches(SAVED_ROUTE_ID_PATTERN)) { "Invalid route ID" }
        if (!directory.exists() && !directory.mkdirs()) throw IOException("Could not create saved-route storage")
        val atomicFile = AtomicFile(File(directory, "${route.id}.json"))
        val output = atomicFile.startWrite()
        try {
            val bytes = SavedRouteCodec.encode(route).toByteArray(StandardCharsets.UTF_8)
            if (bytes.size > MAX_ROUTE_BYTES) throw IOException("Saved route exceeds size limit")
            output.write(bytes)
            atomicFile.finishWrite(output)
        } catch (error: Throwable) {
            atomicFile.failWrite(output)
            throw error
        }
    }

    @WorkerThread
    fun getAll(): List<RoutePlan> = loadAll().routes

    @WorkerThread
    fun loadAll(): SavedRouteListing = synchronized(STORAGE_LOCK) {
        if (!directory.exists()) {
            SavedRouteListing(emptyList(), 0)
        } else {
            val files = directory.listFiles { file -> file.isFile && file.extension == ROUTE_EXTENSION }
                ?: throw IOException("Could not read saved-route storage")
            val entries = ArrayList<Pair<String, String>>(files.size)
            var readFailures = 0
            for (file in files.sortedByDescending(File::lastModified)) {
                try {
                    entries += file.nameWithoutExtension to
                        AtomicFile(file).openRead().use { it.readUtf8Limited(MAX_ROUTE_BYTES) }
                } catch (_: Exception) {
                    readFailures++
                }
            }
            val decoded = decodeSavedRouteEntries(entries)
            decoded.copy(skippedInvalidCount = decoded.skippedInvalidCount + readFailures)
        }
    }

    @WorkerThread
    fun delete(routeId: String) = synchronized(STORAGE_LOCK) {
        require(routeId.matches(SAVED_ROUTE_ID_PATTERN)) { "Invalid route ID" }
        AtomicFile(File(directory, "$routeId.$ROUTE_EXTENSION")).delete()
    }

    private fun InputStream.readUtf8Limited(maxBytes: Int): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(IO_BUFFER_BYTES)
        var totalBytes = 0
        while (true) {
            val count = read(buffer, 0, minOf(buffer.size, maxBytes + 1 - totalBytes))
            if (count < 0) break
            totalBytes += count
            if (totalBytes > maxBytes) throw IOException("Saved route exceeds size limit")
            output.write(buffer, 0, count)
        }
        return output.toString(StandardCharsets.UTF_8.name())
    }

    private companion object {
        val STORAGE_LOCK = Any()
        const val ROUTE_DIRECTORY = "saved-routes"
        const val ROUTE_EXTENSION = "json"
        const val MAX_ROUTE_BYTES = 10_000_000
        const val IO_BUFFER_BYTES = 8_192
    }
}

internal fun decodeSavedRouteEntries(entries: Iterable<Pair<String, String>>): SavedRouteListing {
    val routes = ArrayList<RoutePlan>()
    var skipped = 0
    for ((fileId, json) in entries) {
        try {
            require(fileId.matches(SAVED_ROUTE_ID_PATTERN))
            val route = SavedRouteCodec.decode(json)
            require(route.id == fileId)
            routes += route
        } catch (_: Exception) {
            skipped++
        }
    }
    return SavedRouteListing(routes.toList(), skipped)
}
