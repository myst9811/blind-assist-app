// app/src/main/java/com/example/blindassistant/app/services/BLEManager.kt

package com.example.blindassistant.app.services

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.util.Log
import com.example.blindassistant.app.utils.Constants
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.*

class BLEManager(private val context: Context) {

    private val bluetoothAdapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    private var bluetoothGatt: BluetoothGatt? = null
    private var rxCharacteristic: BluetoothGattCharacteristic? = null
    private var txCharacteristic: BluetoothGattCharacteristic? = null

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected

    private var buttonCallback: ((buttonId: Byte) -> Unit)? = null

    companion object {
        private const val TAG = "BLEManager"
    }

    @SuppressLint("MissingPermission")
    fun scanAndConnect(onError: (String) -> Unit = {}) {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            onError("Bluetooth not available or disabled")
            return
        }

        Log.d(TAG, "🔍 Starting BLE scan for ${Constants.BLE_DEVICE_NAME}...")
        bluetoothAdapter.bluetoothLeScanner?.startScan(scanCallback)
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val deviceName = device.name

            Log.d(TAG, "Found device: $deviceName")

            if (deviceName == Constants.BLE_DEVICE_NAME) {
                Log.d(TAG, "✅ Found glove: ${device.address}")
                bluetoothAdapter?.bluetoothLeScanner?.stopScan(this)
                connectToDevice(device)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "❌ BLE scan failed: $errorCode")
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectToDevice(device: BluetoothDevice) {
        Log.d(TAG, "🔗 Connecting to glove...")
        bluetoothGatt = device.connectGatt(context, false, gattCallback)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.d(TAG, "✅ Connected to GATT server")
                    _isConnected.value = true
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "❌ Disconnected from GATT server")
                    _isConnected.value = false
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val service = gatt.getService(UUID.fromString(Constants.BLE_SERVICE_UUID))

                if (service != null) {
                    rxCharacteristic = service.getCharacteristic(
                        UUID.fromString(Constants.BLE_RX_CHAR_UUID)
                    )
                    txCharacteristic = service.getCharacteristic(
                        UUID.fromString(Constants.BLE_TX_CHAR_UUID)
                    )

                    txCharacteristic?.let {
                        gatt.setCharacteristicNotification(it, true)
                        val descriptor = it.getDescriptor(
                            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
                        )
                        descriptor?.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        gatt.writeDescriptor(descriptor)
                    }

                    Log.d(TAG, "✅ Glove services configured")
                } else {
                    Log.e(TAG, "❌ Service not found")
                }
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            val data = characteristic.value
            if (data.isNotEmpty()) {
                val buttonId = data[0]
                Log.d(TAG, "🔘 Button event: $buttonId")
                buttonCallback?.invoke(buttonId)
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun sendHapticCommand(command: Byte, direction: Byte = Constants.DIRECTION_FRONT) {
        if (!_isConnected.value || rxCharacteristic == null) {
            Log.w(TAG, "⚠️ Cannot send haptic - not connected")
            return
        }

        try {
            val data = byteArrayOf(command, direction)
            rxCharacteristic?.value = data
            bluetoothGatt?.writeCharacteristic(rxCharacteristic)

            Log.d(TAG, "📤 Sent haptic: cmd=${command.toString(16)}, dir=$direction")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error sending haptic", e)
        }
    }

    fun setButtonCallback(callback: (Byte) -> Unit) {
        buttonCallback = callback
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
        _isConnected.value = false
        Log.d(TAG, "🔌 Disconnected")
    }
}