package com.waterdistrict.meterreader.hardware.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.IOException
import java.io.OutputStream
import java.util.UUID

// ─────────────────────────────────────────────────────────────────────────────
// ESC/POS Command constants
// Standard commands compatible with most 58 mm / 80 mm thermal printers.
// ─────────────────────────────────────────────────────────────────────────────
object EscPos {
    // Initialise / Reset
    val INIT                = byteArrayOf(0x1B, 0x40)           // ESC @

    // Text alignment
    val ALIGN_LEFT          = byteArrayOf(0x1B, 0x61, 0x00)     // ESC a 0
    val ALIGN_CENTER        = byteArrayOf(0x1B, 0x61, 0x01)     // ESC a 1
    val ALIGN_RIGHT         = byteArrayOf(0x1B, 0x61, 0x02)     // ESC a 2

    // Text style
    val BOLD_ON             = byteArrayOf(0x1B, 0x45, 0x01)     // ESC E 1
    val BOLD_OFF            = byteArrayOf(0x1B, 0x45, 0x00)     // ESC E 0
    val UNDERLINE_ON        = byteArrayOf(0x1B, 0x2D, 0x01)     // ESC - 1
    val UNDERLINE_OFF       = byteArrayOf(0x1B, 0x2D, 0x00)     // ESC - 0

    // Font size (GS !) — double width + double height = 0x11
    val FONT_NORMAL         = byteArrayOf(0x1D, 0x21, 0x00)
    val FONT_DOUBLE_HEIGHT  = byteArrayOf(0x1D, 0x21, 0x01)
    val FONT_DOUBLE_WIDTH   = byteArrayOf(0x1D, 0x21, 0x10)
    val FONT_LARGE          = byteArrayOf(0x1D, 0x21, 0x11)     // 2× both

    // Line feeds & cuts
    fun lineFeed(lines: Int = 1): ByteArray = ByteArray(lines) { 0x0A }
    val CUT_PAPER           = byteArrayOf(0x1D, 0x56, 0x42, 0x00) // GS V 66 0

    // QR Code (GS ( k) — Model 2, error correction L, cell size 6
    fun qrCode(data: String): ByteArray {
        val bytes       = data.toByteArray(Charsets.UTF_8)
        val storeLen    = bytes.size + 3
        val pL          = (storeLen % 256).toByte()
        val pH          = (storeLen / 256).toByte()
        return byteArrayOf(
            // Model: GS ( k 4 0 49 65 50 0
            0x1D, 0x28, 0x6B, 0x04, 0x00, 0x31, 0x41, 0x32, 0x00,
            // Error correction level L: GS ( k 3 0 49 69 48
            0x1D, 0x28, 0x6B, 0x03, 0x00, 0x31, 0x45, 0x30,
            // Cell size 6: GS ( k 3 0 49 67 6
            0x1D, 0x28, 0x6B, 0x03, 0x00, 0x31, 0x43, 0x06,
            // Store data: GS ( k pL pH 49 80 48 <data>
            0x1D, 0x28, 0x6B, pL,   pH,   0x31, 0x50, 0x30,
            *bytes,
            // Print QR: GS ( k 3 0 49 81 48
            0x1D, 0x28, 0x6B, 0x03, 0x00, 0x31, 0x51, 0x30
        )
    }

    // Divider — 32 dashes for 58 mm paper  (adjust to 48 for 80 mm)
    val DIVIDER_THIN        = "--------------------------------\n".toByteArray()
    val DIVIDER_THICK       = "================================\n".toByteArray()
}

// ─────────────────────────────────────────────────────────────────────────────
// Connection state
// ─────────────────────────────────────────────────────────────────────────────
sealed class PrinterState {
    object Idle        : PrinterState()
    object Connecting  : PrinterState()
    object Connected   : PrinterState()
    object Printing    : PrinterState()
    data class Error(val message: String) : PrinterState()
    object Disconnected : PrinterState()
}

// ─────────────────────────────────────────────────────────────────────────────
// BluetoothPrinterManager
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Manages the lifecycle of a Bluetooth SPP (Serial Port Profile) connection
 * to an ESC/POS thermal printer.
 *
 * ### Critical implementation notes
 *
 * 1. **`autoConnect = false`** — Always use `false` on [BluetoothDevice.createRfcommSocketToServiceRecord].
 *    Using `true` causes Android to use a "background" connection mode that
 *    lingers in a "sticky" connecting state and is notoriously unreliable
 *    with thermal printers. `false` triggers an immediate connection attempt.
 *
 * 2. **UUID** — Standard SPP UUID (00001101-...) works for the vast majority
 *    of generic ESC/POS printers that advertise the Serial Port Profile.
 *    If a printer requires a proprietary UUID, fetch it via
 *    `device.uuids[0].uuid` after pairing.
 *
 * 3. **Thread safety** — All I/O happens on [Dispatchers.IO]. The [printerState]
 *    Flow is updated on the same coroutine and collected safely in Compose.
 *
 * 4. **Release** — Call [disconnect] when the user leaves the print screen to
 *    avoid holding the RFCOMM channel open.
 */
class BluetoothPrinterManager(
    private val bluetoothAdapter: BluetoothAdapter,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
    companion object {
        private const val TAG = "BluetoothPrinterManager"

        /** Standard Serial Port Profile UUID — used by generic ESC/POS printers */
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        private const val CONNECT_TIMEOUT_MS = 12_000L
        private const val WRITE_CHUNK_SIZE   = 512    // bytes per write call
    }

    // Exposed to UI via ViewModel
    private val _printerState = MutableStateFlow<PrinterState>(PrinterState.Idle)
    val printerState: StateFlow<PrinterState> = _printerState.asStateFlow()

    private var socket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns all already-paired Bluetooth devices — the user selects their
     * printer from this list.
     *
     * BLUETOOTH_CONNECT permission must be granted (Android 12+) before calling.
     */
    @SuppressLint("MissingPermission")
    fun getPairedDevices(): Set<BluetoothDevice> =
        bluetoothAdapter.bondedDevices ?: emptySet()

    /**
     * Open an RFCOMM socket to [device] and connect.
     *
     * Internally uses `createRfcommSocketToServiceRecord` (not the fallback
     * reflection trick) because modern firmware universally supports SDP
     * service discovery with the SPP UUID.
     */
    fun connect(device: BluetoothDevice) {
        if (_printerState.value == PrinterState.Connected ||
            _printerState.value == PrinterState.Connecting
        ) return

        scope.launch { connectInternal(device) }
    }

    @SuppressLint("MissingPermission")
    private suspend fun connectInternal(device: BluetoothDevice): Boolean {
        _printerState.emit(PrinterState.Connecting)
        return try {
            // ⚠️  autoConnect = false — see KDoc note above
            val newSocket = device.createRfcommSocketToServiceRecord(SPP_UUID)

            // Cancel discovery — it slows connection and can interfere
            bluetoothAdapter.cancelDiscovery()

            withTimeout(CONNECT_TIMEOUT_MS) { newSocket.connect() }

            socket       = newSocket
            outputStream = newSocket.outputStream

            Log.i(TAG, "Connected to ${device.name}")
            _printerState.emit(PrinterState.Connected)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Connection failed: ${e.message}", e)
            safeCloseSocket()
            _printerState.emit(PrinterState.Error("Connection failed: ${e.message}"))
            false
        }
    }

    /**
     * Connects to the paired printer automatically if not already connected —
     * callers of [print] never had to pick a device themselves, so [print]
     * needs to make that connection happen on their behalf rather than just
     * failing with "not connected" the way it silently did before.
     *
     * Field devices are expected to have exactly one Bluetooth device paired
     * (the receipt printer) via Android's Bluetooth settings; if more than
     * one is paired there's currently no way to disambiguate, so this
     * surfaces a clear error naming them instead of guessing.
     */
    @SuppressLint("MissingPermission")
    private suspend fun ensureConnected(): Boolean {
        if (_printerState.value == PrinterState.Connected) return true

        val paired = try {
            getPairedDevices()
        } catch (e: SecurityException) {
            Log.e(TAG, "Missing Bluetooth permission", e)
            _printerState.emit(
                PrinterState.Error("Bluetooth permission not granted. Allow it and try again.")
            )
            return false
        }

        return when {
            paired.isEmpty() -> {
                _printerState.emit(
                    PrinterState.Error("No paired printer found. Pair your printer in Android Bluetooth settings first.")
                )
                false
            }
            paired.size == 1 -> connectInternal(paired.first())
            else -> {
                val names = paired.joinToString(", ") { it.name ?: it.address }
                _printerState.emit(
                    PrinterState.Error("Multiple paired Bluetooth devices found ($names). Unpair the extra one so only the printer remains.")
                )
                false
            }
        }
    }

    /**
     * Write raw bytes to the printer output stream.
     * Data is chunked to avoid overwhelming the Bluetooth buffer.
     *
     * @throws IOException propagated if the stream write fails mid-print.
     */
    suspend fun write(data: ByteArray) = withContext(Dispatchers.IO) {
        val stream = outputStream ?: throw IOException("Printer not connected")
        data.toList().chunked(WRITE_CHUNK_SIZE).forEach { chunk ->
            stream.write(chunk.toByteArray())
            stream.flush()
        }
    }

    /**
     * High-level print function. Emits [PrinterState.Printing] during the
     * operation and restores [PrinterState.Connected] on completion.
     *
     * @param buildReceipt  Lambda that constructs and returns the full receipt
     *                      byte array using [ReceiptBuilder].
     * @return `true` if printing succeeded, `false` otherwise.
     */
    suspend fun print(buildReceipt: ReceiptBuilder.() -> Unit): Boolean {
        if (!ensureConnected()) return false

        return try {
            _printerState.emit(PrinterState.Printing)
            val receipt = ReceiptBuilder().apply(buildReceipt).build()
            write(receipt)
            _printerState.emit(PrinterState.Connected)
            Log.i(TAG, "Print job complete (${receipt.size} bytes)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Print failed: ${e.message}", e)
            _printerState.emit(PrinterState.Error("Print failed: ${e.message}"))
            false
        }
    }

    /** Close the socket and reset state. Always safe to call. */
    fun disconnect() {
        scope.launch {
            safeCloseSocket()
            _printerState.emit(PrinterState.Disconnected)
            Log.i(TAG, "Disconnected from printer.")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Internal helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun safeCloseSocket() {
        try {
            outputStream?.close()
            socket?.close()
        } catch (_: IOException) { /* best effort */ } finally {
            outputStream = null
            socket       = null
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// ReceiptBuilder — fluent builder for ESC/POS byte sequences
// ─────────────────────────────────────────────────────────────────────────────

/**
 * DSL-style builder that accumulates ESC/POS byte arrays and concatenates
 * them into a single [ByteArray] ready for transmission.
 *
 * Example usage:
 * ```kotlin
 * val bytes = ReceiptBuilder()
 *     .init()
 *     .alignCenter()
 *     .boldOn()
 *     .textLine("MY WATER DISTRICT")
 *     .boldOff()
 *     .dividerThin()
 *     .leftRightText("Account:", "2024-001234")
 *     .qrCode("2024-001234")
 *     .cutPaper()
 *     .build()
 * ```
 */
class ReceiptBuilder {
    private val buffer = mutableListOf<ByteArray>()

    fun raw(bytes: ByteArray)       = apply { buffer.add(bytes) }
    fun init()                      = apply { buffer.add(EscPos.INIT) }
    fun alignLeft()                 = apply { buffer.add(EscPos.ALIGN_LEFT) }
    fun alignCenter()               = apply { buffer.add(EscPos.ALIGN_CENTER) }
    fun alignRight()                = apply { buffer.add(EscPos.ALIGN_RIGHT) }
    fun boldOn()                    = apply { buffer.add(EscPos.BOLD_ON) }
    fun boldOff()                   = apply { buffer.add(EscPos.BOLD_OFF) }
    fun underlineOn()               = apply { buffer.add(EscPos.UNDERLINE_ON) }
    fun underlineOff()              = apply { buffer.add(EscPos.UNDERLINE_OFF) }
    fun fontNormal()                = apply { buffer.add(EscPos.FONT_NORMAL) }
    fun fontLarge()                 = apply { buffer.add(EscPos.FONT_LARGE) }
    fun fontDoubleHeight()          = apply { buffer.add(EscPos.FONT_DOUBLE_HEIGHT) }
    fun dividerThin()               = apply { buffer.add(EscPos.DIVIDER_THIN) }
    fun dividerThick()              = apply { buffer.add(EscPos.DIVIDER_THICK) }
    fun feed(lines: Int = 1)        = apply { buffer.add(EscPos.lineFeed(lines)) }
    fun cutPaper()                  = apply { buffer.add(EscPos.CUT_PAPER) }
    fun qrCode(data: String)        = apply { buffer.add(EscPos.qrCode(data)) }

    /** Append a simple text line followed by a newline character. */
    fun textLine(text: String, encoding: String = "UTF-8") = apply {
        buffer.add("$text\n".toByteArray(charset(encoding)))
    }

    /**
     * Print left-aligned [label] and right-aligned [value] on the same line,
     * padded to [lineWidth] characters (default 32 for 58 mm paper).
     *
     * Example output: `Account No:         2024-001234`
     */
    fun leftRightText(
        label: String,
        value: String,
        lineWidth: Int = 32
    ) = apply {
        val totalLen = label.length + value.length
        val padding  = maxOf(1, lineWidth - totalLen)
        val line     = label + " ".repeat(padding) + value + "\n"
        buffer.add(line.toByteArray())
    }

    /** Produce the final concatenated [ByteArray]. */
    fun build(): ByteArray {
        val totalSize = buffer.sumOf { it.size }
        val result    = ByteArray(totalSize)
        var position  = 0
        buffer.forEach { chunk ->
            chunk.copyInto(result, position)
            position += chunk.size
        }
        return result
    }
}
