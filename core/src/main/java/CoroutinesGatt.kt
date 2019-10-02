/*
 * Copyright 2018 JUUL Labs, Inc.
 */

@file:Suppress("RedundantUnitReturnType")

package com.juul.able.experimental

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile.STATE_CONNECTED
import android.bluetooth.BluetoothProfile.STATE_DISCONNECTED
import android.bluetooth.BluetoothProfile.STATE_DISCONNECTING
import android.os.RemoteException
import com.juul.able.experimental.messenger.Message
import com.juul.able.experimental.messenger.Message.DiscoverServices
import com.juul.able.experimental.messenger.Message.ReadCharacteristic
import com.juul.able.experimental.messenger.Message.RequestMtu
import com.juul.able.experimental.messenger.Message.WriteCharacteristic
import com.juul.able.experimental.messenger.Message.WriteDescriptor
import com.juul.able.experimental.messenger.Messenger
import com.juul.able.experimental.messenger.OnCharacteristicChanged
import com.juul.able.experimental.messenger.OnCharacteristicRead
import com.juul.able.experimental.messenger.OnCharacteristicWrite
import com.juul.able.experimental.messenger.OnConnectionStateChange
import com.juul.able.experimental.messenger.OnDescriptorWrite
import com.juul.able.experimental.messenger.OnMtuChanged
import com.juul.able.experimental.messenger.OnReadRemoteRssi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BroadcastChannel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.filter
import kotlinx.coroutines.selects.select
import java.util.UUID
import kotlin.coroutines.CoroutineContext

class GattClosed(message: String, cause: Throwable) : IllegalStateException(message, cause)
class GattConnectionLost : Exception()

class CoroutinesGatt(
    private val bluetoothGatt: BluetoothGatt,
    private val messenger: Messenger
) : Gatt {

    private val job = Job()
    override val coroutineContext: CoroutineContext
        get() = job

    private val connectionStateMonitor by lazy { ConnectionStateMonitor(this) }

    override val onConnectionStateChange: BroadcastChannel<OnConnectionStateChange>
        get() = messenger.callback.onConnectionStateChange

    override val onCharacteristicChanged: BroadcastChannel<OnCharacteristicChanged>
        get() = messenger.callback.onCharacteristicChanged

    override fun requestConnect(): Boolean = bluetoothGatt.connect()
    override fun requestDisconnect(): Unit = bluetoothGatt.disconnect()

    override suspend fun connect(): Boolean {
        return if (requestConnect()) {
            connectionStateMonitor.suspendUntilConnectionState(STATE_CONNECTED)
        } else {
            Able.error { "connect → BluetoothGatt.requestConnect() returned false " }
            false
        }
    }

    override suspend fun disconnect(): Unit {
        requestDisconnect()
        connectionStateMonitor.suspendUntilConnectionState(STATE_DISCONNECTED)
    }

    override fun close() {
        Able.verbose { "close → Begin" }
        job.cancel()
        connectionStateMonitor.close()
        messenger.close()
        bluetoothGatt.close()
        Able.verbose { "close → End" }
    }

    override val services: List<BluetoothGattService> get() = bluetoothGatt.services
    override fun getService(uuid: UUID): BluetoothGattService? = bluetoothGatt.getService(uuid)

    /**
     * @throws [RemoteException] if underlying [BluetoothGatt.discoverServices] returns `false`.
     * @throws [GattClosed] if [Gatt] is closed while method is executing.
     */
    override suspend fun discoverServices(): GattStatus {
        Able.debug { "discoverServices → send(DiscoverServices)" }

        val response = CompletableDeferred<Boolean>()
        messenger.send(DiscoverServices(response))

        val call = "BluetoothGatt.discoverServices()"
        Able.verbose { "discoverServices → Waiting for $call" }
        if (!response.await()) {
            throw RemoteException("$call returned false.")
        }

        Able.verbose { "discoverServices → Waiting for BluetoothGattCallback" }
        return try {
            messenger.callback.onServicesDiscovered.receiveRequiringConnection().also { status ->
                Able.info { "discoverServices, status=${status.asGattStatusString()}" }
            }
        } catch (e: ClosedReceiveChannelException) {
            throw GattClosed("Gatt closed during discoverServices", e)
        }
    }

    /**
     * @throws [RemoteException] if underlying [BluetoothGatt.readCharacteristic] returns `false`.
     * @throws [GattClosed] if [Gatt] is closed while method is executing.
     */
    override suspend fun readCharacteristic(
        characteristic: BluetoothGattCharacteristic
    ): OnCharacteristicRead {
        val uuid = characteristic.uuid
        Able.debug { "readCharacteristic → send(ReadCharacteristic[uuid=$uuid])" }

        val response = CompletableDeferred<Boolean>()
        messenger.send(ReadCharacteristic(characteristic, response))

        val call = "BluetoothGatt.readCharacteristic(BluetoothGattCharacteristic[uuid=$uuid])"
        Able.verbose { "readCharacteristic → Waiting for $call" }
        if (!response.await()) {
            throw RemoteException("Failed to read characteristic with UUID $uuid.")
        }

        Able.verbose { "readCharacteristic → Waiting for BluetoothGattCallback" }
        return try {
            messenger.callback.onCharacteristicRead.receiveRequiringConnection()
                .also { (_, value, status) ->
                    Able.info {
                        val bytesString = value.size.bytesString
                        val statusString = status.asGattStatusString()
                        "← readCharacteristic $uuid ($bytesString), status=$statusString"
                    }
                }
        } catch (e: ClosedReceiveChannelException) {
            throw GattClosed("Gatt closed during readCharacteristic[uuid=$uuid]", e)
        }
    }

    /**
     * @param value applied to [characteristic] when characteristic is written.
     * @param writeType applied to [characteristic] when characteristic is written.
     * @throws [RemoteException] if underlying [BluetoothGatt.writeCharacteristic] returns `false`.
     * @throws [GattClosed] if [Gatt] is closed while method is executing.
     */
    override suspend fun writeCharacteristic(
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        writeType: WriteType
    ): OnCharacteristicWrite {
        val uuid = characteristic.uuid
        Able.debug { "writeCharacteristic → send(WriteCharacteristic[uuid=$uuid])" }

        val response = CompletableDeferred<Boolean>()
        messenger.send(WriteCharacteristic(characteristic, value, writeType, response))

        val call = "BluetoothGatt.writeCharacteristic(BluetoothGattCharacteristic[uuid=$uuid])"
        Able.verbose { "writeCharacteristic → Waiting for $call" }
        if (!response.await()) {
            throw RemoteException("$call returned false.")
        }

        Able.verbose { "writeCharacteristic → Waiting for BluetoothGattCallback" }
        return try {
            messenger.callback.onCharacteristicWrite.receiveRequiringConnection()
                .also { (_, status) ->
                    Able.info {
                        val bytesString = value.size.bytesString
                        val typeString = writeType.asWriteTypeString()
                        val statusString = status.asGattStatusString()
                        "→ writeCharacteristic $uuid ($bytesString), type=$typeString, status=$statusString"
                    }
                }
        } catch (e: ClosedReceiveChannelException) {
            throw GattClosed("Gatt closed during writeCharacteristic[uuid=$uuid]", e)
        }
    }

    /**
     * @param value applied to [descriptor] when descriptor is written.
     * @throws [RemoteException] if underlying [BluetoothGatt.writeDescriptor] returns `false`.
     * @throws [GattClosed] if [Gatt] is closed while method is executing.
     */
    override suspend fun writeDescriptor(
        descriptor: BluetoothGattDescriptor, value: ByteArray
    ): OnDescriptorWrite {
        val uuid = descriptor.uuid
        Able.debug { "writeDescriptor → send(WriteDescriptor[uuid=$uuid])" }

        val response = CompletableDeferred<Boolean>()
        messenger.send(WriteDescriptor(descriptor, value, response))

        val call = "BluetoothGatt.writeDescriptor(BluetoothGattDescriptor[uuid=$uuid])"
        Able.verbose { "writeDescriptor → Waiting for $call" }
        if (!response.await()) {
            throw RemoteException("$call returned false.")
        }

        Able.verbose { "writeDescriptor → Waiting for BluetoothGattCallback" }
        return try {
            messenger.callback.onDescriptorWrite.receiveRequiringConnection().also { (_, status) ->
                Able.info {
                    val bytesString = value.size.bytesString
                    val statusString = status.asGattStatusString()
                    "→ writeDescriptor $uuid ($bytesString), status=$statusString"
                }
            }
        } catch (e: ClosedReceiveChannelException) {
            throw GattClosed("Gatt closed during writeDescriptor[uuid=$uuid]", e)
        }
    }

    /**
     * @throws [RemoteException] if underlying [BluetoothGatt.requestMtu] returns `false`.
     * @throws [GattClosed] if [Gatt] is closed while method is executing.
     */
    override suspend fun requestMtu(mtu: Int): OnMtuChanged {
        Able.debug { "requestMtu → send(RequestMtu[mtu=$mtu])" }

        val response = CompletableDeferred<Boolean>()
        messenger.send(RequestMtu(mtu, response))

        val call = "BluetoothGatt.requestMtu($mtu)"
        Able.verbose { "requestMtu → Waiting for $call" }
        if (!response.await()) {
            throw RemoteException("$call returned false.")
        }

        Able.verbose { "requestMtu → Waiting for BluetoothGattCallback" }
        return try {
            messenger.callback.onMtuChanged.receiveRequiringConnection().also { (mtu, status) ->
                Able.info { "requestMtu $mtu, status=${status.asGattStatusString()}" }
            }
        } catch (e: ClosedReceiveChannelException) {
            throw GattClosed("Gatt closed during requestMtu[mtu=$mtu]", e)
        }
    }

    /**
     * @throws [RemoteException] if underlying [BluetoothGatt.readRemoteRssi] returns `false`.
     * @throws [GattClosed] if [Gatt] is closed while method is executing.
     */
    override suspend fun readRemoteRssi(): OnReadRemoteRssi {
        Able.debug { "readRemoteRssi → send(ReadRemoteRssi)" }

        val response = CompletableDeferred<Boolean>()
        messenger.send(Message.ReadRemoteRssi(response))

        val call = "BluetoothGatt.readRemoteRssi()"
        Able.verbose { "readRemoteRssi → Waiting for $call" }
        if (!response.await()) {
            throw RemoteException("$call returned false.")
        }

        Able.verbose { "readRemoteRssi → Waiting for BluetoothGattCallback" }
        return try {
            messenger.callback.onReadRemoteRssi.receiveRequiringConnection().also { (status) ->
                Able.info { "readRemoteRssi status=${status.asGattStatusString()}" }
            }
        } catch (e: ClosedReceiveChannelException) {
            throw GattClosed("Gatt closed during readRemoteRssi", e)
        }
    }

    override fun setCharacteristicNotification(
        characteristic: BluetoothGattCharacteristic,
        enable: Boolean
    ): Boolean {
        Able.info { "setCharacteristicNotification ${characteristic.uuid} enable=$enable" }
        return bluetoothGatt.setCharacteristicNotification(characteristic, enable)
    }

    private suspend fun <T> ReceiveChannel<T>.receiveRequiringConnection(): T = select {
        onReceive { it }

        onConnectionStateChange
            .openSubscription() // fixme: Find solution w/o subscription object creation cost.
            .filter { (_, newState) ->
                newState == STATE_DISCONNECTING || newState == STATE_DISCONNECTED
            }
            .onReceive { throw GattConnectionLost() }
    }
}

private val Int.bytesString get() = if (this == 1) "$this byte" else "$this bytes"
