package com.example.bond

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import androidx.annotation.RequiresPermission
import java.nio.charset.StandardCharsets

class BLEManager(private val context: Context) {

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothLeAdvertiser: BluetoothLeAdvertiser? = null
    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private var isAdvertising = false
    private var isScanning = false

    // --- Callback to notify when a new user is detected ---
    var onUserDetected: ((name: String, rssi: Int) -> Unit)? = null

    companion object {
        private const val TAG = "BLEManager"
        private val SERVICE_UUID =
            ParcelUuid.fromString("0000180D-0000-1000-8000-00805F9B34FB") // Heart Rate UUID
    }

    init {
        try {
            val bluetoothManager =
                context.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager
            bluetoothAdapter = bluetoothManager?.adapter
            bluetoothLeAdvertiser = bluetoothAdapter?.bluetoothLeAdvertiser
            bluetoothLeScanner = bluetoothAdapter?.bluetoothLeScanner
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Bluetooth: ${e.message}")
            bluetoothAdapter = null
            bluetoothLeAdvertiser = null
            bluetoothLeScanner = null
        }
    }

    // --- Start advertising your own username ---
    @RequiresPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
    fun startAdvertising(name: String) {
        if (bluetoothAdapter == null || !bluetoothAdapter!!.isEnabled) {
            Log.e(TAG, "Bluetooth adapter is null or disabled")
            return
        }

        if (bluetoothLeAdvertiser == null) {
            Log.e(TAG, "Bluetooth LE Advertiser is null")
            return
        }

        if (isAdvertising) {
            Log.w(TAG, "Already advertising, restarting...")
            stopAdvertising()
        }

        val advertisingName = "BND$name"
        val nameBytes = advertisingName.toByteArray(StandardCharsets.UTF_8)

        Log.d(TAG, "Starting BLE advertising with name: $advertisingName")

        if (nameBytes.size > 20) {
            val truncated = "BND${name.take(10)}"
            Log.w(TAG, "Name too long, truncating to: $truncated")
            return startAdvertisingWithData(truncated.toByteArray(StandardCharsets.UTF_8))
        }

        startAdvertisingWithData(nameBytes)
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
    private fun startAdvertisingWithData(nameBytes: ByteArray) {
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(SERVICE_UUID)
            .addServiceData(SERVICE_UUID, nameBytes)
            .build()

        try {
            bluetoothLeAdvertiser?.startAdvertising(settings, data, advertiseCallback)
            isAdvertising = true
        } catch (se: SecurityException) {
            Log.e(TAG, "Missing BLE advertise permission: ${se.message}")
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
    fun stopAdvertising() {
        if (bluetoothLeAdvertiser != null && isAdvertising) {
            Log.d(TAG, "Stopping BLE advertising")
            try {
                bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
            } catch (se: SecurityException) {
                Log.e(TAG, "Missing advertise permission: ${se.message}")
            }
            isAdvertising = false
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            Log.d(TAG, "BLE advertising started successfully")
        }

        override fun onStartFailure(errorCode: Int) {
            Log.e(TAG, "BLE advertising failed: $errorCode")
            isAdvertising = false
        }
    }

    // --- BLE Scanning ---
    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    fun startBLEScan() {
        if (bluetoothAdapter == null || !bluetoothAdapter!!.isEnabled) {
            Log.e(TAG, "Bluetooth adapter is null or disabled")
            return
        }

        if (bluetoothLeScanner == null) {
            Log.e(TAG, "Bluetooth LE Scanner is null")
            return
        }

        if (isScanning) {
            Log.w(TAG, "Already scanning, restarting")
            stopBLEScan()
        }

        Log.d(TAG, "Starting BLE scan for BND-prefixed devices")

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
            .setReportDelay(0L)
            .build()

        val filter = ScanFilter.Builder().setServiceUuid(SERVICE_UUID).build()

        try {
            bluetoothLeScanner?.startScan(listOf(filter), settings, scanCallback)
            isScanning = true
        } catch (se: SecurityException) {
            Log.e(TAG, "Missing BLE scan permission: ${se.message}")
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    fun stopBLEScan() {
        if (bluetoothLeScanner != null && isScanning) {
            try {
                bluetoothLeScanner?.stopScan(scanCallback)
                Log.d(TAG, "BLE scan stopped successfully")
            } catch (se: SecurityException) {
                Log.e(TAG, "Missing BLE scan permission: ${se.message}")
            }
            isScanning = false
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (!isScanning) return

            val serviceData = result.scanRecord?.serviceData
            serviceData?.forEach { (uuid, data) ->
                if (uuid == SERVICE_UUID) {
                    try {
                        val dataString = String(data, StandardCharsets.UTF_8)
                        if (dataString.startsWith("BND")) {
                            val username = dataString.removePrefix("BND")
                            val rssi = result.rssi
                            Log.d(TAG, "Found BND user: $username (rssi=$rssi)")
                            onUserDetected?.invoke(username, rssi)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to parse BLE data: ${e.message}")
                    }
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "BLE scan failed: $errorCode")
            isScanning = false
        }
    }
}
