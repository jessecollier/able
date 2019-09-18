/*
 * Copyright 2019 JUUL Labs, Inc.
 */

package com.juul.able.experimental

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGatt.GATT_SUCCESS
import android.bluetooth.BluetoothProfile.STATE_CONNECTED
import android.bluetooth.BluetoothProfile.STATE_CONNECTING
import android.bluetooth.BluetoothProfile.STATE_DISCONNECTING
import android.content.Context
import com.juul.able.experimental.ConnectGattResult.Success
import com.juul.able.experimental.messenger.GattCallback
import com.juul.able.experimental.messenger.GattCallbackConfig
import com.juul.able.experimental.messenger.Messenger
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.BeforeClass
import org.junit.Test

class ConsoleLogger : Logger {

    override fun isLoggable(priority: Int) = true

    override fun log(priority: Int, throwable: Throwable?, message: String) {
        println("$priority $message${if (throwable != null) " $throwable" else ""}")
    }

}

class CoroutinesDeviceTest {

    companion object {
        @BeforeClass
        @JvmStatic
        fun setUp() {
//            Able.logger = NoOpLogger()
            Able.logger = ConsoleLogger()
        }
    }

    @Test
    fun test() = runBlocking<Unit> {
        val bluetoothDevice = spyk<BluetoothDevice>()
        val context = mockk<Context>()

        val callback = GattCallback(GattCallbackConfig(capacity = 1))
        val bluetoothGatt = mockk<BluetoothGatt> {
            every { close() } returns Unit
        }

        val device = CoroutinesDevice(bluetoothDevice) { _, _ ->
            val messenger = Messenger(bluetoothGatt, callback)
            CoroutinesGatt(bluetoothGatt, messenger)
        }

        launch {
            println("STATE_CONNECTING")
            callback.onConnectionStateChange(bluetoothGatt, GATT_SUCCESS, STATE_CONNECTING)

            println("STATE_CONNECTED")
            callback.onConnectionStateChange(bluetoothGatt, GATT_SUCCESS, STATE_CONNECTED)

            println("STATE_DISCONNECTING")
            callback.onConnectionStateChange(bluetoothGatt, GATT_SUCCESS, STATE_DISCONNECTING)
        }

        val gatt = (device.connectGatt(context, autoConnect = false) as Success).gatt

        launch {
            gatt.onConnectionStateChange.consumeEach { (status, newState) ->
                println("status=$status, newState=$newState")
            }
        }

        println("connected")
        gatt.doOnDisconnected {
            println("disconnecting")
            gatt.close()
            println("closed")
        }
    }
}

private fun Gatt.doOnDisconnected(action: suspend () -> Unit) =
    doOnConnectionState(STATE_DISCONNECTING, action)

private fun Gatt.doOnConnectionState(
    connectionState: GattState,
    action: suspend () -> Unit
) = launch {
    onConnectionStateChange.consumeEach { (_, newState) ->
        if (newState == connectionState) {
            action.invoke()
        }
    }
}
