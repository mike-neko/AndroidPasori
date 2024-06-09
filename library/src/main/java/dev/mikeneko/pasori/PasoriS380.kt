package dev.mikeneko.pasori

import kotlinx.coroutines.delay

private const val TIMEOUT = 50

internal class PasoriS380S : PasoriS380Adapter("RC-S380/S", 0x054c, 0x06c1) {}
internal class PasoriS380P: PasoriS380Adapter("RC-S380/P", 0x054c, 0x06c3) {}

internal open class PasoriS380Adapter(
    override val productName: String,
    override val vendorID: Int,
    override val productID: Int
) : Adapter {

    private enum class Command(val data: Int) {
        InSetRF(0x00),
        InSetProtocol(0x02),
        InCommRF(0x04),
        SwitchRF(0x06),
        SetCommandType(0x2A)
    }

    override suspend fun readTypeAUID(pipe: PasoriReader.Pipe): String? {
        delay(50)
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

    override suspend fun readTypeFIDm(pipe: PasoriReader.Pipe): String? {
        delay(50)
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

    private fun makeCommand(command: Command, params: String): Pair<Command, Array<Int>> {
        return command to params.windowed(2, 2).map { Integer.parseInt(it, 16) }.toTypedArray()
    }

    private fun makePacket(command: Command, params: Array<Int>): Array<Int> {
        val data = mutableListOf(0xd6, command.data)
        data.addAll(params)
        val list = mutableListOf(0x00, 0x00, 0xff, 0xff, 0xff, data.size, 0, 256 - data.size)
        list.addAll(data)
        val parity = (256 - data.sum()) % 256 + 256
        list.add(parity)
        list.add(0)
        return list.toTypedArray()
    }

    private fun sendACK(pipe: PasoriReader.Pipe) {
        send(pipe, arrayOf(0x00, 0x00, 0xff, 0x00, 0xff, 0x00))
    }

    private fun execCommand(pipe: PasoriReader.Pipe, command: Command, params: Array<Int>): ByteArray? {
        send(pipe, makePacket(command, params))
        receive(pipe, 6) // ACK
        return receive(pipe)
    }

    private fun execCommands(pipe: PasoriReader.Pipe, commands: Array<Pair<Command, Array<Int>>>): ByteArray? {
        var result: ByteArray? = null
        for (cmd in commands) {
            result = execCommand(pipe, cmd.first, cmd.second) ?: return null
        }
        return result
    }

    private fun send(pipe: PasoriReader.Pipe, commands: Array<Int>) {
        val bytes = ByteArray(commands.size) { commands[it].toByte() }
        pipe.connection.bulkTransfer(pipe.outEndpoint, bytes, bytes.size, TIMEOUT)
        PasoriReader.traceLog(">>>", bytes)
    }

    private fun receive(pipe: PasoriReader.Pipe, size: Int? = null): ByteArray? {
        val buf = ByteArray(size ?: pipe.inEndpoint.maxPacketSize)
        val result = pipe.connection.bulkTransfer(pipe.inEndpoint, buf, buf.size, TIMEOUT)
        val data = when {
            result < 0 -> null
            result == 0 -> ByteArray(0)
            else -> buf.sliceArray(0 until result)
        }
        PasoriReader.traceLog("<<<", data)
        return data
    }
}
