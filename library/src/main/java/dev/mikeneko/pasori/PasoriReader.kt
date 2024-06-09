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

internal interface Adapter {
    val productName: String
    val vendorID: Int
    val productID: Int

    fun matchesDevice(device: UsbDevice): Boolean {
        return device.vendorId == vendorID && device.productId == productID
    }
    suspend fun open(pipe: PasoriReader.Pipe): Boolean = true
    suspend fun close(pipe: PasoriReader.Pipe): Boolean = true

    suspend fun readTypeAUID(pipe: PasoriReader.Pipe): String?
    suspend fun readTypeFIDm(pipe: PasoriReader.Pipe): String?
}

object PasoriReader {
    sealed class Result<out R> {
        data class Success<out T>(val id: T) : Result<T>()
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

    internal data class Pipe(val connection: UsbDeviceConnection, val inEndpoint: UsbEndpoint, val outEndpoint: UsbEndpoint)

    var debug = true

    private val adapters = arrayOf(PasoriS380S(), PasoriS380P(), PasoriS300S(), PasoriS300P())

    private var inEndpoint: UsbEndpoint? = null
    private var outEndpoint: UsbEndpoint? = null
    private var connection: UsbDeviceConnection? = null

    suspend fun asyncReadIDs(context: Context): Result<String> {
        log("Call asyncReadIDs")
        close()

        val manager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
        if (manager == null) {
            errorLog(Error.NOT_SERVICE.toString())
            return Result.Failure(Error.NOT_SERVICE)
        }

        val deviceList: HashMap<String, UsbDevice> = manager.deviceList
        val result = deviceList.values.asSequence().mapNotNull { device ->
            val adapter = adapters.find { adapter -> adapter.matchesDevice(device) }
            if (adapter != null) Pair(device, adapter) else null
        }.firstOrNull()
        if (result == null) {
            errorLog(Error.NOT_FOUND.toString())
            return Result.Failure(Error.NOT_FOUND)
        }
        val (device, adapter) = result

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

        if (!adapter.open(pipe)) {
            close()
            return Result.Failure(Error.ERROR_OPEN)
        }

        log("start read...")
        try {
            while (true) {
                val type2 = adapter.readTypeAUID(pipe)
                if (type2 != null) {
                    return Result.Success(type2)
                }

                val type3 = adapter.readTypeFIDm(pipe)
                if (type3 != null) {
                    return Result.Success(type3)
                }
            }
        } finally {
            adapter.close(pipe)
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

    internal fun traceLog(msg: String, bytes: ByteArray?) {
        if (!debug) return
        Log.d(LOG_TAG, msg + " " + (bytes?.joinToString("") { "%02X".format(it) } ?: "(null)"))
    }

    internal fun log(msg: String) {
        if (!debug) return
        Log.d(LOG_TAG, msg)
    }

    internal fun errorLog(msg: String) {
        if (!debug) return
        Log.e(LOG_TAG, msg)
    }
}
