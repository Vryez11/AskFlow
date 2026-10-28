package io.askflow.pipeline.datasource.mysql

import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * 고정한 로컬 포트를 대상 DB로 잇는 TCP 중계. [start] 전에는 아무것도 듣지 않아 꺼진 DB와 같고,
 * [start] 뒤에는 같은 접속 주소로 DB에 닿는다. 공용 컨테이너를 멈추지 않고 "나중에 살아난 DB"를 만든다.
 */
class TcpForwarder(
    private val targetHost: String,
    private val targetPort: Int,
) : Closeable {
    /** 비어 있는 포트를 골라 둔다. 아직 듣지 않는다 */
    val port: Int = ServerSocket(0, 0, LOOPBACK).use { it.localPort }

    private var server: ServerSocket? = null
    private val sockets = mutableListOf<Socket>()

    fun start() {
        val server = ServerSocket(port, 50, LOOPBACK).also { server = it }
        thread(isDaemon = true, name = "tcp-forwarder-$port") {
            while (!server.isClosed) {
                val client = runCatching { server.accept() }.getOrNull() ?: break
                val target = Socket(targetHost, targetPort)
                synchronized(sockets) { sockets += listOf(client, target) }
                pipe(client, target)
                pipe(target, client)
            }
        }
    }

    private fun pipe(
        from: Socket,
        to: Socket,
    ) = thread(isDaemon = true) {
        runCatching { from.getInputStream().copyTo(to.getOutputStream()) }
        runCatching { to.shutdownOutput() }
    }

    override fun close() {
        server?.close()
        synchronized(sockets) { sockets.forEach { runCatching { it.close() } } }
    }

    private companion object {
        val LOOPBACK: InetAddress = InetAddress.getLoopbackAddress()
    }
}
