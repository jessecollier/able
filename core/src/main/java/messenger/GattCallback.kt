/*
 * Copyright 2018 JUUL Labs, Inc.
 */

package com.juul.able.experimental.messenger

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import com.juul.able.experimental.Able
import com.juul.able.experimental.GattState
import com.juul.able.experimental.GattStatus
import com.juul.able.experimental.asGattConnectionStatusString
import com.juul.able.experimental.asGattStateString
import kotlinx.coroutines.channels.BroadcastChannel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.sendBlocking
import kotlinx.coroutines.sync.Mutex

data class GattCallbackConfig(
    val onCharacteristicChangedCapacity: Int = Channel.CONFLATED,
    val onServicesDiscoveredCapacity: Int = Channel.CONFLATED,
    val onCharacteristicReadCapacity: Int = Channel.CONFLATED,
    val onCharacteristicWriteCapacity: Int = Channel.CONFLATED,
    val onDescriptorReadCapacity: Int = Channel.CONFLATED,
    val onDescriptorWriteCapacity: Int = Channel.CONFLATED,
    val onReliableWriteCompletedCapacity: Int = Channel.CONFLATED,
    val onMtuChangedCapacity: Int = Channel.CONFLATED
) {
    constructor(capacity: Int = Channel.CONFLATED) : this(
        onCharacteristicChangedCapacity = capacity,
        onServicesDiscoveredCapacity = capacity,
        onCharacteristicReadCapacity = capacity,
        onCharacteristicWriteCapacity = capacity,
        onDescriptorReadCapacity = capacity,
        onDescriptorWriteCapacity = capacity,
        onReliableWriteCompletedCapacity = capacity,
        onMtuChangedCapacity = capacity
    )
}

internal class GattCallback(config: GattCallbackConfig) : BluetoothGattCallback() {

    internal val onConnectionStateChange =
        BroadcastChannel<OnConnectionStateChange>(1)
    internal val onCharacteristicChanged =
        BroadcastChannel<OnCharacteristicChanged>(config.onCharacteristicChangedCapacity)

    internal val onServicesDiscovered =
        Channel<GattStatus>(config.onServicesDiscoveredCapacity)
    internal val onCharacteristicRead =
        Channel<OnCharacteristicRead>(config.onCharacteristicReadCapacity)
    internal val onCharacteristicWrite =
        Channel<OnCharacteristicWrite>(config.onCharacteristicWriteCapacity)
    internal val onDescriptorRead = Channel<OnDescriptorRead>(config.onDescriptorReadCapacity)
    internal val onDescriptorWrite = Channel<OnDescriptorWrite>(config.onDescriptorWriteCapacity)
    internal val onReliableWriteCompleted =
        Channel<OnReliableWriteCompleted>(config.onReliableWriteCompletedCapacity)
    internal val onMtuChanged = Channel<OnMtuChanged>(config.onMtuChangedCapacity)

    private val gattLock = Mutex()
    internal suspend fun waitForGattReady() = gattLock.lock()
    internal fun notifyGattReady() {
        if (gattLock.isLocked) {
            gattLock.unlock()
        }
    }

    fun close() {
        Able.verbose { "close → Begin" }

        onConnectionStateChange.close()
        onCharacteristicChanged.close()

        onServicesDiscovered.close()
        onCharacteristicRead.close()
        onCharacteristicWrite.close()
        onDescriptorRead.close()
        onDescriptorWrite.close()
        onReliableWriteCompleted.close()

        Able.verbose { "close → End" }
    }

    override fun onConnectionStateChange(
        gatt: BluetoothGatt,
        status: GattStatus,
        newState: GattState
    ) {
        Able.debug {
            val statusString = status.asGattConnectionStatusString()
            val stateString = newState.asGattStateString()
            "onConnectionStateChange → status = $statusString, newState = $stateString"
        }

        onConnectionStateChange.sendBlocking(OnConnectionStateChange(status, newState))
    }

    override fun onServicesDiscovered(gatt: BluetoothGatt, status: GattStatus) {
        Able.verbose { "onServicesDiscovered → status = $status" }
        onServicesDiscovered.sendBlocking(status)
        notifyGattReady()
    }

    override fun onCharacteristicRead(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        status: GattStatus
    ) {
        Able.verbose { "onCharacteristicRead → uuid = ${characteristic.uuid}" }
        val event = OnCharacteristicRead(characteristic, characteristic.value, status)
        onCharacteristicRead.sendBlocking(event)
        notifyGattReady()
    }

    override fun onCharacteristicWrite(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        status: GattStatus
    ) {
        Able.verbose { "onCharacteristicWrite → uuid = ${characteristic.uuid}" }
        onCharacteristicWrite.sendBlocking(OnCharacteristicWrite(characteristic, status))
        notifyGattReady()
    }

    override fun onCharacteristicChanged(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic
    ) {
        Able.verbose { "onCharacteristicChanged → uuid = ${characteristic.uuid}" }
        val event = OnCharacteristicChanged(characteristic, characteristic.value)
        onCharacteristicChanged.sendBlocking(event)

        // We don't call `notifyGattReady` because `onCharacteristicChanged` is called whenever a
        // characteristic changes (after notification(s) have been enabled) so is not directly tied
        // to a specific call (or lock).
    }

    override fun onDescriptorRead(
        gatt: BluetoothGatt,
        descriptor: BluetoothGattDescriptor,
        status: GattStatus
    ) {
        Able.verbose { "onDescriptorRead → uuid = ${descriptor.uuid}" }
        onDescriptorRead.sendBlocking(OnDescriptorRead(descriptor, descriptor.value, status))
        notifyGattReady()
    }

    override fun onDescriptorWrite(
        gatt: BluetoothGatt,
        descriptor: BluetoothGattDescriptor,
        status: GattStatus
    ) {
        Able.verbose { "onDescriptorWrite → uuid = ${descriptor.uuid}" }
        onDescriptorWrite.sendBlocking(OnDescriptorWrite(descriptor, status))
        notifyGattReady()
    }

    override fun onReliableWriteCompleted(gatt: BluetoothGatt, status: Int) {
        Able.verbose { "onReliableWriteCompleted → status = $status" }
        onReliableWriteCompleted.sendBlocking(OnReliableWriteCompleted(status))
        notifyGattReady()
    }

    override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
        Able.verbose { "onMtuChanged → status = $status" }
        onMtuChanged.sendBlocking(OnMtuChanged(mtu, status))
        notifyGattReady()
    }
}
