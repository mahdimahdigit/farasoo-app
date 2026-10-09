package ir.farasoo.app

import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class PacketForwarder(
    private val vpnService: VpnService,
    private val tunInterface: ParcelFileDescriptor
) {
    companion object {
        private const val TAG = "PacketForwarder"
        private const val BUFFER_SIZE = 32767
    }

    private val running = AtomicInteger(1)
    private val udpPool: ExecutorService = Executors.newFixedThreadPool(4)
    private val tcpPool: ExecutorService = Executors.newCachedThreadPool()
    private val tcpConnections = ConcurrentHashMap<String, TcpConnection>()

    private var input: FileInputStream? = null
    private var output: FileOutputStream? = null
    private var thread: Thread? = null

    private var packetsRead = 0
    private var packetsUDP = 0
    private var packetsTCP = 0

    fun start() {
        try {
            val fd = tunInterface.fileDescriptor
            input = FileInputStream(fd)
            output = FileOutputStream(fd)
            Log.d(TAG, "Streams opened OK")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open streams", e)
            return
        }

        thread = thread(name = "ForwarderMain") {
            val buffer = ByteArray(BUFFER_SIZE)
            Log.d(TAG, "Forwarder thread started")
            while (running.get() == 1) {
                try {
                    val length = input!!.read(buffer)
                    if (length <= 0) continue

                    packetsRead++
                    if (packetsRead <= 20 || packetsRead % 100 == 0) {
                        Log.d(TAG, "Read packet #$packetsRead len=$length")
                    }
                    val packet = buffer.copyOf(length)
                    handlePacket(packet)
                } catch (e: Exception) {
                    if (running.get() == 1) {
                        Log.e(TAG, "read error: ${e.message}")
                    }
                    break
                }
            }
            Log.d(TAG, "Forwarder stopped. Read=$packetsRead UDP=$packetsUDP TCP=$packetsTCP")
        }
    }

    fun stop() {
        running.set(0)
        try { input?.close() } catch (_: Exception) {}
        try { output?.close() } catch (_: Exception) {}
        for (conn in tcpConnections.values) conn.close()
        tcpConnections.clear()
        udpPool.shutdownNow()
        tcpPool.shutdownNow()
        thread?.interrupt()
    }

    private fun handlePacket(packet: ByteArray) {
        if (packet.size < 20) return

        val version = (packet[0].toInt() ushr 4) and 0xF
        if (version != 4) return

        val ihl = (packet[0].toInt() and 0xF) * 4
        if (packet.size < ihl) return

        val protocol = packet[9].toInt() and 0xFF
        val srcIP = packet.copyOfRange(12, 16)
        val dstIP = packet.copyOfRange(16, 20)

        when (protocol) {
            17 -> {
                packetsUDP++
                handleUDP(packet, ihl, srcIP, dstIP)
            }
            6 -> {
                packetsTCP++
                handleTCP(packet, ihl, srcIP, dstIP)
            }
        }
    }

    // ============================================================
    // UDP
    // ============================================================
    private fun handleUDP(packet: ByteArray, ihl: Int, srcIP: ByteArray, dstIP: ByteArray) {
        if (packet.size < ihl + 8) return

        val srcPort = readU16(packet, ihl)
        val dstPort = readU16(packet, ihl + 2)
        val udpLen = readU16(packet, ihl + 4)
        val payloadLen = udpLen - 8
        if (payloadLen <= 0 || ihl + 8 + payloadLen > packet.size) return

        val payload = packet.copyOfRange(ihl + 8, ihl + 8 + payloadLen)

        udpPool.submit {
            try {
                val socket = DatagramSocket()
                if (!vpnService.protect(socket)) {
                    Log.e(TAG, "UDP protect failed")
                    return@submit
                }
                socket.soTimeout = 5000

                val dstAddr = InetAddress.getByAddress(dstIP)
                socket.send(DatagramPacket(payload, payload.size, dstAddr, dstPort))

                val respBuf = ByteArray(BUFFER_SIZE)
                val respPkt = DatagramPacket(respBuf, respBuf.size)
                socket.receive(respPkt)
                socket.close()

                val respPayload = respPkt.data.copyOf(respPkt.length)
                val respPacket = buildUdpPacket(
                    srcIP = dstIP, srcPort = dstPort,
                    dstIP = srcIP, dstPort = srcPort,
                    payload = respPayload
                )
                writeToTun(respPacket)
            } catch (e: Exception) {
                // timeout — drop
            }
        }
    }

    // ============================================================
    // TCP
    // ============================================================
    private fun handleTCP(packet: ByteArray, ihl: Int, srcIP: ByteArray, dstIP: ByteArray) {
        if (packet.size < ihl + 20) return

        val srcPort = readU16(packet, ihl)
        val dstPort = readU16(packet, ihl + 2)
        val seqNum = readU32(packet, ihl + 4)
        val dataOffset = ((packet[ihl + 12].toInt() ushr 4) and 0xF) * 4
        if (dataOffset < 20) return

        val flags = packet[ihl + 13].toInt() and 0xFF
        val isSyn = (flags and 0x02) != 0
        val isAck = (flags and 0x10) != 0
        val isFin = (flags and 0x01) != 0
        val isRst = (flags and 0x04) != 0
        val isPsh = (flags and 0x08) != 0

        val key = "${ipStr(srcIP)}:$srcPort-${ipStr(dstIP)}:$dstPort"

        if (isSyn && !isAck) {
            if (tcpConnections.containsKey(key)) return

            tcpPool.submit {
                try {
                    val socket = Socket()
                    if (!vpnService.protect(socket)) {
                        Log.e(TAG, "TCP protect failed")
                        return@submit
                    }
                    socket.tcpNoDelay = true
                    socket.soTimeout = 15000
                    socket.connect(
                        InetSocketAddress(InetAddress.getByAddress(dstIP), dstPort),
                        10000
                    )

                    val conn = TcpConnection(
                        socket = socket,
                        srcIP = srcIP, srcPort = srcPort,
                        dstIP = dstIP, dstPort = dstPort,
                        mySeq = 1000,
                        peerSeq = seqNum + 1
                    )
                    tcpConnections[key] = conn

                    // SYN-ACK
                    writeToTun(buildTcpPacket(
                        srcIP = dstIP, srcPort = dstPort,
                        dstIP = srcIP, dstPort = srcPort,
                        seq = conn.mySeq, ack = conn.peerSeq,
                        flags = 0x12,
                        payload = null
                    ))

                    startTcpReader(conn, key)
                } catch (e: Exception) {
                    Log.e(TAG, "TCP connect failed: ${e.message}")
                    writeToTun(buildTcpPacket(
                        srcIP = dstIP, srcPort = dstPort,
                        dstIP = srcIP, dstPort = srcPort,
                        seq = 0, ack = seqNum + 1,
                        flags = 0x14,
                        payload = null
                    ))
                }
            }
            return
        }

        val conn = tcpConnections[key] ?: return

        if (isRst) {
            conn.close()
            tcpConnections.remove(key)
            return
        }

        if (isPsh && packet.size > ihl + dataOffset) {
            val payload = packet.copyOfRange(ihl + dataOffset, packet.size)
            try {
                conn.socket.getOutputStream().write(payload)
                conn.socket.getOutputStream().flush()
                conn.mySeq += payload.size

                writeToTun(buildTcpPacket(
                    srcIP = dstIP, srcPort = dstPort,
                    dstIP = srcIP, dstPort = srcPort,
                    seq = conn.mySeq, ack = conn.peerSeq,
                    flags = 0x10,
                    payload = null
                ))
            } catch (e: Exception) {
                Log.e(TAG, "TCP write failed: ${e.message}")
                conn.close()
                tcpConnections.remove(key)
            }
        }

        if (isFin) {
            writeToTun(buildTcpPacket(
                srcIP = dstIP, srcPort = dstPort,
                dstIP = srcIP, dstPort = srcPort,
                seq = conn.mySeq, ack = seqNum + 1,
                flags = 0x11,
                payload = null
            ))
            conn.close()
            tcpConnections.remove(key)
        }
    }

    private fun startTcpReader(conn: TcpConnection, key: String) {
        tcpPool.submit {
            val buf = ByteArray(BUFFER_SIZE)
            try {
                val ins = conn.socket.getInputStream()
                while (running.get() == 1 && !conn.socket.isClosed) {
                    val n = ins.read(buf)
                    if (n <= 0) break

                    val payload = buf.copyOf(n)
                    writeToTun(buildTcpPacket(
                        srcIP = conn.dstIP, srcPort = conn.dstPort,
                        dstIP = conn.srcIP, dstPort = conn.srcPort,
                        seq = conn.peerSeq, ack = conn.mySeq,
                        flags = 0x18,
                        payload = payload
                    ))
                    conn.peerSeq += n
                }

                writeToTun(buildTcpPacket(
                    srcIP = conn.dstIP, srcPort = conn.dstPort,
                    dstIP = conn.srcIP, dstPort = conn.srcPort,
                    seq = conn.peerSeq, ack = conn.mySeq,
                    flags = 0x11,
                    payload = null
                ))
            } catch (e: Exception) {
                // closed
            } finally {
                conn.close()
                tcpConnections.remove(key)
            }
        }
    }

    // ============================================================
    // Builders
    // ============================================================
    private fun buildUdpPacket(
        srcIP: ByteArray, srcPort: Int,
        dstIP: ByteArray, dstPort: Int,
        payload: ByteArray
    ): ByteArray {
        val udpLen = 8 + payload.size
        val totalLen = 20 + udpLen
        val pkt = ByteArray(totalLen)

        pkt[0] = 0x45
        writeU16(pkt, 2, totalLen)
        pkt[8] = 64
        pkt[9] = 17
        srcIP.copyInto(pkt, 12)
        dstIP.copyInto(pkt, 16)
        writeU16(pkt, 10, checksum(pkt, 0, 20))

        writeU16(pkt, 20, srcPort)
        writeU16(pkt, 22, dstPort)
        writeU16(pkt, 24, udpLen)
        payload.copyInto(pkt, 28)

        return pkt
    }

    private fun buildTcpPacket(
        srcIP: ByteArray, srcPort: Int,
        dstIP: ByteArray, dstPort: Int,
        seq: Int, ack: Int,
        flags: Int,
        payload: ByteArray?
    ): ByteArray {
        val payloadLen = payload?.size ?: 0
        val totalLen = 20 + 20 + payloadLen
        val pkt = ByteArray(totalLen)

        pkt[0] = 0x45
        writeU16(pkt, 2, totalLen)
        pkt[8] = 64
        pkt[9] = 6
        srcIP.copyInto(pkt, 12)
        dstIP.copyInto(pkt, 16)
        writeU16(pkt, 10, checksum(pkt, 0, 20))

        writeU16(pkt, 20, srcPort)
        writeU16(pkt, 22, dstPort)
        writeU32(pkt, 24, seq)
        writeU32(pkt, 28, ack)
        pkt[32] = 0x50
        pkt[33] = flags.toByte()
        writeU16(pkt, 34, 65535)

        if (payload != null && payloadLen > 0) {
            payload.copyInto(pkt, 40)
        }

        val tcpSum = tcpChecksum(srcIP, dstIP, pkt, 20, 20 + payloadLen)
        writeU16(pkt, 36, tcpSum)

        return pkt
    }

    // ============================================================
    // Helpers
    // ============================================================
    private fun writeToTun(packet: ByteArray) {
        try {
            output?.write(packet)
            output?.flush()
        } catch (e: Exception) {
            Log.e(TAG, "write to tun failed: ${e.message}")
        }
    }

    private fun readU16(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)

    private fun readU32(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xFF) shl 24) or
        ((data[offset + 1].toInt() and 0xFF) shl 16) or
        ((data[offset + 2].toInt() and 0xFF) shl 8) or
        (data[offset + 3].toInt() and 0xFF)

    private fun writeU16(data: ByteArray, offset: Int, value: Int) {
        data[offset] = ((value ushr 8) and 0xFF).toByte()
        data[offset + 1] = (value and 0xFF).toByte()
    }

    private fun writeU32(data: ByteArray, offset: Int, value: Int) {
        data[offset] = ((value ushr 24) and 0xFF).toByte()
        data[offset + 1] = ((value ushr 16) and 0xFF).toByte()
        data[offset + 2] = ((value ushr 8) and 0xFF).toByte()
        data[offset + 3] = (value and 0xFF).toByte()
    }

    private fun ipStr(ip: ByteArray): String =
        "${ip[0].toInt() and 0xFF}.${ip[1].toInt() and 0xFF}.${ip[2].toInt() and 0xFF}.${ip[3].toInt() and 0xFF}"

    private fun checksum(data: ByteArray, offset: Int, length: Int): Int {
        var sum = 0
        var i = offset
        while (i < offset + length - 1) {
            sum += readU16(data, i)
            i += 2
        }
        if (length % 2 == 1) sum += (data[offset + length - 1].toInt() and 0xFF) shl 8
        while (sum shr 16 != 0) sum = (sum and 0xFFFF) + (sum shr 16)
        return sum.inv() and 0xFFFF
    }

    private fun tcpChecksum(
        srcIP: ByteArray, dstIP: ByteArray,
        data: ByteArray, offset: Int, length: Int
    ): Int {
        val pseudo = ByteArray(12 + length)
        srcIP.copyInto(pseudo, 0)
        dstIP.copyInto(pseudo, 4)
        pseudo[9] = 6
        writeU16(pseudo, 10, length)
        data.copyInto(pseudo, 12, offset, offset + length)

        var sum = 0
        var i = 0
        while (i < pseudo.size - 1) {
            sum += readU16(pseudo, i)
            i += 2
        }
        if (pseudo.size % 2 == 1) sum += (pseudo[pseudo.size - 1].toInt() and 0xFF) shl 8
        while (sum shr 16 != 0) sum = (sum and 0xFFFF) + (sum shr 16)
        return sum.inv() and 0xFFFF
    }

    data class TcpConnection(
        val socket: Socket,
        val srcIP: ByteArray,
        val srcPort: Int,
        val dstIP: ByteArray,
        val dstPort: Int,
        var mySeq: Int,
        var peerSeq: Int
    ) {
        fun close() {
            try { socket.close() } catch (_: Exception) {}
        }
    }
}
