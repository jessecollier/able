/*
 * Copyright 2018 JUUL Labs, Inc.
 */

package com.juul.able.experimental

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGatt.STATE_CONNECTED
import android.content.Context
import android.os.RemoteException
import com.juul.able.experimental.ConnectGattResult.Canceled
import com.juul.able.experimental.ConnectGattResult.Failure
import com.juul.able.experimental.ConnectGattResult.Success
import com.juul.able.experimental.messenger.GattCallback
import com.juul.able.experimental.messenger.GattCallbackConfig
import com.juul.able.experimental.messenger.Messenger
import kotlinx.coroutines.CancellationException

private typealias GattConnector = (context: Context, autoConnect: Boolean) -> CoroutinesGatt?

/**
 * Provides a [Gatt] by requesting that a connection to the `device` be established.
 *
 * [Gatt.close] must be called when the connection is no longer needed.
 */
private fun defaultGattConnector(
    device: BluetoothDevice,
    callbackConfig: GattCallbackConfig
): GattConnector = { context, autoConnect ->
    val callback = GattCallback(callbackConfig)
    device.connectGatt(context, autoConnect, callback)?.let { bluetoothGatt ->
        val messenger = Messenger(bluetoothGatt, callback)
        CoroutinesGatt(bluetoothGatt, messenger)
    }
}

class CoroutinesDevice internal constructor(
    private val device: BluetoothDevice,
    private val gattConnector: GattConnector
) : Device {

    constructor(
        device: BluetoothDevice,
        callbackConfig: GattCallbackConfig = GattCallbackConfig()
    ) : this(device, defaultGattConnector(device, callbackConfig))

    /**
     * Establishes a connection to the [BluetoothDevice], suspending until connection is successful
     * or error occurs.
     *
     * To cancel an in-flight connection attempt, the Coroutine from which this method was called
     * can be canceled:
     *
     * ```
     * fun connect(androidContext: Context, device: BluetoothDevice) {
     *     connectJob = async {
     *         device.connectGattOrNull(androidContext, autoConnect = false)
     *     }
     * }
     *
     * fun cancelConnection() {
     *     connectJob?.cancel() // cancels the above `connectGatt`
     * }
     * ```
     *
     * A dedicated thread is spun up to handle interacting with the underlying [BluetoothGatt], and
     * can be stopped by invoking [Gatt.close] on the returned [Gatt] object.
     *
     * If an error occurs during connection process, then [Gatt.close] is automatically called
     * (which stops the dedicated thread).
     */
    override suspend fun connectGatt(context: Context, autoConnect: Boolean): ConnectGattResult {
        val gatt = gattConnector.invoke(context, autoConnect)
            ?: return Failure(
                RemoteException("`BluetoothDevice.connectGatt` returned `null`.")
            )
        val connectionStateMonitor = ConnectionStateMonitor(gatt)

        val didConnect = try {
            connectionStateMonitor.suspendUntilConnectionState(STATE_CONNECTED)
        } catch (e: CancellationException) {
            Able.info { "connectGatt() canceled." }
            gatt.close()
            return Canceled(e)
        } finally {
            connectionStateMonitor.close()
        }

        return if (didConnect) {
            Success(gatt)
        } else {
            Able.warn { "connectGatt() failed." }
            gatt.close()
            return Failure(
                IllegalStateException("Failed to connect to ${device.address}.")
            )
        }
    }
}
