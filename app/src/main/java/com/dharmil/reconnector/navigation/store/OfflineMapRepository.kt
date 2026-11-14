package com.dharmil.reconnector.navigation.store

import android.content.Context
import androidx.annotation.MainThread
import com.dharmil.reconnector.navigation.RoutePlan
import com.dharmil.reconnector.map.MapStyleConfig
import org.json.JSONObject
import org.maplibre.android.offline.OfflineGeometryRegionDefinition
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.geojson.Geometry
import java.nio.charset.StandardCharsets

data class OfflineMapStatus(
    val completedTiles: Long = 0,
    val completedBytes: Long = 0,
    val completedResources: Long = 0,
    val requiredResources: Long? = null,
    val complete: Boolean = false,
    val downloading: Boolean = false,
    val issue: String? = null,
)

class OfflineMapRepository(context: Context) {
    private val manager = OfflineManager.getInstance(context.applicationContext)
    private val listeners = HashMap<String, (OfflineMapStatus) -> Unit>()
    private val observers = HashMap<Long, OfflineRegion.OfflineRegionObserver>()
    private val issues = HashMap<Long, String>()
    private val pendingDownloads = HashSet<String>()

    @MainThread
    fun observe(routeId: String, listener: (OfflineMapStatus) -> Unit): () -> Unit {
        require(routeId.matches(ROUTE_ID_PATTERN))
        listeners[routeId] = listener
        listener(OfflineMapStatus())
        try {
            manager.listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
            override fun onList(offlineRegions: Array<OfflineRegion>?) {
                try {
                    val region = offlineRegions.orEmpty().firstOrNull { it.routeId() == routeId }
                    if (region == null) return
                    observeRegion(routeId, region)
                    region.getStatus(statusCallback(routeId, region))
                } catch (failure: Exception) {
                    emit(routeId, OfflineMapStatus(issue = MAP_STATUS_ERROR))
                }
            }

            override fun onError(error: String) {
                emit(routeId, OfflineMapStatus(issue = MAP_DATABASE_ERROR))
            }
            })
        } catch (failure: Exception) {
            emit(routeId, OfflineMapStatus(issue = MAP_DATABASE_ERROR))
        }
        return {
            if (listeners[routeId] === listener) listeners.remove(routeId)
        }
    }

    @MainThread
    fun download(route: RoutePlan, corridor: Geometry) {
        require(route.id.matches(ROUTE_ID_PATTERN))
        if (!pendingDownloads.add(route.id)) return
        try {
            manager.listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
                override fun onList(offlineRegions: Array<OfflineRegion>?) {
                    try {
                        val existing = offlineRegions.orEmpty().firstOrNull { it.routeId() == route.id }
                        if (existing != null) {
                            pendingDownloads.remove(route.id)
                            if (issues[existing.id] != MAP_BUDGET_ERROR && issues[existing.id] != MAP_DEVICE_LIMIT_ERROR) {
                                issues.remove(existing.id)
                            }
                            observeRegion(route.id, existing)
                            existing.setDownloadState(OfflineRegion.STATE_ACTIVE)
                            existing.getStatus(statusCallback(route.id, existing))
                            return
                        }
                        val definition = OfflineGeometryRegionDefinition(
                            MapStyleConfig.URL,
                            corridor,
                            MIN_ZOOM,
                            MAX_ZOOM,
                            PIXEL_RATIO,
                        )
                        manager.createOfflineRegion(
                            definition,
                            metadata(route.id),
                            object : OfflineManager.CreateOfflineRegionCallback {
                                override fun onCreate(offlineRegion: OfflineRegion) {
                                    pendingDownloads.remove(route.id)
                                    try {
                                        observeRegion(route.id, offlineRegion)
                                        offlineRegion.setDownloadState(OfflineRegion.STATE_ACTIVE)
                                        offlineRegion.getStatus(statusCallback(route.id, offlineRegion))
                                    } catch (failure: Exception) {
                                        emit(route.id, OfflineMapStatus(issue = MAP_CREATE_ERROR))
                                    }
                                }

                                override fun onError(error: String) = failDownload(route.id, MAP_CREATE_ERROR)
                            },
                        )
                    } catch (failure: Exception) {
                        failDownload(route.id, MAP_CREATE_ERROR)
                    }
                }

                override fun onError(error: String) = failDownload(route.id, MAP_DATABASE_ERROR)
            })
        } catch (failure: Exception) {
            failDownload(route.id, MAP_DATABASE_ERROR)
        }
    }

    @MainThread
    fun pause(routeId: String) {
        require(routeId.matches(ROUTE_ID_PATTERN))
        try {
            manager.listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
                override fun onList(offlineRegions: Array<OfflineRegion>?) {
                    try {
                        val region = offlineRegions.orEmpty().firstOrNull { it.routeId() == routeId } ?: return
                        region.setDownloadState(OfflineRegion.STATE_INACTIVE)
                        region.getStatus(statusCallback(routeId, region))
                    } catch (failure: Exception) {
                        emit(routeId, OfflineMapStatus(issue = MAP_STATUS_ERROR))
                    }
                }

                override fun onError(error: String) = emit(routeId, OfflineMapStatus(issue = MAP_DATABASE_ERROR))
            })
        } catch (failure: Exception) {
            emit(routeId, OfflineMapStatus(issue = MAP_DATABASE_ERROR))
        }
    }

    private fun failDownload(routeId: String, message: String) {
        pendingDownloads.remove(routeId)
        emit(routeId, OfflineMapStatus(issue = message))
    }

    private fun observeRegion(routeId: String, region: OfflineRegion) {
        if (observers.containsKey(region.id)) return
        val observer = object : OfflineRegion.OfflineRegionObserver {
            override fun onStatusChanged(status: OfflineRegionStatus) = reportStatus(routeId, region, status)

            override fun onError(error: OfflineRegionError) {
                issues[region.id] = MAP_DOWNLOAD_ERROR
                val issue = try {
                    region.setDownloadState(OfflineRegion.STATE_INACTIVE)
                    MAP_DOWNLOAD_ERROR
                } catch (failure: Exception) {
                    MAP_STOP_ERROR
                }
                emit(routeId, OfflineMapStatus(issue = issue))
            }

            override fun mapboxTileCountLimitExceeded(limit: Long) {
                issues[region.id] = MAP_DEVICE_LIMIT_ERROR
                try {
                    region.setDownloadState(OfflineRegion.STATE_INACTIVE)
                    region.getStatus(statusCallback(routeId, region))
                } catch (failure: Exception) {
                    emit(routeId, OfflineMapStatus(issue = MAP_STOP_ERROR))
                }
            }
        }
        region.setObserver(observer)
        observers[region.id] = observer
    }

    private fun statusCallback(routeId: String, region: OfflineRegion) = object : OfflineRegion.OfflineRegionStatusCallback {
        override fun onStatus(status: OfflineRegionStatus?) {
            if (status == null) emit(routeId, OfflineMapStatus(issue = MAP_STATUS_ERROR))
            else reportStatus(routeId, region, status)
        }

        override fun onError(error: String?) = emit(routeId, OfflineMapStatus(issue = MAP_STATUS_ERROR))
    }

    private fun reportStatus(routeId: String, region: OfflineRegion, status: OfflineRegionStatus) {
        try {
            if (status.downloadState == OfflineRegion.STATE_ACTIVE &&
                OfflineDownloadBudget.exceeded(status.completedTileCount, status.completedResourceSize)
            ) {
                issues[region.id] = MAP_BUDGET_ERROR
                region.setDownloadState(OfflineRegion.STATE_INACTIVE)
            } else if (status.isComplete && status.downloadState == OfflineRegion.STATE_ACTIVE) {
                region.setDownloadState(OfflineRegion.STATE_INACTIVE)
            }
        } catch (failure: Exception) {
            runCatching { region.setDownloadState(OfflineRegion.STATE_INACTIVE) }
            emit(routeId, status.toMapStatus(MAP_STOP_ERROR))
            return
        }
        emit(routeId, status.toMapStatus(issues[region.id]))
    }

    private fun OfflineRegionStatus.toMapStatus(issue: String? = null) = OfflineMapStatus(
        completedTiles = completedTileCount,
        completedBytes = completedResourceSize,
        completedResources = completedResourceCount,
        requiredResources = requiredResourceCount.takeIf { isRequiredResourceCountPrecise },
        complete = isComplete,
        downloading = downloadState == OfflineRegion.STATE_ACTIVE,
        issue = issue,
    )

    private fun OfflineRegion.routeId(): String? = runCatching {
        if (metadata.size > MAX_METADATA_BYTES) return null
        val data = JSONObject(String(metadata, StandardCharsets.UTF_8))
        if (data.optInt(METADATA_VERSION, -1) != METADATA_VERSION_NUMBER) return null
        data.optString(METADATA_ROUTE_ID).takeIf { it.matches(ROUTE_ID_PATTERN) }
    }.getOrNull()

    private fun metadata(routeId: String) = JSONObject()
        .put(METADATA_VERSION, METADATA_VERSION_NUMBER)
        .put(METADATA_ROUTE_ID, routeId)
        .toString()
        .toByteArray(StandardCharsets.UTF_8)

    private fun emit(routeId: String, status: OfflineMapStatus) {
        listeners[routeId]?.invoke(status)
    }

    private companion object {
        const val MIN_ZOOM = 8.0
        const val MAX_ZOOM = 15.0
        const val PIXEL_RATIO = 1f
        const val METADATA_VERSION = "version"
        const val METADATA_VERSION_NUMBER = 1
        const val METADATA_ROUTE_ID = "route_id"
        const val MAX_METADATA_BYTES = 512
        const val MAP_DATABASE_ERROR = "Could not read saved offline maps"
        const val MAP_CREATE_ERROR = "Could not create the offline map"
        const val MAP_DOWNLOAD_ERROR = "Map provider reported a download error"
        const val MAP_STATUS_ERROR = "Could not read offline map status"
        const val MAP_STOP_ERROR = "Could not stop the offline map download; check it before retrying"
        const val MAP_BUDGET_ERROR = "Stopped at the offline map size limit; this route may need a smaller area"
        const val MAP_DEVICE_LIMIT_ERROR = "Stopped at the device's offline tile limit"
        val ROUTE_ID_PATTERN = Regex("[A-Za-z0-9_-]{1,80}")
    }
}
