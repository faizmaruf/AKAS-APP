package com.akas.pos

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.akas.pos.databinding.ActivityPrinterSettingsBinding
import com.akas.pos.printer.EscPosPrinterManager
import com.akas.pos.printer.PrinterDeviceAdapter
import java.util.concurrent.Executors

class PrinterSettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivityPrinterSettingsBinding
    private lateinit var printerManager: EscPosPrinterManager
    private val executor = Executors.newSingleThreadExecutor()
    private var devices: List<BluetoothDevice> = emptyList()

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            if (results.values.all { it }) loadPrinters()
            else Toast.makeText(this, "Izin koneksi dan pemindaian Bluetooth diperlukan", Toast.LENGTH_LONG).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityPrinterSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.rootView) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }

        printerManager = EscPosPrinterManager(this)
        binding.backButton.setOnClickListener { finish() }
        binding.openBluetoothButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        }
        binding.paper58Button.isChecked = printerManager.paperWidthMm() == 58
        binding.paper80Button.isChecked = printerManager.paperWidthMm() == 80
        binding.paperWidthGroup.setOnCheckedChangeListener { _, checkedId ->
            when (checkedId) {
                R.id.paper58Button -> printerManager.savePaperWidth(58)
                R.id.paper80Button -> printerManager.savePaperWidth(80)
            }
            updateStatus()
        }
        binding.printerList.setOnItemClickListener { _, _, position, _ ->
            connectPrinter(devices[position])
        }
    }

    override fun onResume() {
        super.onResume()
        requestPermissionAndLoad()
        updateStatus()
    }

    private fun requestPermissionAndLoad() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !hasBluetoothPermissions()) {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_SCAN
                )
            )
        } else loadPrinters()
    }

    private fun hasBluetoothPermissions(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun loadPrinters() {
        devices = printerManager.pairedPrinters()
        binding.printerList.adapter = PrinterDeviceAdapter(
            this,
            devices,
            printerManager.savedPrinterAddress()
        )
        binding.printerList.isEnabled = devices.isNotEmpty()
        if (devices.isEmpty()) {
            binding.connectionStatus.text = "Belum ada printer. Pairing perangkat baru terlebih dahulu."
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectPrinter(device: BluetoothDevice) {
        binding.connectionStatus.text = "Menghubungkan ke ${device.name ?: device.address}…"
        binding.printerList.isEnabled = false
        binding.openBluetoothButton.isEnabled = false
        executor.execute {
            runCatching { printerManager.testConnection(device.address) }
                .onSuccess {
                    printerManager.savePrinter(device.address)
                    runOnUiThread {
                        binding.connectionStatus.text = "● Terhubung: ${device.name ?: device.address}"
                        Toast.makeText(this, "Printer berhasil disambungkan", Toast.LENGTH_SHORT).show()
                        loadPrinters()
                        binding.openBluetoothButton.isEnabled = true
                    }
                }
                .onFailure { error ->
                    runOnUiThread {
                        binding.connectionStatus.text = "Gagal terhubung: ${error.message}"
                        binding.printerList.isEnabled = true
                        binding.openBluetoothButton.isEnabled = true
                    }
                }
        }
    }

    @SuppressLint("MissingPermission")
    private fun updateStatus() {
        val address = printerManager.savedPrinterAddress()
        val name = printerManager.savedPrinterName()
        binding.connectionStatus.text = if (address == null) {
            "Printer belum disambungkan • ${printerManager.paperWidthMm()} mm"
        } else {
            "● Siap: ${name ?: address} • ${printerManager.paperWidthMm()} mm"
        }
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }
}
