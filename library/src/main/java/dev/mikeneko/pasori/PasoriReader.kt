package dev.mikeneko.pasori

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.*
import android.util.Log
import kotlinx.coroutines.*

private const val LOG_TAG = "Pasori"
private const val ACTION_USB_PERMISSION = "dev.mikeneko.USB_PERMISSION"
private const val PRODUCT_NAME = "RC-S380/P"
private const val TIMEOUT = 50

object PasoriReader {
    sealed class Result<out R> {
        data class Success<out T>(val ids: T) : Result<T>()
        data class Failure(val error: Error) : Result<Nothing>()
    }

    enum class Error(val message: String, val detail: String) {
        // サービス取得不可
        NOT_SERVICE("USB利用不可", "利用対象外の機種の為、カードリーダーが利用できません"),
        // パーミッションなし
        PERMISSION_DENIED("USBアクセスエラー", "USBへのアクセス権限がありません。USBへの接続を許可してください"),
        // 未接続
        NOT_FOUND("カードリーダー未接続", "カードリーダーが接続されているか確認してください。接続している場合は、一度、抜き差しをしてください"),
        // 接続エラー
        ERROR_OPEN("カードリーダー通信失敗", "カードリーダーが接続されているか確認してください。接続している場合は、一度、抜き差しをしてください")
    }

    private data class Pipe(val connection: UsbDeviceConnection, val inEndpoint: UsbEndpoint, val outEndpoint: UsbEndpoint)

    private enum class Command(val data: Int) {
        InSetRF(0x00),
        InSetProtocol(0x02),
        InCommRF(0x04),
        SwitchRF(0x06),
        SetCommandType(0x2A)
    }

    var debug = true

    private var inEndpoint: UsbEndpoint? = null
    private var outEndpoint: UsbEndpoint? = null
    private var connection: UsbDeviceConnection? = null

    @Synchronized
    suspend fun asyncReadIDs(context: Context): Result<Array<String>> {
        log("Call asyncReadIDs")
        close()

        val manager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
        if (manager == null) {
            errorLog(Error.NOT_SERVICE.toString())
            return Result.Failure(Error.NOT_SERVICE)
        }

        val deviceList: HashMap<String, UsbDevice> = manager.deviceList
        val device = deviceList.values.find { it.productName == PRODUCT_NAME }
        if (device == null) {
            errorLog(Error.NOT_FOUND.toString())
            return Result.Failure(Error.NOT_FOUND)
        }


        if (!manager.hasPermission(device)) {
            if(!requestPermission(context, manager, device)) {
                errorLog(Error.PERMISSION_DENIED.toString())
                return Result.Failure(Error.PERMISSION_DENIED)
            }
        }
        val pipe: Pipe
        device.getInterface(0).also {
            val dirIn = (0..it.endpointCount).find { index -> it.getEndpoint(index).direction == UsbConstants.USB_DIR_IN }
            val dirOut = (0..it.endpointCount).find { index -> it.getEndpoint(index).direction == UsbConstants.USB_DIR_OUT }
            if (dirIn == null || dirOut == null) {
                return Result.Failure(Error.ERROR_OPEN)
            }
            val inEndpoint = it.getEndpoint(dirIn)
            val outEndpoint = it.getEndpoint(dirOut)
            val connection = manager.openDevice(device)
            if (connection == null || inEndpoint == null || outEndpoint == null) {
                errorLog(Error.ERROR_OPEN.toString())
                return Result.Failure(Error.ERROR_OPEN)
            }
            pipe = Pipe(connection, inEndpoint, outEndpoint)

            connection.claimInterface(it, true)
        }

        log("start read...")
        try {
            while (true) {
                val ids = mutableListOf<String>()
                val type2 = readType2UID(pipe)
                if (type2 != null) {
                    ids.add(type2)
                }
                delay(50)
                val type3 = readType3IDm(pipe)
                if (type3 != null) {
                    ids.add(type3)
                }
                if (ids.isNotEmpty()) {
                    log(ids.toString())
                    return Result.Success(ids.toTypedArray())
                }
                delay(100)
            }
        } finally {
            close()
        }
    }

    @Synchronized
    fun close() {
        log("Call close")
        connection?.close()
        connection = null
        inEndpoint = null
        outEndpoint = null
    }

    private suspend fun requestPermission(context: Context, manager: UsbManager, device: UsbDevice): Boolean {
        log("Call requestPermission")
        return suspendCancellableCoroutine { continuation ->
            val usbReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (ACTION_USB_PERMISSION == intent.action) {
                        synchronized(this@PasoriReader) {
                            context.unregisterReceiver(this)
                            val result = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                            continuation.resume(result) {
                                close()
                            }
                        }
                    }
                }
            }
            continuation.invokeOnCancellation {
                context.unregisterReceiver(usbReceiver)
            }
            context.run {
                val permissionIntent = PendingIntent.getBroadcast(this, 0, Intent(ACTION_USB_PERMISSION), 0)
                val filter = IntentFilter(ACTION_USB_PERMISSION)
                registerReceiver(usbReceiver, filter)
                manager.requestPermission(device, permissionIntent)
            }
        }
    }

    private fun readType2UID(pipe: Pipe): String? {
        var uid = ""
        sendACK(pipe)
        val sdd1 = execCommands(pipe, arrayOf(
            makeCommand(Command.SetCommandType, "01"),
            makeCommand(Command.SwitchRF, "00"),
            makeCommand(Command.InSetRF, "02030f03"),
            makeCommand(Command.InSetProtocol, "00180101020103000400050006000708080009000a000b000c000e040f001000110012001306"),
            makeCommand(Command.InSetProtocol, "01000200050100060707"),
            makeCommand(Command.InCommRF, "360126"),
            makeCommand(Command.InSetProtocol, "04010708"),
            makeCommand(Command.InSetProtocol, "01000200"),
            makeCommand(Command.InCommRF, "36019320"),
        )) ?: return null
        if (sdd1.size < 20) { return null }
        val id1 = sdd1.sliceArray(15..19).joinToString("") { "%02X".format(it) }
        uid = id1.substring(2 until 8)

        val sel1 = execCommands(pipe, arrayOf(
            makeCommand(Command.InSetProtocol, "01010201"),
            makeCommand(Command.InCommRF, "36019370$id1"),
        )) ?: return null

        if (sel1.size < 15) { return null }
        if ((sel1[15].toInt() and 0b00000100) == 0) {
            return id1.substring(0 until 8)
        }

        val sdd2 = execCommands(pipe, arrayOf(
            makeCommand(Command.InSetProtocol, "01000200"),
            makeCommand(Command.InCommRF, "36019520"),
        )) ?: return null
        if (sdd2.size < 20) { return null }
        val id2 = sdd2.sliceArray(15..19).joinToString("") { "%02X".format(it) }
        uid += id2.substring(0 until 8)

        val sel2 = execCommands(pipe, arrayOf(
            makeCommand(Command.InSetProtocol, "01010201"),
            makeCommand(Command.InCommRF, "36019570$id2"),
        )) ?: return null

        if (sel2.size < 15) { return null }
        if ((sel2[15].toInt() and 0b00000100) == 0) {
            return uid
        }

        return null
    }

    private fun readType3IDm(pipe: Pipe): String? {
        sendACK(pipe)
        val data = execCommands(pipe, arrayOf(
            Command.SetCommandType to arrayOf(0x01),
            Command.SwitchRF to arrayOf(0x00),
            Command.InSetRF to arrayOf(0x01, 0x01, 0x0f, 0x01),
            Command.InSetProtocol to arrayOf(0x00, 0x18, 0x01, 0x01, 0x02, 0x01, 0x03, 0x00, 0x04, 0x00, 0x05, 0x00, 0x06, 0x00, 0x07, 0x08, 0x08, 0x00, 0x09, 0x00, 0x0a, 0x00, 0x0b, 0x00, 0x0c, 0x00, 0x0e, 0x04, 0x0f, 0x00, 0x10, 0x00, 0x11, 0x00, 0x12, 0x00, 0x13, 0x06),
            Command.InSetProtocol to arrayOf(0x00, 0x18),
            Command.InCommRF to arrayOf(0x6e, 0x00, 0x06, 0x00, 0xff, 0xff, 0x01, 0x00)
        ))
        if (data != null && data.size > 25) {
            return data.sliceArray(17 until 25).joinToString("") { "%02X".format(it) }
        } else {
            return null
        }
    }

    private fun send(pipe: Pipe, commands: Array<Int>) {
        val bytes = ByteArray(commands.size) { commands[it].toByte() }
        pipe.connection.bulkTransfer(pipe.outEndpoint, bytes, bytes.size, TIMEOUT)
        traceLog(">>>", bytes)
    }

    private fun receive(pipe: Pipe, size: Int? = null): ByteArray? {
        val buf = ByteArray(size ?: pipe.inEndpoint.maxPacketSize)
        val result = pipe.connection.bulkTransfer(pipe.inEndpoint, buf, buf.size, TIMEOUT)
        val data = when {
            result < 0 -> null
            result == 0 -> ByteArray(0)
            else -> buf.sliceArray(0 until result)
        }
        traceLog("<<<", data)
        return data
    }

    private fun makeCommand(command: Command, params: String): Pair<Command, Array<Int>> {
        return command to params.windowed(2, 2).map { Integer.parseInt(it, 16) }.toTypedArray()
    }

    private fun makePacket(command: Command, params: Array<Int>): Array<Int> {
        val data = mutableListOf(0xd6, command.data)
        data.addAll(params)
        val list = mutableListOf(0x00, 0x00, 0xff, 0xff, 0xff, data.size, 0, 256 - data.size)
        list.addAll(data)
        val parity = (256 - data.sum()) % 256 + 256
        list.add(parity.toInt())
        list.add(0)
        return list.toTypedArray()
    }

    private fun sendACK(pipe: Pipe) {
        send(pipe, arrayOf(0x00, 0x00, 0xff, 0x00, 0xff, 0x00))
    }

    private fun execCommand(pipe: Pipe, command: Command, params: Array<Int>): ByteArray? {
        send(pipe, makePacket(command, params))
        receive(pipe, 6) // ACK
        return receive(pipe)
    }

    private fun execCommands(pipe: Pipe, commands: Array<Pair<Command, Array<Int>>>): ByteArray? {
        var result: ByteArray? = null
        for (cmd in commands) {
            result = execCommand(pipe, cmd.first, cmd.second) ?: return null
        }
        return result
    }

    private fun traceLog(msg: String, bytes: ByteArray?) {
        if (!debug) return
        Log.d(LOG_TAG, msg + " " + (bytes?.joinToString("") { "%02X".format(it) } ?: "(null)"))
    }

    private fun log(msg: String) {
        if (!debug) return
        Log.d(LOG_TAG, msg)
    }

    private fun errorLog(msg: String) {
        if (!debug) return
        Log.e(LOG_TAG, msg)
    }
}
