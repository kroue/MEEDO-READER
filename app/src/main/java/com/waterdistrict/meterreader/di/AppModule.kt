package com.waterdistrict.meterreader.di

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import androidx.room.Room
import androidx.work.WorkManager
import com.waterdistrict.meterreader.data.local.MeterReaderDatabase
import com.waterdistrict.meterreader.data.local.dao.ConsumerDao
import com.waterdistrict.meterreader.data.local.dao.ReadingDao
import com.waterdistrict.meterreader.hardware.bluetooth.BluetoothPrinterManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    // ── Room ──────────────────────────────────────────────────────────────────

    @Provides @Singleton
    fun provideDatabase(@ApplicationContext context: Context): MeterReaderDatabase =
        Room.databaseBuilder(
            context,
            MeterReaderDatabase::class.java,
            MeterReaderDatabase.DATABASE_NAME
        )
        // No destructive fallback. It was previously enabled for every build
        // type, so any schema change would silently wipe unsynced readings on
        // every reader's phone — data that exists nowhere else until it
        // reaches Firestore. A missing migration should fail loudly in
        // testing, not quietly delete a day of field work in production.
        // Every version bump ships a Migration in MeterReaderDatabase.MIGRATIONS.
        .addMigrations(*MeterReaderDatabase.MIGRATIONS)
        .build()

    @Provides @Singleton
    fun provideConsumerDao(db: MeterReaderDatabase): ConsumerDao = db.consumerDao()

    @Provides @Singleton
    fun provideReadingDao(db: MeterReaderDatabase): ReadingDao = db.readingDao()

    // ── Bluetooth ─────────────────────────────────────────────────────────────

    @Provides @Singleton
    fun provideBluetoothAdapter(@ApplicationContext context: Context): BluetoothAdapter {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        return manager.adapter
            ?: throw IllegalStateException("This device does not support Bluetooth.")
    }

    @Provides @Singleton
    fun provideBluetoothPrinterManager(adapter: BluetoothAdapter): BluetoothPrinterManager =
        BluetoothPrinterManager(
            bluetoothAdapter = adapter,
            scope            = CoroutineScope(Dispatchers.IO + SupervisorJob())
        )

    // ── WorkManager ───────────────────────────────────────────────────────────

    @Provides @Singleton
    fun provideWorkManager(@ApplicationContext context: Context): WorkManager =
        WorkManager.getInstance(context)
}
