package uk.co.tomos.chat

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Nearby-device discovery for the direct Bluetooth fallback.
 *
 * BLE advertises only a short hash of the already trusted contact fingerprint.
 * The actual conversation uses RFCOMM and then the exact same signed identity
 * proof and Noise XX handshake as Wi-Fi. BLE is never used as a voice-media
 * transport. No location permission, relay, cloud directory or rendezvous is used.
 */
class BluetoothPeerDiscovery(
    context: Context,
    private val localFingerprint: String,
    private val trustedFingerprint: String,
    private val onPeerFound: (BluetoothDevice) -> Unit,
    private val onStatus: (String) -> Unit,
    private val onError: (Throwable) -> Unit
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val adapter: BluetoothAdapter? =
        (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    private val serviceUuid = ParcelUuid(PeerMessagingCoordinator.BLUETOOTH_SERVICE_UUID)
    private val expectedToken = fingerprintToken(trustedFingerprint)
    private val started = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val peerDelivered = AtomicBoolean(false)
    private var serverSocket: BluetoothServerSocket? = null
    private var advertiserCallback: AdvertiseCallback? = null
    private var scanCallback: ScanCallback? = null

    val acceptingSocket: BluetoothServerSocket?
        get() = serverSocket

    fun start() {
        check(!closed.get()) { "Bluetooth discovery has been closed." }
        if (!started.compareAndSet(false, true)) return
        if (localFingerprint == trustedFingerprint) {
            fail(IOException("A device cannot connect to itself."))
            return
        }
        if (!hasRequiredPermissions()) {
            fail(SecurityException("Allow Nearby devices permission to use automatic Bluetooth discovery."))
            return
        }
        val bluetooth = adapter
        if (bluetooth == null || !bluetooth.isEnabled) {
            fail(IOException("Bluetooth is unavailable or switched off."))
            return
        }
        try {
            startBluetooth(bluetooth)
        } catch (error: Exception) {
            fail(error)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startBluetooth(bluetooth: BluetoothAdapter) {
        val advertiser = bluetooth.bluetoothLeAdvertiser
            ?: throw IOException("This device does not support Bluetooth LE advertising.")
        val scanner = bluetooth.bluetoothLeScanner
            ?: throw IOException("This device does not support Bluetooth LE scanning.")
        serverSocket = bluetooth.listenUsingRfcommWithServiceRecord(
            "TomChat",
            PeerMessagingCoordinator.BLUETOOTH_SERVICE_UUID
        )

        val advertisingData = AdvertiseData.Builder()
            .addServiceUuid(serviceUuid)
            .addServiceData(serviceUuid, fingerprintToken(localFingerprint))
            .setIncludeDeviceName(false)
            .build()
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        val advertiserListener = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                if (!closed.get()) onStatus("Bluetooth is discoverable to your trusted device")
            }
            override fun onStartFailure(errorCode: Int) {
                fail(IOException("Bluetooth advertising failed (code $errorCode)."))
            }
        }
        advertiserCallback = advertiserListener
        advertiser.startAdvertising(settings, advertisingData, advertiserListener)

        val filter = ScanFilter.Builder()
            .setServiceUuid(serviceUuid)
            .setServiceData(serviceUuid, expectedToken, ByteArray(expectedToken.size) { 0xFF.toByte() })
            .build()
        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        val scannerListener = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (closed.get() || !peerDelivered.compareAndSet(false, true)) return
                stopScanningOnly()
                onStatus("Found trusted device over Bluetooth; authenticating…")
                onPeerFound(result.device)
            }
            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, it) }
            }
            override fun onScanFailed(errorCode: Int) {
                fail(IOException("Bluetooth scanning failed (code $errorCode)."))
            }
        }
        scanCallback = scannerListener
        scanner.startScan(listOf(filter), scanSettings, scannerListener)
        onStatus("Finding trusted device over Bluetooth…")
    }

    @SuppressLint("MissingPermission")
    private fun stopScanningOnly() {
        try { scanCallback?.let { adapter?.bluetoothLeScanner?.stopScan(it) } } catch (_: Exception) { }
        scanCallback = null
        try { advertiserCallback?.let { adapter?.bluetoothLeAdvertiser?.stopAdvertising(it) } } catch (_: Exception) { }
        advertiserCallback = null
    }

    private fun hasRequiredPermissions(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val required = arrayOf(
            android.Manifest.permission.BLUETOOTH_SCAN,
            android.Manifest.permission.BLUETOOTH_ADVERTISE,
            android.Manifest.permission.BLUETOOTH_CONNECT
        )
        return required.all {
            ContextCompat.checkSelfPermission(appContext, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun fail(error: Throwable) {
        if (!closed.get()) {
            onError(error)
            close()
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            stopScanningOnly()
            try { serverSocket?.close() } catch (_: Exception) { }
            serverSocket = null
        }
    }

    companion object {
        internal fun fingerprintToken(fingerprint: String): ByteArray =
            MessageDigest.getInstance("SHA-256")
                .digest(fingerprint.toByteArray(Charsets.US_ASCII))
                .copyOfRange(0, 16)
    }
}
