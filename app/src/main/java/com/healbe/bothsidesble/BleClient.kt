package com.healbe.bothsidesble

import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.ParcelUuid
import android.util.Log
import java.lang.Exception
import java.util.concurrent.Executors


class BleClient(private val context: Context) {
    companion object {
        const val TAG = "BleClient"
    }
    
    private val manager: BluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private var gatt: BluetoothGatt? = null
    private var service: BluetoothGattService? = null
    private var char: BluetoothGattCharacteristic? = null
    private var desc: BluetoothGattDescriptor? = null
    private val stopHandler = Handler()
    private var message: String = ""
    private var onResult: ((Boolean) -> Unit)? = null
    private val searchTimeout: Long = 15000
    
    fun send(message: String, onResult: (Boolean) -> Unit) {
        this.message = message
        this.onResult = onResult
        
        findFirstAndSend()
    }
    
    private fun findFirstAndSend() {
        Log.d(TAG, "Searching device...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val callback = object : ScanCallback() {
                override fun onScanFailed(errorCode: Int) {
                    stopHandler.removeCallbacksAndMessages(null)
                    onResult?.invoke(false)
                }
                
                override fun onScanResult(callbackType: Int, result: ScanResult?) {
                    stopHandler.removeCallbacksAndMessages(null)
                    manager.adapter.bluetoothLeScanner.stopScan(this)
                    sendTo(result?.device?.address ?: "")
                }
            }
            
            with(manager.adapter.bluetoothLeScanner) {
                stopHandler.postDelayed(
                    {
                        Log.e(TAG, "device not found")
                        stopScan(callback)
                        onResult?.invoke(false)
                    },
                    searchTimeout)
                
                startScan(
                    listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(NotificationProfile.INPUT_SERVICE)).build()),
                    ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
                    callback
                )
            }
            
        } else {
            @Suppress("DEPRECATION")
            val callback = object : BluetoothAdapter.LeScanCallback {
                override fun onLeScan(device: BluetoothDevice?, rssi: Int, value: ByteArray?) {
                    if (UUIDUtils.extractUUIDs(value).contains(NotificationProfile.INPUT_SERVICE)) {
                        stopHandler.removeCallbacksAndMessages(null)
                        manager.adapter.stopLeScan(this)
                        sendTo(device?.address ?: "")
                    }
                }
            }
            
            @Suppress("DEPRECATION")
            with(manager.adapter) {
                stopHandler.postDelayed(
                    {
                        Log.e(TAG, "device not found")
                        stopLeScan(callback)
                        onResult?.invoke(false)
                    },
                    15000)
                
                startLeScan(callback)
            }
        }
    }
    
    private fun sendTo(mac: String) {
        Log.d(TAG, "trying to send message: \"$message\" to $mac...")
        val callback = Callback(object : GattBacks {
            var result = false
            
            override fun onError() {
                onResult?.invoke(false)
            }
            
            override fun onPrepared() {
                gatt?.writeCharacteristic(char?.apply {
                    value = "<#".toByteArray() + message.length.toByte() + message.toByteArray()
                }) ?: onError()
            }
            
            override fun onDisconnected() {
                onResult?.invoke(result)
            }
            
            override fun onCharChanged(value: ByteArray?) {
                result = (value?.get(0)?.toInt() ?: 1) == 0
                gatt?.disconnect()
            }
        })
        
        try {
            val device = manager.adapter.getRemoteDevice(mac.toUpperCase())
            Log.d(TAG, "device $mac found, name: ${device.name}")
            gatt = device.connectGatt(context, false, callback)
            Log.d(TAG, "connecting...")
            if (gatt == null)
                onResult?.invoke(false)
            
        } catch (exception: Exception) {
            Log.e(TAG, exception.message, exception)
            onResult?.invoke(false)
        }
    }
    
    interface GattBacks {
        fun onPrepared()
        fun onDisconnected()
        fun onCharChanged(value: ByteArray?)
        fun onError()
    }
    
    inner class Callback(private val backs: GattBacks) : BluetoothGattCallback() {
        private val executor = Executors.newSingleThreadExecutor()
        
        private fun finish(gatt: BluetoothGatt?) {
            gatt?.close()
            executor.shutdown()
        }
        
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) = executor.execute {
            Log.d(TAG, "onConnectionStateChange status: $status, newState: $newState")
            if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                if (status != BluetoothGatt.GATT_SUCCESS)
                    backs.onError()
                
                backs.onDisconnected()
                finish(gatt)
            } else if (newState == BluetoothGatt.STATE_CONNECTED)
                gatt?.discoverServices()
        }
        
        
        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) = executor.execute {
            Log.d(TAG, "onServicesDiscovered status: $status")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                service = gatt?.getService(NotificationProfile.INPUT_SERVICE)
                char = service?.getCharacteristic(NotificationProfile.NEW_MESSAGE)
                desc = char?.getDescriptor(NotificationProfile.CLIENT_CONFIG)
                
                gatt?.setCharacteristicNotification(char, true)
                
                try {
                    desc?.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    gatt?.writeDescriptor(desc)
                } catch (throwable: Throwable) {
                    backs.onError()
                    finish(gatt)
                }
            }
            
            if (service == null || char == null || desc == null) {
                backs.onError()
            }
        }
        
        override fun onDescriptorWrite(gatt: BluetoothGatt?, descriptor: BluetoothGattDescriptor?, status: Int) =
            executor.execute {
                Log.d(TAG, "onDescriptorWrite: $status, uuid: ${descriptor?.uuid.toString()}")
                
                if (status == BluetoothGatt.GATT_SUCCESS && descriptor?.uuid == desc?.uuid)
                    gatt?.readCharacteristic(gatt.getService(NotificationProfile.INPUT_SERVICE)
                                                 .getCharacteristic(NotificationProfile.NEW_MESSAGE)
                    ) //backs.onPrepared()
                else
                    backs.onError()
            }
        
        
        override fun onCharacteristicRead(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?,
            status: Int) {
            Log.d(TAG,
                  "onCharacteristicRead: $status, " +
                       "uuid: ${characteristic?.uuid.toString()}, " +
                       "value: ${String(characteristic?.value ?: byteArrayOf())}"
            )
            
            if (status == BluetoothGatt.GATT_SUCCESS)
                backs.onPrepared()
            else
                backs.onError()
        }
        
        override fun onCharacteristicWrite(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?,
            status: Int) = executor.execute {
            Log.d("BleClient",
                  "onCharacteristicWrite: value: ${String(characteristic?.value ?: byteArrayOf())}, " +
                       "uuid: ${characteristic?.uuid.toString()}")
        }
        
        override fun onCharacteristicChanged(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?) =
            executor.execute {
                Log.d("BleClient",
                      "onCharacteristicChanged: value: ${String(characteristic?.value ?: byteArrayOf())}, " +
                           "uuid: ${characteristic?.uuid.toString()}")
                
                if (characteristic?.uuid == NotificationProfile.NEW_MESSAGE)
                    backs.onCharChanged(characteristic.value)
                else
                    backs.onError()
            }
    }
}