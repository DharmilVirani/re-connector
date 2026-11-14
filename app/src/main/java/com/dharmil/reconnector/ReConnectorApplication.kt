package com.dharmil.reconnector

import android.app.Application
import com.dharmil.reconnector.network.AppHttpClient
import com.dharmil.reconnector.navigation.store.OfflineMapRepository
import org.maplibre.android.MapLibre
import org.maplibre.android.module.http.HttpRequestUtil

class ReConnectorApplication : Application() {
    val offlineMapRepository by lazy { OfflineMapRepository(this) }

    override fun onCreate() {
        super.onCreate()
        MapLibre.getInstance(this)
        HttpRequestUtil.setOkHttpClient(AppHttpClient.instance)
    }
}
