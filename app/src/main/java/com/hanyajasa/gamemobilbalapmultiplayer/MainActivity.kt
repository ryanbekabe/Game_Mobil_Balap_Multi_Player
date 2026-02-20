package com.hanyajasa.gamemobilbalapmultiplayer

import android.graphics.Color
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var menuLayout: View
    private lateinit var gameLayout: View
    private lateinit var gameView: GameView
    private lateinit var statusText: TextView
    private lateinit var myIpText: TextView
    private lateinit var nameInput: EditText
    private lateinit var ipInput: EditText
    private lateinit var playAgainBtn: Button
    
    private var networkManager: NetworkManager? = null
    private var playerId = UUID.randomUUID().toString()
    private var hostIp: String? = null

    // Input states as class properties
    private var isLeftPressed = false
    private var isRightPressed = false
    private var isAccelPressed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        menuLayout = findViewById(R.id.menuLayout)
        gameLayout = findViewById(R.id.gameLayout)
        gameView = findViewById(R.id.gameView)
        statusText = findViewById(R.id.statusText)
        myIpText = findViewById(R.id.myIpText)
        nameInput = findViewById(R.id.nameInput)
        ipInput = findViewById(R.id.ipInput)
        playAgainBtn = findViewById(R.id.playAgainBtn)

        findViewById<Button>(R.id.hostButton).setOnClickListener {
            startAsHost()
        }

        findViewById<Button>(R.id.joinButton).setOnClickListener {
            startAsClient()
        }

        playAgainBtn.setOnClickListener {
            val nextSeed = System.currentTimeMillis()
            gameView.resetGame(nextSeed)
            networkManager?.sendReset(nextSeed)
            playAgainBtn.visibility = View.GONE
        }

        setupGameControls()
        displayLocalIp()
    }

    private fun displayLocalIp() {
        val ip = getLocalIpAddress()
        myIpText.text = getString(R.string.my_ip_label, ip)
    }

    private fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && address is Inet4Address) {
                        return address.hostAddress ?: getString(R.string.unknown_ip)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return "127.0.0.1"
    }

    private fun startAsHost() {
        val name = nameInput.text.toString()
        val playerCar = Car(playerId, 100f, 80f, 0f, Color.RED, name) 
        gameView.playerCar = playerCar
        
        networkManager = NetworkManager(
            onCarUpdate = { car ->
                runOnUiThread {
                    if (car.id != playerId) {
                        gameView.otherCars[car.id] = car
                    }
                }
            },
            onPlayerJoined = { id ->
                runOnUiThread {
                    Toast.makeText(this, getString(R.string.player_joined, id), Toast.LENGTH_SHORT).show()
                }
            },
            onWin = { winner ->
                runOnUiThread {
                    if (winner == "MATCH_ABORTED") {
                        showMenu()
                    } else {
                        gameView.gameWin(winner)
                        playAgainBtn.visibility = View.VISIBLE
                        Toast.makeText(this, getString(R.string.player_wins, winner), Toast.LENGTH_LONG).show()
                    }
                }
            },
            onReset = { seed ->
                runOnUiThread {
                    gameView.resetGame(seed)
                    playAgainBtn.visibility = View.GONE
                }
            },
            onItemCollected = { id ->
                runOnUiThread {
                    gameView.disableItem(id)
                }
            }
        )
        val seed = System.currentTimeMillis()
        gameView.setMazeSeed(seed)
        gameView.isHost = true
        
        val botRadio = findViewById<RadioGroup>(R.id.botRadioGroup)
        val botCount = when (botRadio.checkedRadioButtonId) {
            R.id.bot1 -> 1
            R.id.bot2 -> 2
            else -> 0
        }
        
        val speedRadio = findViewById<RadioGroup>(R.id.botSpeedRadioGroup)
        val botSpeed = when (speedRadio.checkedRadioButtonId) {
            R.id.speedSlow -> 0.25f
            R.id.speedFast -> 0.65f
            else -> 0.45f
        }
        
        gameView.setupBots(botCount, botSpeed)

        networkManager?.startHost(seed)
        
        val hostMenuBtn = findViewById<Button>(R.id.hostMenuBtn)
        hostMenuBtn.visibility = View.VISIBLE
        hostMenuBtn.setOnClickListener {
            val popup = PopupMenu(this, hostMenuBtn)
            popup.menu.add(0, 1, 0, getString(R.string.restart_menu))
            popup.menu.add(0, 2, 0, getString(R.string.finish_game_menu))
            popup.setOnMenuItemClickListener { item ->
                when(item.itemId) {
                    1 -> {
                        val nextSeed = System.currentTimeMillis()
                        gameView.resetGame(nextSeed)
                        networkManager?.sendReset(nextSeed)
                        playAgainBtn.visibility = View.GONE
                    }
                    2 -> {
                        val endMsg = "MATCH_ABORTED"
                        gameView.gameWin(endMsg)
                        networkManager?.sendWin(endMsg)
                        showMenu()
                    }
                }
                true
            }
            popup.show()
        }
        
        showGame()
        
        gameView.onPositionUpdate = { car ->
            networkManager?.sendUpdate(car)
        }

        gameView.onWin = { winner ->
            networkManager?.sendWin(winner)
            playAgainBtn.visibility = View.VISIBLE
            Toast.makeText(this, getString(R.string.you_win), Toast.LENGTH_LONG).show()
        }

        gameView.onItemPickedUp = { id ->
            networkManager?.sendItemCollected(id)
        }

        gameView.onItemDropped = { id ->
            networkManager?.sendItemDropped(id)
        }
        networkManager?.onItemDropped = { id ->
            runOnUiThread { gameView.enableItem(id) }
        }
    }

    private fun startAsClient() {
        val manualIp = ipInput.text.toString().trim()
        
        networkManager = NetworkManager(
            onCarUpdate = { car ->
                runOnUiThread {
                    if (car.id != playerId) {
                        gameView.otherCars[car.id] = car
                    }
                }
            },
            onPlayerJoined = {},
            onWin = { winner ->
                runOnUiThread {
                    if (winner == "MATCH_ABORTED") {
                        Toast.makeText(this, getString(R.string.host_ended_match), Toast.LENGTH_LONG).show()
                        showMenu()
                    } else {
                        gameView.gameWin(winner)
                        Toast.makeText(this, getString(R.string.player_wins, winner), Toast.LENGTH_LONG).show()
                    }
                }
            },
            onReset = { seed ->
                runOnUiThread {
                    gameView.resetGame(seed)
                }
            },
            onItemCollected = { id ->
                runOnUiThread {
                    gameView.disableItem(id)
                }
            }
        )

        if (manualIp.isNotEmpty()) {
            statusText.text = getString(R.string.connecting_to, manualIp)
            connectToHost(manualIp)
        } else {
            statusText.text = getString(R.string.searching_hosts)
            networkManager?.findHosts { ip, seed ->
                hostIp = ip
                runOnUiThread {
                    statusText.text = getString(R.string.found_host, ip)
                    gameView.setMazeSeed(seed)
                    connectToHost(ip)
                }
            }
        }
    }

    private fun connectToHost(ip: String) {
        val name = nameInput.text.toString()
        val playerCar = Car(playerId, 100f, 150f, 0f, Color.BLUE, name) 
        gameView.playerCar = playerCar
        
        this.hostIp = ip // Ensure local variable is set for manual IP flow
        gameView.isHost = false
        gameView.setupBots(0, 0f)
        networkManager?.connectToHost(ip)
        
        findViewById<Button>(R.id.hostMenuBtn).visibility = View.GONE
        
        showGame()
        
        gameView.onPositionUpdate = { car ->
            networkManager?.sendUpdate(car, hostIp)
        }

        gameView.onWin = { winner ->
            networkManager?.sendWin(winner) // Need to ensure client can send to host
            Toast.makeText(this, getString(R.string.you_win), Toast.LENGTH_LONG).show()
        }

        gameView.onItemPickedUp = { id ->
            networkManager?.sendItemCollected(id)
        }

        gameView.onItemDropped = { id ->
            networkManager?.sendItemDropped(id)
        }
        networkManager?.onItemDropped = { id ->
            runOnUiThread { gameView.enableItem(id) }
        }
    }

    private fun showGame() {
        menuLayout.visibility = View.GONE
        gameLayout.visibility = View.VISIBLE
    }

    private fun showMenu() {
        gameLayout.visibility = View.GONE
        menuLayout.visibility = View.VISIBLE
        networkManager?.stop()
        networkManager = null
    }

    private fun setupGameControls() {
        findViewById<Button>(R.id.leftBtn).setOnTouchListener { v, event ->
            when(event.action) {
                MotionEvent.ACTION_DOWN -> isLeftPressed = true
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    isLeftPressed = false
                    if (event.action == MotionEvent.ACTION_UP) v.performClick()
                }
            }
            gameView.handleInput(isLeftPressed, isRightPressed, isAccelPressed)
            true
        }

        findViewById<Button>(R.id.rightBtn).setOnTouchListener { v, event ->
            when(event.action) {
                MotionEvent.ACTION_DOWN -> isRightPressed = true
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    isRightPressed = false
                    if (event.action == MotionEvent.ACTION_UP) v.performClick()
                }
            }
            gameView.handleInput(isLeftPressed, isRightPressed, isAccelPressed)
            true
        }

        findViewById<Button>(R.id.accelBtn).setOnTouchListener { v, event ->
            when(event.action) {
                MotionEvent.ACTION_DOWN -> isAccelPressed = true
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    isAccelPressed = false
                    if (event.action == MotionEvent.ACTION_UP) v.performClick()
                }
            }
            gameView.handleInput(isLeftPressed, isRightPressed, isAccelPressed)
            true
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        networkManager?.stop()
    }
}