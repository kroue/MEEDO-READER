package com.waterdistrict.meterreader

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * Application class.
 *
 * ### WorkManager + Hilt
 * To use @AssistedInject workers, WorkManager must use [HiltWorkerFactory].
 * We implement [Configuration.Provider] here and supply our own [Configuration]
 * so that the default WorkManager initialisation (which would use the standard
 * factory and miss Hilt-injected workers) is bypassed.
 *
 * The corresponding `<meta-data>` removal for the default initialiser is in
 * AndroidManifest.xml.
 */
@HiltAndroidApp
class MeterReaderApplication : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()
}
