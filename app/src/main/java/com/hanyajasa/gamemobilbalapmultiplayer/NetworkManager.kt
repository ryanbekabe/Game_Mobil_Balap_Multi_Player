package com.hanyajasa.gamemobilbalapmultiplayer

import android.util.Log
import java.net.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

class NetworkManager(
    val onCarUpdate: (Car) -> Unit, 
    val onPlayerJoined: (String) -> Unit,
    val onWin: (String) -> Unit,
    val onReset: (Long) -> Unit,
    val onItemCollected: (Int) -> Unit
) {
    private val executor = Executors.newCachedThreadPool()
    private var serverSocket: ServerSocket? = null
    private var udpSocket: DatagramSocket? = null
    private var isHost = false
    private var hostAddress: InetAddress? = null
    private val clients = ConcurrentHashMap<String, SocketAddress>()
    
    private var discoveryRunning = false
    private var mazeSeed: Long = 0
    var onItemDropped: ((Int) -> Unit)? = null

    fun startHost(mazeSeed: Long) {
        this.mazeSeed = mazeSeed
        isHost = true
        discoveryRunning = true
        executor.execute {
            try {
                serverSocket = ServerSocket(8889)
                Log.d("NetworkManager", "Server started on 8889")
                while (isHost) {
                    serverSocket?.accept()?.close() // Accept and close for simple lobby placeholder
                }
            } catch (e: Exception) {
                Log.e("NetworkManager", "Server error", e)
            }
        }

        // UDP Position listener
        executor.execute {
            try {
                val socket = DatagramSocket(8890)
                udpSocket = socket
                val buffer = ByteArray(1024)
                while (isHost) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    val message = String(packet.data, 0, packet.length)
                    handleMessage(message, packet.socketAddress)
                }
                socket.close()
            } catch (e: Exception) {
                Log.e("NetworkManager", "UDP Server error", e)
            }
        }
        
        // Broadcast existence
        executor.execute {
            val socket = DatagramSocket()
            socket.broadcast = true
            while(discoveryRunning) {
                val message = "RACING_HOST_IS_HERE:$mazeSeed"
                val packet = DatagramPacket(message.toByteArray(), message.length, InetAddress.getByName("255.255.255.255"), 8888)
                socket.send(packet)
                Thread.sleep(2000)
            }
        }
    }

    fun findHosts(onHostFound: (String, Long) -> Unit) {
        executor.execute {
            try {
                val socket = DatagramSocket(8888)
                socket.soTimeout = 5000
                val buffer = ByteArray(1024)
                while (true) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                        val message = String(packet.data, 0, packet.length)
                        if (message.startsWith("RACING_HOST_IS_HERE")) {
                            val seed = if (message.contains(":")) message.split(":")[1].toLong() else 0L
                            val hostIpStr = packet.address.hostAddress ?: "0.0.0.0"
                            onHostFound(hostIpStr, seed)
                        }
                    } catch (e: SocketTimeoutException) {
                        break
                    }
                }
                socket.close()
            } catch (_: Exception) {
                Log.e("NetworkManager", "Discovery error")
            }
        }
    }

    fun connectToHost(hostIp: String) {
        isHost = false
        executor.execute {
            try {
                hostAddress = InetAddress.getByName(hostIp)
                val socket = DatagramSocket() // Menggunakan port dinamis acak
                udpSocket = socket
                
                // Pancing host agar merekam Port kita
                val hello = "HELLO:".toByteArray()
                socket.send(DatagramPacket(hello, hello.size, hostAddress, 8890))
                
                val buffer = ByteArray(1024)
                while (true) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    val message = String(packet.data, 0, packet.length)
                    if (message == "MATCH_ABORTED") {
                        onWin("MATCH_ABORTED")
                    } else {
                        handleMessage(message, packet.socketAddress)
                    }
                }
            } catch (e: Exception) {
                Log.e("NetworkManager", "Client UDP Error", e)
            }
        }
    }

    fun stop() {
        isHost = false
        discoveryRunning = false
        try { serverSocket?.close() } catch(_: Exception) {}
        try { udpSocket?.close() } catch(_: Exception) {}
        serverSocket = null
        udpSocket = null
        clients.clear()
    }

    fun sendUpdate(car: Car, targetIp: String? = null) {
        executor.execute {
            try {
                val deadFlag = if (car.isDead) 1 else 0
                val message = "UPDATE:${car.id}:${car.x}:${car.y}:${car.angle}:${car.color}:${car.name}:${car.coins}:${car.hp}:$deadFlag"
                val data = message.toByteArray()
                val socket = udpSocket ?: return@execute
                
                if (isHost) {
                    clients.values.forEach { address ->
                        val packet = DatagramPacket(data, data.size, address)
                        socket.send(packet)
                    }
                } else {
                    val addr = hostAddress ?: targetIp?.let { InetAddress.getByName(it) }
                    if (addr != null) {
                        val packet = DatagramPacket(data, data.size, addr, 8890)
                        socket.send(packet)
                    }
                }
            } catch (e: Exception) {
                Log.e("NetworkManager", "Send Update Error", e)
            }
        }
    }

    private fun sendSeedToClient(address: SocketAddress) {
        executor.execute {
            try {
                val message = "SEED:$mazeSeed"
                val data = message.toByteArray()
                val packet = DatagramPacket(data, data.size, address)
                udpSocket?.send(packet)
            } catch (_: Exception) {}
        }
    }

    fun sendWin(playerName: String) {
        val message = "WIN:$playerName"
        val data = message.toByteArray()
        executor.execute {
            try {
                val socket = udpSocket ?: return@execute
                if (isHost) {
                    clients.values.forEach { address ->
                        val packet = DatagramPacket(data, data.size, address)
                        socket.send(packet)
                    }
                } else {
                    hostAddress?.let {
                        val packet = DatagramPacket(data, data.size, it, 8890)
                        socket.send(packet)
                    }
                }
            } catch (_: Exception) {}
        }
    }

    fun sendItemCollected(itemId: Int) {
        val message = "ITEM:$itemId"
        val data = message.toByteArray()
        executor.execute {
            try {
                val socket = udpSocket ?: return@execute
                if (isHost) {
                    clients.values.forEach { address ->
                        val packet = DatagramPacket(data, data.size, address)
                        socket.send(packet)
                    }
                } else {
                    hostAddress?.let {
                        val packet = DatagramPacket(data, data.size, it, 8890)
                        socket.send(packet)
                    }
                }
            } catch (_: Exception) {}
        }
    }

    fun sendItemDropped(itemId: Int) {
        val message = "DROP:$itemId"
        val data = message.toByteArray()
        executor.execute {
            try {
                val socket = udpSocket ?: return@execute
                if (isHost) {
                    clients.values.forEach { address ->
                        val packet = DatagramPacket(data, data.size, address)
                        socket.send(packet)
                    }
                } else {
                    hostAddress?.let {
                        val packet = DatagramPacket(data, data.size, it, 8890)
                        socket.send(packet)
                    }
                }
            } catch (_: Exception) {}
        }
    }

    fun sendReset(newSeed: Long) {
        this.mazeSeed = newSeed
        val message = "RESET:$newSeed"
        val data = message.toByteArray()
        executor.execute {
            try {
                val socket = udpSocket ?: return@execute
                if (isHost) {
                    clients.values.forEach { address ->
                        val packet = DatagramPacket(data, data.size, address)
                        socket.send(packet)
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private fun handleMessage(message: String, fromAddress: SocketAddress) {
        if (message.startsWith("UPDATE:")) {
            val parts = message.split(":")
            if (parts.size >= 10) {
                val car = Car(parts[1], parts[2].toFloat(), parts[3].toFloat(), parts[4].toFloat(), parts[5].toInt(), parts[6], coins = parts[7].toInt(), hp = parts[8].toInt(), isDead = parts[9] == "1")
                onCarUpdate(car)
                
                if (isHost) {
                    if (!clients.containsKey(car.id)) {
                        clients[car.id] = fromAddress
                        onPlayerJoined(car.id)
                        sendSeedToClient(fromAddress)
                    }
                    // Relay this update to other clients
                    relayUpdate(message, car.id)
                }
            }
        } else if (message.startsWith("WIN:")) {
            val winnerName = message.substring(4)
            onWin(winnerName)
        } else if (message.startsWith("RESET:")) {
            val newSeed = message.substring(6).toLong()
            onReset(newSeed)
        } else if (message.startsWith("SEED:")) {
            val seed = message.substring(5).toLong()
            onReset(seed) // Use onReset to apply the seed as it's the same logic
        } else if (message.startsWith("ITEM:")) {
            val itemId = message.substring(5).toInt()
            onItemCollected(itemId)
            if (isHost) {
                // Relay to other clients
                val data = message.toByteArray()
                executor.execute {
                    try {
                        val socket = udpSocket ?: return@execute
                        clients.values.forEach { address ->
                            if (address != fromAddress) {
                                val packet = DatagramPacket(data, data.size, address)
                                socket.send(packet)
                            }
                        }
                    } catch (_: Exception) {}
                }
            }
        } else if (message.startsWith("DROP:")) {
            val itemId = message.substring(5).toInt()
            onItemDropped?.invoke(itemId)
            if (isHost) {
                // Relay to other clients
                val data = message.toByteArray()
                executor.execute {
                    try {
                        val socket = udpSocket ?: return@execute
                        clients.values.forEach { address ->
                            if (address != fromAddress) {
                                val packet = DatagramPacket(data, data.size, address)
                                socket.send(packet)
                            }
                        }
                    } catch (_: Exception) {}
                }
            }
        }
    }

    private fun relayUpdate(message: String, senderId: String) {
        val data = message.toByteArray()
        executor.execute {
            try {
                val socket = udpSocket ?: return@execute
                clients.filter { it.key != senderId }.values.forEach { address ->
                    val packet = DatagramPacket(data, data.size, address)
                    socket.send(packet)
                }
            } catch (e: Exception) {
                Log.e("NetworkManager", "Relay error", e)
            }
        }
    }
    // old duplicate stop removed
}
