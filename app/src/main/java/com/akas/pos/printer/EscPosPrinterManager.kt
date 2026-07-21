package com.akas.pos.printer

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.util.Log
import com.akas.pos.config.PosConfigLoader
import java.io.IOException
import java.nio.charset.Charset
import java.util.UUID
import android.bluetooth.BluetoothSocket

class EscPosPrinterManager(private val context: Context) {
    private val preferences = context.getSharedPreferences("printer", Context.MODE_PRIVATE)
    private val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
    private val defaultPaperWidth = PosConfigLoader.load(context).defaultPaperWidthMm

    @SuppressLint("MissingPermission")
    fun pairedPrinters(): List<BluetoothDevice> =
        adapter?.bondedDevices?.sortedBy { it.name.orEmpty() } ?: emptyList()

    fun savePrinter(address: String) {
        preferences.edit().putString(KEY_ADDRESS, address).apply()
    }

    fun savedPrinterAddress(): String? = preferences.getString(KEY_ADDRESS, null)

    fun savePaperWidth(widthMm: Int) {
        require(widthMm == 58 || widthMm == 80) { "Ukuran kertas harus 58 atau 80 mm" }
        preferences.edit().putInt(KEY_PAPER_WIDTH, widthMm).apply()
    }

    fun paperWidthMm(): Int = preferences.getInt(KEY_PAPER_WIDTH, defaultPaperWidth)

    fun paperColumns(): Int = if (paperWidthMm() == 80) 48 else 32

    @SuppressLint("MissingPermission")
    fun savedPrinterName(): String? {
        val address = savedPrinterAddress() ?: return null
        return runCatching { adapter?.getRemoteDevice(address)?.name }.getOrNull()
    }

    @SuppressLint("MissingPermission")
    @Throws(IOException::class)
    fun testConnection(address: String) {
        val bluetoothAdapter = adapter ?: error("Bluetooth tidak tersedia di perangkat ini")
        if (!bluetoothAdapter.isEnabled) error("Bluetooth belum aktif")
        val socket = bluetoothAdapter.getRemoteDevice(address)
            .createRfcommSocketToServiceRecord(SPP_UUID)
        try {
            bluetoothAdapter.cancelDiscovery()
            socket.connect()
        } finally {
            runCatching { socket.close() }
        }
    }

    @SuppressLint("MissingPermission")
    @Throws(IOException::class)
    fun print(text: String) {
        sendToPrinter(
            INIT + ALIGN_LEFT + text.toByteArray(PRINTER_CHARSET) +
                byteArrayOf(0x0A, 0x0A, 0x0A) + PARTIAL_CUT
        )
    }

    @SuppressLint("MissingPermission")
    @Throws(IOException::class)
    fun printReceipt(json: String) {
        sendToPrinter(ProfessionalReceiptBuilder.build(context, json, paperWidthMm()))
    }

    @SuppressLint("MissingPermission")
    @Throws(IOException::class)
    private fun sendToPrinter(payload: ByteArray) {
        val bluetoothAdapter = adapter ?: error("Bluetooth tidak tersedia di perangkat ini")
        if (!bluetoothAdapter.isEnabled) error("Bluetooth belum aktif")

        val address = savedPrinterAddress() ?: error("Printer belum dipilih")
        val device = bluetoothAdapter.getRemoteDevice(address)
        bluetoothAdapter.cancelDiscovery()
        val socket = connectWithRetry(device)

        try {
            socket.outputStream.use { output ->
                Log.i(TAG, "Mengirim ${payload.size} byte ke ${device.name ?: address}")
                payload.asList().chunked(WRITE_CHUNK_SIZE).forEach { chunk ->
                    output.write(chunk.toByteArray())
                    output.flush()
                    Thread.sleep(WRITE_CHUNK_DELAY_MS)
                }
                output.flush()
                Thread.sleep(AFTER_WRITE_DELAY_MS)
                Log.i(TAG, "Payload printer selesai dikirim")
            }
        } finally {
            runCatching { socket.close() }
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectWithRetry(device: BluetoothDevice): BluetoothSocket {
        var lastError: Throwable? = null
        repeat(CONNECTION_ATTEMPTS) { attempt ->
            val socket = if (attempt == 0) {
                device.createRfcommSocketToServiceRecord(SPP_UUID)
            } else {
                device.createInsecureRfcommSocketToServiceRecord(SPP_UUID)
            }
            try {
                Log.i(TAG, "Menyambungkan printer, percobaan ${attempt + 1}")
                socket.connect()
                return socket
            } catch (error: Throwable) {
                lastError = error
                Log.w(TAG, "Koneksi printer gagal pada percobaan ${attempt + 1}", error)
                runCatching { socket.close() }
                if (attempt + 1 < CONNECTION_ATTEMPTS) Thread.sleep(CONNECTION_RETRY_DELAY_MS)
            }
        }
        throw IOException("Gagal terhubung ke printer: ${lastError?.message}", lastError)
    }

    companion object {
        private const val KEY_ADDRESS = "bluetooth_address"
        private const val KEY_PAPER_WIDTH = "paper_width_mm"
        private const val TAG = "AkasPrinter"
        private const val WRITE_CHUNK_SIZE = 256
        private const val WRITE_CHUNK_DELAY_MS = 20L
        private const val AFTER_WRITE_DELAY_MS = 800L
        private const val CONNECTION_ATTEMPTS = 2
        private const val CONNECTION_RETRY_DELAY_MS = 500L
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private val PRINTER_CHARSET: Charset = Charset.forName("windows-1252")
        private val INIT = byteArrayOf(0x1B, 0x40)
        private val ALIGN_LEFT = byteArrayOf(0x1B, 0x61, 0x00)
        private val PARTIAL_CUT = byteArrayOf(0x1D, 0x56, 0x01)
    }
}
