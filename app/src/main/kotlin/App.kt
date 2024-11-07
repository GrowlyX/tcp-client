package io.liftgate.gvf.app

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.concurrent.thread

data class Client(
    val socket: Socket,
    var username: String? = null,
    val listener: Client.(String) -> Unit,
    val disconnectionListener: Client.() -> Unit
) : () -> Unit
{
    val output = PrintWriter(socket.getOutputStream(), true)
    val input = BufferedReader(InputStreamReader(socket.inputStream))

    lateinit var thread: Thread

    fun start()
    {
        thread = Thread(this)
        thread.start()
    }

    fun destroy()
    {
        thread.interrupt()
    }

    fun broadcast(message: String)
    {
        output.println(message)
    }

    override fun invoke()
    {
        while (true)
        {
            val readLine = runCatching {
                input.readLine()
            }.onFailure {
                Logger.getGlobal().log(Level.WARNING, "I/O error, with client", it)
            }.getOrNull()
                ?: return run {
                    disconnectionListener()
                }

            kotlin.runCatching {
                listener(readLine)
            }.onFailure {
                Logger.getGlobal().log(Level.WARNING, "Subscription error on client $username", it)
            }
        }
    }
}

data class Server(val port: Int) : () -> Unit
{
    val server = ServerSocket(1001)
    val chatHistory = LinkedList<String>()
    val connectedClients = CopyOnWriteArrayList<Client>()

    lateinit var thread: Thread
    fun start()
    {
        thread = Thread(this)
        thread.start()

        thread {
            while (true)
            {
                runCatching {
                    heartbeat()
                }.onFailure {
                    it.printStackTrace()
                }

                Thread.sleep(250L)
            }
        }
    }

    private val dateFormat = SimpleDateFormat("HH.mm.ss")
    fun broadcastToAll(message: String, from: Client? = null)
    {
        val formattedMessage = "${dateFormat.format(Date())} $message"
        recordChatMessage(formattedMessage)

        connectedClients.forEach {
            if (from != null && from == it)
            {
                return@forEach
            }

            it.broadcast(formattedMessage)
        }
    }

    fun deregisterClient(client: Client)
    {
        client.destroy()
        connectedClients -= client
    }

    private val mentionExtractor = "(^|\\s)@([A-z]+)\\b".toRegex()
    fun registerNewClient(socket: Socket)
    {
        val client = Client(socket, listener = { incoming ->
            if (username == null)
            {
                if (connectedClients.any { it.username != null && it.username == incoming })
                {
                    broadcast("This username is already taken! Please choose another one!")
                    return@Client
                }

                username = incoming

                // TODO: reversed?
                broadcast(
                    "You are connected with ${
                        connectedClients.size - 1
                    } other users: ${
                        connectedClients
                            .filterNot { client -> client == this }
                            .filterNot { client -> client.username == null }
                            .map(Client::username)
                    }")

                chatHistory.forEach { history ->
                    broadcast(history)
                }

                broadcastToAll("*$username has joined the chat*")
                return@Client
            }

            broadcastToAll("<$username> $incoming")

            val extracted = mentionExtractor.matchEntire(incoming)
                ?.groupValues?.firstOrNull()?.removePrefix("@")

            if (extracted != null)
            {
                val matchingUser = connectedClients
                    .firstOrNull { client -> client.username == extracted }

                matchingUser?.broadcast("\\u0007")
            }
        }, disconnectionListener = {
            broadcastToAll("*$username has left the chat*")
            deregisterClient(this)
        })
        client.start()
        client.broadcast("Welcome to my chat server! What is your nickname?")

        connectedClients += client
    }

    fun recordChatMessage(message: String)
    {
        if (chatHistory.size == 10)
        {
            chatHistory.removeLast()
        }

        chatHistory += message
    }

    fun heartbeat()
    {
        connectedClients.filterNot { it.socket.isConnected }
            .forEach {
                Logger.getGlobal().info("Discarding disconnected client ${it.username}")
                connectedClients.remove(it)
            }
    }

    override fun invoke()
    {
        while (true)
        {
            val socket = kotlin
                .runCatching {
                    server.accept()
                }
                .onFailure {
                    Logger.getGlobal().log(Level.WARNING, "I/O error", it)
                }
                .getOrNull()
                ?: continue

            registerNewClient(socket)
        }
    }
}

fun main()
{
    Server(1001).start()
    println("started!")
}
