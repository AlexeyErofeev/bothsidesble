package com.healbe.bothsidesble

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.bluetooth.*
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import java.util.*
import java.util.concurrent.Executors
import kotlin.collections.HashMap
import kotlin.math.min

class BleServer(private val context: Context) {
    companion object {
        const val TAG = "BleServer"
    }
    
    private val manager: BluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private var bleServer: BluetoothGattServer? = null
    private var storedDevices = HashMap<String, BluetoothDevice>()
    private var buffers = HashMap<String, ByteArray>()
    
    var logCallback: ((String) -> Unit)? = null
    
    fun start() {
        startAdvertising()
        bleServer = manager.openGattServer(context, callback)
        bleServer?.addService(createService())
        logCallback?.invoke("Server started")
    }
    
    fun stop() {
        bleServer?.close()
        logCallback?.invoke("Server stopped")
        stopAdvertising()
    }
    
    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private fun startAdvertising() {
        
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP)
            return
        
        val bluetoothLeAdvertiser: BluetoothLeAdvertiser? =
            manager.adapter.bluetoothLeAdvertiser
        
        bluetoothLeAdvertiser?.let {
            val settings = AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
                .setConnectable(true)
                .setTimeout(0)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                .build()
            
            val data = AdvertiseData.Builder()
                .setIncludeDeviceName(true)
                .setIncludeTxPowerLevel(false)
                .addServiceUuid(ParcelUuid(NotificationProfile.INPUT_SERVICE))
                .build()
            
            it.startAdvertising(settings, data, advertiseCallback)
        } ?: logCallback?.invoke("Failed to create advertiser")
    }
    
    /**
     * Stop Bluetooth advertisements.
     */
    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private fun stopAdvertising() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP)
            return
        
        val bluetoothLeAdvertiser: BluetoothLeAdvertiser? =
            manager.adapter.bluetoothLeAdvertiser
        bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
            ?: logCallback?.invoke("Failed to create advertiser")
    }
    
    private val advertiseCallback by lazy {
        @TargetApi(Build.VERSION_CODES.LOLLIPOP)
        object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                logCallback?.invoke("LE Advertise Started.")
            }
            
            override fun onStartFailure(errorCode: Int) {
                logCallback?.invoke("LE Advertise Failed: $errorCode")
            }
        }
    }
    
    private fun createService(): BluetoothGattService? = with(NotificationProfile) {
        return BluetoothGattService(INPUT_SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY).apply {
            addCharacteristic(
                BluetoothGattCharacteristic(
                    NEW_MESSAGE,
                    BluetoothGattCharacteristic.PROPERTY_READ or
                         BluetoothGattCharacteristic.PROPERTY_WRITE,
                    BluetoothGattCharacteristic.PERMISSION_READ or
                         BluetoothGattCharacteristic.PERMISSION_WRITE
                ).apply {
                    addDescriptor(
                        BluetoothGattDescriptor(
                            CLIENT_CONFIG,
                            BluetoothGattDescriptor.PERMISSION_READ or
                                 BluetoothGattDescriptor.PERMISSION_WRITE
                        )
                    )
                }
            )
            
        }
    }
    
    private val callback = object : BluetoothGattServerCallback() {
        private val executor = Executors.newSingleThreadExecutor()
        
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) = executor.execute {
            Log.d(TAG, "onConnectionStateChange mac: ${device.address}, status: $status, newState: $newState")
            
            if (newState == BluetoothProfile.STATE_CONNECTED)
                logCallback?.invoke("${device.address} connected")
            
            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                storedDevices.remove(device.address)
                logCallback?.invoke("${device.address} disconnected")
            }
        }
        
        @SuppressLint("MissingPermission")
        override fun onCharacteristicReadRequest(device: BluetoothDevice?, requestId: Int, offset: Int,
            characteristic: BluetoothGattCharacteristic?) = executor.execute {
            Log.d(TAG, "onCharacteristicReadRequest mac: ${device?.address}, " +
                 "requestId: $requestId, " +
                 "charUuid: ${characteristic?.uuid.toString()}, " +
                 "offset: $offset")
            
            val strVal = "Hello ${device?.name ?: "new device"}. Put your message here."
            val resVal = strVal.substring(offset, min(offset + 22, strVal.length - 1)).toByteArray()
            
            if (NotificationProfile.NEW_MESSAGE == characteristic?.uuid) {
                bleServer?.sendResponse(
                    device,
                    requestId,
                    BluetoothGatt.GATT_SUCCESS,
                    offset,
                    resVal
                )
            } else
                bleServer?.sendResponse(
                    device,
                    requestId,
                    BluetoothGatt.GATT_FAILURE,
                    0,
                    null
                )
        }
        
        override fun onCharacteristicWriteRequest(device: BluetoothDevice, requestId: Int,
            characteristic: BluetoothGattCharacteristic?, preparedWrite: Boolean, responseNeeded: Boolean, offset: Int,
            value: ByteArray?) = executor.execute {
            Log.d(TAG, "onCharacteristicWriteRequest " +
                 "mac: ${device?.address}, " +
                 "requestId: $requestId, " +
                 "charUuid: ${characteristic?.uuid.toString()}, " +
                 "offset: $offset, " +
                 "value: ${String(value ?: byteArrayOf())}")
            
            if (NotificationProfile.NEW_MESSAGE == characteristic?.uuid) {
                if (responseNeeded)
                    bleServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
                
                if (String(value ?: byteArrayOf()).contains("<#"))
                    buffers[device.address] = value ?: byteArrayOf()
                else
                    buffers[device.address] = buffers[device.address] ?: byteArrayOf() + (value ?: byteArrayOf())
                
                val str = String(buffers[device.address] ?: byteArrayOf())
                
                if (str.length - 3 >= str[2].toInt() && storedDevices.containsKey(device.address))
                    characteristic.apply {
                        logCallback?.invoke("${device.address}: ${str.substring(3)}")
                        setValue(byteArrayOf(0))
                        bleServer?.notifyCharacteristicChanged(device, this, false)
                        buffers.remove(device.address)
                    }
                
            } else if (responseNeeded)
                bleServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, offset, null)
        }
        
        override fun onDescriptorReadRequest(device: BluetoothDevice, requestId: Int, offset: Int,
            descriptor: BluetoothGattDescriptor) = executor.execute {
            Log.d(TAG, "onDescriptorReadRequest mac: ${device.address}, " +
                 "requestId: $requestId, " +
                 "descUuid: ${descriptor.uuid}")
            
            if (NotificationProfile.CLIENT_CONFIG == descriptor.uuid) {
                val value = if (storedDevices.containsKey(device.address))
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                else
                    BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                
                bleServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, value)
            } else
                bleServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
        }
        
        override fun onDescriptorWriteRequest(device: BluetoothDevice, requestId: Int,
            descriptor: BluetoothGattDescriptor, preparedWrite: Boolean, responseNeeded: Boolean, offset: Int,
            value: ByteArray) = executor.execute {
            Log.d(TAG, "onDescriptorWriteRequest " +
                 "mac: ${device.address}, " +
                 "requestId: $requestId, " +
                 "charUuid: ${descriptor.uuid}, " +
                 "offeset: $offset, " +
                 "value: $value")
            
            if (NotificationProfile.CLIENT_CONFIG == descriptor.uuid) {
                if (Arrays.equals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE, value)) {
                    storedDevices[device.address] = device
                    logCallback?.invoke("${device.address} request notify")
                } else if (Arrays.equals(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE, value))
                    storedDevices.remove(device.address)
                
                if (responseNeeded)
                    bleServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            } else if (responseNeeded)
                bleServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
        }
    }
}


