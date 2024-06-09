package dev.mikeneko.pasori

import kotlinx.coroutines.delay

private const val SLOT_NUMBER: Byte = 0x00
private const val TIMEOUT = 50

internal class PasoriS300S : PasoriS300Adapter("RC-S300/S", 0x054c, 0x0dc8) {}
internal class PasoriS300P: PasoriS300Adapter("RC-S300/P", 0x054c, 0x0dc9) {}

internal open class PasoriS300Adapter(
    override val productName: String,
    override val vendorID: Int,
    override val productID: Int
) : Adapter {
    private object SequenceNo {
        private var value: Byte = 0

        @Synchronized
        fun next(): Byte {
            val no = value.toInt() + 1
            if (no > 0xFF) {
                value = 0
            } else {
                value = no.toByte()
            }
            return value
        }
    }

    private enum class Command(val data: IntArray, val wait: Long) {
        StartTransparentSession(intArrayOf(0xFF, 0x50, 0x00, 0x00, 0x02, 0x81, 0x00, 0x00), 0),
        EndTransparentSession(intArrayOf(0xFF, 0x50, 0x00, 0x00, 0x02, 0x82, 0x00, 0x00), 0),
        TurnOnTheRF(intArrayOf(0xFF, 0x50, 0x00, 0x00, 0x02, 0x84, 0x00, 0x00), 25),
        TurnOffTheRF(intArrayOf(0xFF, 0x50, 0x00, 0x00, 0x02, 0x83, 0x00, 0x00), 30),
        SwitchProtocolTypeA(intArrayOf(0xFF, 0x50, 0x00, 0x02, 0x04, 0x8F, 0x02, 0x00, 0x03, 0x00), 0),
        SwitchProtocolTypeF(intArrayOf(0xFF, 0x50, 0x00, 0x02, 0x04, 0x8F, 0x02, 0x03, 0x00, 0x00), 0),
        CommunicateThruEX(intArrayOf(0xFF, 0x50, 0x00, 0x01), 0),
        GetData(intArrayOf(0xFF, 0xCA, 0x00, 0x00), 0);

        fun appendData(vararg data: Int): IntArray {
            return this.data + data
        }
    }

    override suspend fun open(pipe: PasoriReader.Pipe): Boolean {
        sendCommand(pipe, Command.EndTransparentSession) ?: return false
        sendCommand(pipe, Command.StartTransparentSession) ?: return false
        sendCommand(pipe, Command.TurnOffTheRF) ?: return false
        sendCommand(pipe, Command.TurnOnTheRF) ?: return false
        return true
    }
    override suspend fun readTypeAUID(pipe: PasoriReader.Pipe): String? {
        delay(50)
        sendCommand(pipe, Command.SwitchProtocolTypeA) ?: return null
        val result = sendCommand(pipe, Command.GetData) ?: return null
        if (result.size < 10) {
            return null
        }
        val size = result[1].toInt() + (result[2].toInt() shl 0x08) + (result[3].toInt() shl 0x10) + (result[4].toInt() shl 0x18)
        val end = 10 + size
        if (result[end - 2] != 0x90.toByte() || result[end - 1] != 0x00.toByte()) {
            return null
        }
        val uid = result.sliceArray(10 until end - 2)
        return uid.joinToString("") { "%02X".format(it) }
    }

    override suspend fun readTypeFIDm(pipe: PasoriReader.Pipe): String? {
        delay(50)
        sendCommand(pipe, Command.SwitchProtocolTypeF) ?: return null
        val polling = makeFelicaPollingCommand()
        val data = toRDR(toByteArray(*polling), SequenceNo.next())
        PasoriReader.log("readFelica")
        val result = transfer(pipe, data) ?: return null
        if (result.size < 24) {
            return null
        }
        val size = result[23].toInt()
        val end = 24 + size
        if (result[end] != 0x90.toByte() || result[end + 1] != 0x00.toByte()) {
            return null
        }
        val buffer = result.sliceArray(24 until end)
        if (buffer[1] != 0x01.toByte()) {
            return null
        }
        val idm = buffer.sliceArray(2 until 10)
        return idm.joinToString("") { "%02X".format(it) }
    }

    private suspend fun sendCommand(pipe: PasoriReader.Pipe, command: Command): ByteArray? {
        PasoriReader.log("cmd:" + command.name)
        val data = toRDR(toByteArray(*command.data), SequenceNo.next())
        val result = transfer(pipe, data) ?: return null
        if (command.wait > 0) {
            delay(command.wait)
        }
        return result
    }

    private fun toByteArray(vararg numbers: Int): ByteArray {
        return numbers.map { it.toByte() }.toByteArray()
    }

    private fun toRDR(commands: ByteArray, seqNo: Byte): ByteArray {
        val len = commands.size

        val header = byteArrayOf(
            0x6B,
            ((len shr 0x00) and 0xFF).toByte(),
            ((len shr 0x08) and 0xFF).toByte(),
            ((len shr 0x10) and 0xFF).toByte(),
            ((len shr 0x18) and 0xFF).toByte(),
            SLOT_NUMBER,
            seqNo,
            0x00,
            0x00,
            0x00)
        return header + commands
    }

    private fun makeFelicaPollingCommand(): IntArray {
        val tag = intArrayOf(0x95)
        val polling = intArrayOf(0x06, 0x00, 0xFF, 0xFF, 0x00, 0x00)
        val len = intArrayOf(0x82, (polling.size shr 0x08) and 0xFF, polling.size and 0xFF)
        val t = TIMEOUT * 1000
        val timeout = intArrayOf(0x5F, 0x46, 0x04, (t shr 0x00) and 0xFF, (t shr 0x08) and 0xFF, (t shr 0x10) and 0xFF, (t shr 0x18) and 0xFF)
        val buffer = timeout + tag + len + polling
        val data = intArrayOf(0x00, (buffer.size shr 0x08) and 0xFF, buffer.size and 0xFF) + buffer
        val command = Command.CommunicateThruEX
        return command.appendData(*data, 0x00, 0x00, 0x00)
    }

    private suspend fun transfer(pipe: PasoriReader.Pipe, data: ByteArray): ByteArray? {
        val start = System.currentTimeMillis()
        var end = start
        while (end - start < TIMEOUT * 3) {
            pipe.connection.bulkTransfer(pipe.outEndpoint, data, data.size, TIMEOUT)
            PasoriReader.traceLog(">>>", data)
            val buffer = receive(pipe)
            delay(TIMEOUT.toLong() + 10)
            if (buffer != null && buffer.size > 10 && buffer[0] == 0x83.toByte() && buffer[5] == data[5] && buffer[6] == data[6]) {
                val status = (buffer[7].toInt() shr 0x06) and 0x10
                if (status == 0) {
                    return buffer
                }
            }
            end = System.currentTimeMillis()
        }
        PasoriReader.log("timeout")
        return null
    }

    private fun receive(pipe: PasoriReader.Pipe, length: Int? = null): ByteArray? {
        val size = length ?: pipe.inEndpoint.maxPacketSize
        val buffer = ByteArray(size)
        val result = pipe.connection.bulkTransfer(pipe.inEndpoint, buffer, size, TIMEOUT)
        PasoriReader.traceLog("<<<", buffer)
        return if (result < 0) {
            null
        } else {
            buffer
        }
    }
}
