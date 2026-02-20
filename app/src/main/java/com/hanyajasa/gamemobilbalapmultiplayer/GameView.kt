package com.hanyajasa.gamemobilbalapmultiplayer

import android.content.Context
import android.graphics.*
import android.media.AudioManager
import android.media.ToneGenerator
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

enum class ItemType { NITRO, TELEPORT, COIN, HEALTH }

data class Item(
    val id: Int,
    val x: Float,
    val y: Float,
    val type: ItemType,
    var isActive: Boolean = true
)

data class SkidMark(val x: Float, val y: Float, var alpha: Int = 200)

enum class ZoneType { ICE, MUD }
data class Zone(val rect: RectF, val type: ZoneType)
data class BlinkingWall(val rect: RectF, val offsetMs: Long)

data class Particle(
    var x: Float,
    var y: Float,
    var vx: Float,
    var vy: Float,
    var life: Int,
    val maxLife: Int,
    val color: Int,
    val size: Float
)

class GameView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // Virtual Coordinate System (Fixed for all devices)
    private val VIRTUAL_WIDTH = 1000f
    private val VIRTUAL_HEIGHT = 1500f // Vertical maze
    private var screenScaleX = 1f
    private var screenScaleY = 1f

    var playerCar: Car? = null
    val otherCars = mutableMapOf<String, Car>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    
    var onPositionUpdate: ((Car) -> Unit)? = null
    var onWin: ((String) -> Unit)? = null
    var onItemPickedUp: ((Int) -> Unit)? = null
    var onItemDropped: ((Int) -> Unit)? = null
    
    var isHost = false
    val botPlayers = mutableListOf<Car>()
    private var botSpeedMultiplier = 0.45f

    private val items = mutableListOf<Item>()
    private val skidMarks = mutableListOf<SkidMark>()
    private val particles = mutableListOf<Particle>()
    private var nitroTime = 0L
    private var teleportIndicatorTime = 0L // Animasi kedip sebelum teleport

    private val toneGen = ToneGenerator(AudioManager.STREAM_MUSIC, 100)

    private var startTime: Long = 0L
    private var finishTime: Long = 0L
    private val TARGET_COINS = 10

    fun disableItem(id: Int) {
        items.find { it.id == id }?.isActive = false
    }

    fun enableItem(id: Int) {
        items.find { it.id == id }?.isActive = true
    }

    private var leftDown = false
    private var rightDown = false
    private var accelDown = false
    
    private var gameEnded = false
    private var winnerName = ""
    private var winnerTimeStr = ""

    private val mazeWalls = mutableListOf<RectF>()
    private val blinkingWalls = mutableListOf<BlinkingWall>()
    private val zones = mutableListOf<Zone>()
    private val finishLine = RectF()
    private var mazeSeed: Long = 0

    init {
        post(object : Runnable {
            override fun run() {
                if (!gameEnded) {
                    update()
                }
                invalidate()
                postDelayed(this, 16)
            }
        })
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        screenScaleX = w.toFloat() / VIRTUAL_WIDTH
        screenScaleY = h.toFloat() / VIRTUAL_HEIGHT
        setupMaze()
    }

    fun setMazeSeed(seed: Long) {
        this.mazeSeed = seed
        setupMaze()
    }

    private fun setupMaze() {
        mazeWalls.clear()
        blinkingWalls.clear()
        zones.clear()
        items.clear()
        val random = java.util.Random(mazeSeed)
        val thickness = 30f
        
        // Outer boundaries (Virtual space)
        mazeWalls.add(RectF(0f, 0f, VIRTUAL_WIDTH, thickness)) // Top
        mazeWalls.add(RectF(0f, VIRTUAL_HEIGHT - thickness, VIRTUAL_WIDTH, VIRTUAL_HEIGHT)) // Bottom
        mazeWalls.add(RectF(0f, 0f, thickness, VIRTUAL_HEIGHT)) // Left
        mazeWalls.add(RectF(VIRTUAL_WIDTH - thickness, 0f, VIRTUAL_WIDTH, VIRTUAL_HEIGHT)) // Right

        val cols = 5
        val rows = 8
        val cellW = VIRTUAL_WIDTH / cols
        val cellH = VIRTUAL_HEIGHT / rows

        val exitCol = random.nextInt(cols)
        val exitRow = 4 + random.nextInt(4) // Random di bagian bawah map
        val exitX = exitCol * cellW + cellW / 2
        val exitY = exitRow * cellH + cellH / 2
        finishLine.set(exitX - 60f, exitY - 60f, exitX + 60f, exitY + 60f)

        for (i in 0 until cols) {
            for (j in 0 until rows) {
                // Keep Start (top left) and Finish clear
                if ((i <= 1 && j <= 1) || (i == exitCol && j == exitRow)) continue
                
                val randVal = random.nextFloat()
                if (randVal < 0.25f) {
                    when (random.nextInt(3)) {
                        0 -> mazeWalls.add(RectF(i * cellW, j * cellH, i * cellW + thickness, j * cellH + cellH * 0.8f))
                        1 -> mazeWalls.add(RectF(i * cellW, j * cellH, i * cellW + cellW * 0.8f, j * cellH + thickness))
                        2 -> {
                            val cx = i * cellW + cellW / 2
                            val cy = j * cellH + cellH / 2
                            mazeWalls.add(RectF(cx - 50f, cy - 50f, cx + 50f, cy + 50f))
                        }
                    }
                } else if (randVal < 0.35f) {
                    val bwRect = when (random.nextInt(2)) {
                        0 -> RectF(i * cellW, j * cellH, i * cellW + cellW * 0.6f, j * cellH + thickness)
                        else -> RectF(i * cellW, j * cellH, i * cellW + thickness, j * cellH + cellH * 0.6f)
                    }
                    blinkingWalls.add(BlinkingWall(bwRect, random.nextInt(3000).toLong()))
                } else if (randVal < 0.50f) {
                    val type = if (random.nextBoolean()) ZoneType.ICE else ZoneType.MUD
                    zones.add(Zone(RectF(i * cellW + 10f, j * cellH + 10f, i * cellW + cellW - 10f, j * cellH + cellH - 10f), type))
                } else if (randVal < 0.60f) { // 10% to spawn item
                    val cx = i * cellW + cellW / 2
                    val cy = j * cellH + cellH / 2
                    val type = when (random.nextInt(3)) {
                        0 -> ItemType.NITRO
                        1 -> ItemType.TELEPORT
                        else -> ItemType.HEALTH
                    }
                    items.add(Item(items.size, cx, cy, type))
                }
            }
        }
        
        // Spawn exact 10 Coins in valid spaces
        val validSpots = mutableListOf<PointF>()
        for (i in 0 until cols) {
            for (j in 0 until rows) {
                if ((i <= 1 && j <= 1) || (i == exitCol && j == exitRow)) continue
                val cx = i * cellW + cellW / 2
                val cy = j * cellH + cellH / 2
                
                // Cek apakah tabrakan dengan maze wall
                val ptRect = RectF(cx - 15f, cy - 15f, cx + 15f, cy + 15f)
                var blocked = false
                for (w in mazeWalls) if (RectF.intersects(w, ptRect)) blocked = true
                if (!blocked) validSpots.add(PointF(cx, cy))
            }
        }
        
        validSpots.shuffle(random)
        val coinCount = Math.min(TARGET_COINS, validSpots.size)
        for (i in 0 until coinCount) {
            val spot = validSpots[i]
            items.add(Item(items.size, spot.x, spot.y, ItemType.COIN))
        }

        if (startTime == 0L) startTime = System.currentTimeMillis()
    }

    fun setupBots(count: Int, speedScale: Float = 0.45f) {
        botPlayers.clear()
        botSpeedMultiplier = speedScale
        for (i in 1..count) {
            val bot = Car(
                id = "BOT_$i",
                x = 100f,
                y = 150f + (i * 50f),
                angle = 0f,
                color = if (i == 1) Color.parseColor("#FF9800") else Color.parseColor("#9C27B0"),
                name = "BOT $i"
            )
            botPlayers.add(bot)
        }
    }

    private fun update() {
        val iterator = skidMarks.iterator()
        while(iterator.hasNext()) {
            val mark = iterator.next()
            mark.alpha -= 2
            if (mark.alpha <= 0) iterator.remove()
        }
        
        val pIt = particles.iterator()
        while(pIt.hasNext()) {
            val p = pIt.next()
            p.x += p.vx
            p.y += p.vy
            p.life--
            if (p.life <= 0) pIt.remove()
        }

        playerCar?.let { car ->
            if (leftDown) car.angle -= 5f
            if (rightDown) car.angle += 5f
            
            var maxSpeed = if (System.currentTimeMillis() < nitroTime) 15f else 8f
            var acceleration = 0.5f
            var friction = 0.95f // decelerate gradually
            
            if (car.isDead) {
                onPositionUpdate?.invoke(car)
                return@let // Skip input processing while dead
            }

            val carCenter = RectF(car.x - 10f, car.y - 10f, car.x + 10f, car.y + 10f)
            for (zone in zones) {
                if (RectF.intersects(zone.rect, carCenter)) {
                    if (zone.type == ZoneType.ICE) {
                        friction = 0.995f // Sangat licin (susah berhenti)
                        acceleration = 0.15f // Susah mulai bergerak
                        maxSpeed += 2f
                    } else if (zone.type == ZoneType.MUD) {
                        friction = 0.70f // Cepat berhenti
                        maxSpeed = 3.5f // Sangat lambat
                    }
                }
            }
            
            if (accelDown) {
                val rad = Math.toRadians(car.angle.toDouble())
                car.velX += (acceleration * cos(rad)).toFloat()
                car.velY += (acceleration * sin(rad)).toFloat()
                
                val speedSq = car.velX * car.velX + car.velY * car.velY
                if (speedSq > maxSpeed * maxSpeed) {
                    val speed = Math.sqrt(speedSq.toDouble()).toFloat()
                    car.velX = (car.velX / speed) * maxSpeed
                    car.velY = (car.velY / speed) * maxSpeed
                }
                
                // Spawn exhaust particles
                val radRear = Math.toRadians((car.angle + 180).toDouble())
                val rearX = car.x + 20f * cos(radRear).toFloat()
                val rearY = car.y + 20f * sin(radRear).toFloat()
                
                // random spread
                val spreadAngle = Math.toRadians((car.angle + 180 + (java.util.Random().nextInt(40) - 20)).toDouble())
                val pSpeed = java.util.Random().nextFloat() * 2f + 1f
                val pColor = if (System.currentTimeMillis() < nitroTime) Color.CYAN else Color.GRAY
                
                // Spawn chance
                if (java.util.Random().nextFloat() < 0.4f) {
                    particles.add(Particle(
                        rearX, rearY,
                        (pSpeed * cos(spreadAngle)).toFloat(),
                        (pSpeed * sin(spreadAngle)).toFloat(),
                        20, 20, pColor, 4f
                    ))
                }
            } else {
                car.velX *= friction
                car.velY *= friction
            }
            
            // Limit completely stopped to prevent infinite tiny floats
            if (Math.abs(car.velX) < 0.1f) car.velX = 0f
            if (Math.abs(car.velY) < 0.1f) car.velY = 0f

            val velAngle = Math.toDegrees(Math.atan2(car.velY.toDouble(), car.velX.toDouble())).toFloat()
            var d = Math.abs(car.angle - velAngle) % 360
            val angleDiff = if (d > 180) 360 - d else d
            
            val speedSqForDrift = car.velX * car.velX + car.velY * car.velY
            if (speedSqForDrift > 10f && angleDiff > 20f) {
                val radRear = Math.toRadians((car.angle + 180).toDouble())
                val rearX = car.x + 20f * cos(radRear).toFloat()
                val rearY = car.y + 20f * sin(radRear).toFloat()
                // offset a bit for two wheels
                val radSide = Math.toRadians((car.angle + 90).toDouble())
                skidMarks.add(SkidMark(rearX + 10f * cos(radSide).toFloat(), rearY + 10f * sin(radSide).toFloat()))
                skidMarks.add(SkidMark(rearX - 10f * cos(radSide).toFloat(), rearY - 10f * sin(radSide).toFloat()))
            }

            val nextX = car.x + car.velX
            val nextY = car.y + car.velY
            
            var collisionX = false
            var collisionY = false
            var collisionCorner = false
            var collisionOccurred = false
            
            // Wall checking (No more Ghost mode wall pass)
            val rectX = RectF(nextX - 20, car.y - 20, nextX + 20, car.y + 20)
            val rectY = RectF(car.x - 20, nextY - 20, car.x + 20, nextY + 20)
            val rectBoth = RectF(nextX - 20, nextY - 20, nextX + 20, nextY + 20)
            
            val activeWalls = mutableListOf<RectF>()
            activeWalls.addAll(mazeWalls)
            val now = System.currentTimeMillis()
            for (bw in blinkingWalls) {
                if ((now + bw.offsetMs) % 4000 < 2000) {
                    activeWalls.add(bw.rect)
                }
            }
            
            for (wall in activeWalls) {
                if (RectF.intersects(wall, rectX)) { collisionX = true; collisionOccurred = true }
                if (RectF.intersects(wall, rectY)) { collisionY = true; collisionOccurred = true }
                if (RectF.intersects(wall, rectBoth)) { collisionCorner = true; collisionOccurred = true }
            }
            
            if (collisionOccurred) {
                val speedSq = car.velX * car.velX + car.velY * car.velY
                if (speedSq > 5f) { // only hard collisions
                    car.hp -= (speedSq / 3f).toInt()
                    
                    if (car.hp <= 0 && !car.isDead) { // Meledak mati
                        val lostCoins = Math.min(car.coins, 3)
                        car.isDead = true
                        car.coins = Math.max(0, car.coins - 3) // Penalty jatuh koin
                        
                        // Respawn coins back to the map randomly
                        if (lostCoins > 0) {
                            val inactiveCoins = items.filter { it.type == ItemType.COIN && !it.isActive }.shuffled()
                            val spawnCount = Math.min(lostCoins, inactiveCoins.size)
                            for (i in 0 until spawnCount) {
                                inactiveCoins[i].isActive = true
                                onItemDropped?.invoke(inactiveCoins[i].id)
                            }
                        }
                        
                        toneGen.startTone(ToneGenerator.TONE_CDMA_HIGH_L, 500)
                        
                        // Boom particles
                        for (i in 0 until 40) {
                            val angle = java.util.Random().nextDouble() * Math.PI * 2
                            val pSpeed = java.util.Random().nextFloat() * 12f + 2f
                            val pColor = if (java.util.Random().nextBoolean()) Color.RED else Color.YELLOW
                            particles.add(Particle(
                                car.x, car.y,
                                (pSpeed * Math.cos(angle)).toFloat(),
                                (pSpeed * Math.sin(angle)).toFloat(),
                                30, 30, pColor, 6f
                            ))
                        }
                        
                        // Auto respawn scheduler
                        postDelayed({
                            car.x = 100f
                            car.y = 150f // safe coords
                            car.velX = 0f
                            car.velY = 0f
                            car.angle = 0f
                            car.hp = 100
                            car.isDead = false
                        }, 3000)
                        
                        onPositionUpdate?.invoke(car)
                        return@let
                    } else {
                        // Cuma tabrakan tapi masih hidup
                        toneGen.startTone(ToneGenerator.TONE_CDMA_SOFT_ERROR_LITE, 150)
                        for (i in 0 until 5) {
                            val angle = java.util.Random().nextDouble() * Math.PI * 2
                            val pSpeed = java.util.Random().nextFloat() * 4f + 1f
                            particles.add(Particle(
                                car.x, car.y, // approximate collision point
                                (pSpeed * Math.cos(angle)).toFloat(),
                                (pSpeed * Math.sin(angle)).toFloat(),
                                15, 15, Color.YELLOW, 3f
                            ))
                        }
                    }
                }
            }
            
            if (collisionX) {
                car.velX = -car.velX * 0.5f // Bounce back with half speed
            } else {
                car.x = nextX
            }
            
            if (collisionY) {
                car.velY = -car.velY * 0.5f // Bounce back with half speed
            } else {
                car.y = nextY
            }
            
            if (!collisionX && !collisionY && collisionCorner) {
                 // Corner hit
                 car.velX = -car.velX * 0.5f
                 car.velY = -car.velY * 0.5f
            }
            
            val carRect = RectF(car.x - 20, car.y - 20, car.x + 20, car.y + 20)
            
            // Item checking
            for (item in items) {
                if (item.isActive) {
                    val itemRect = RectF(item.x - 20, item.y - 20, item.x + 20, item.y + 20)
                    if (RectF.intersects(carRect, itemRect)) {
                        item.isActive = false
                        if (item.type == ItemType.NITRO) {
                            nitroTime = System.currentTimeMillis() + 3000
                        } else if (item.type == ItemType.TELEPORT) {
                            teleportIndicatorTime = System.currentTimeMillis() + 2000 // 2 second charging
                            toneGen.startTone(ToneGenerator.TONE_CDMA_KEYPAD_VOLUME_KEY_LITE, 1000)
                            
                            // Schedule explicit Teleport Jump
                            postDelayed({
                                if (!car.isDead) { // If survivor hasn't died during charging
                                    toneGen.startTone(ToneGenerator.TONE_PROP_BEEP, 100)
                                    // Teleport to random safe cell
                                    val safeCells = mutableListOf<PointF>()
                                    val cellW = VIRTUAL_WIDTH / 5
                                    val cellH = VIRTUAL_HEIGHT / 8
                                    for (i in 0 until 5) {
                                        for (j in 0 until 8) {
                                            val cx = i * cellW + cellW / 2
                                            val cy = j * cellH + cellH / 2
                                            val ptRect = RectF(cx - 15f, cy - 15f, cx + 15f, cy + 15f)
                                            var blocked = false
                                            for (w in mazeWalls) if (RectF.intersects(w, ptRect)) blocked = true
                                            if (!blocked) safeCells.add(PointF(cx, cy))
                                        }
                                    }
                                    if (safeCells.isNotEmpty()) {
                                        val dest = safeCells.random()
                                        car.x = dest.x
                                        car.y = dest.y
                                        car.velX = 0f
                                        car.velY = 0f
                                        // Poof effect
                                        for (i in 0 until 20) {
                                            val angle = java.util.Random().nextDouble() * Math.PI * 2
                                            particles.add(Particle(
                                                car.x, car.y,
                                                (3f * Math.cos(angle)).toFloat(), (3f * Math.sin(angle)).toFloat(),
                                                20, 20, Color.MAGENTA, 5f
                                            ))
                                        }
                                    }
                                }
                            }, 2000)
                            
                        } else if (item.type == ItemType.HEALTH) {
                            car.hp = Math.min(100, car.hp + 40)
                        } else if (item.type == ItemType.COIN) {
                            car.coins++
                        }
                        if (item.type != ItemType.TELEPORT) { // Teleport emits custom tone above
                            toneGen.startTone(if (item.type == ItemType.COIN) ToneGenerator.TONE_DTMF_1 else if (item.type == ItemType.HEALTH) ToneGenerator.TONE_CDMA_NETWORK_USA_RINGBACK else ToneGenerator.TONE_PROP_PROMPT, 100)
                        }
                        onItemPickedUp?.invoke(item.id)
                    }
                }
            }
            
            if (RectF.intersects(finishLine, carRect)) {
                val allCoinsCollected = items.none { it.type == ItemType.COIN && it.isActive }
                if (allCoinsCollected) {
                    finishTime = System.currentTimeMillis()
                    toneGen.startTone(ToneGenerator.TONE_CDMA_ABBR_ALERT, 500)
                    
                    var winnerCar = car
                    var maxCoins = car.coins
                    for (enemy in otherCars.values) {
                        if (enemy.coins > maxCoins) {
                            maxCoins = enemy.coins
                            winnerCar = enemy
                        }
                    }
                    for (bot in botPlayers) {
                        if (bot.coins > maxCoins) {
                            maxCoins = bot.coins
                            winnerCar = bot
                        }
                    }
                    
                    val timeStr = String.format("%.1fs", (finishTime - startTime) / 1000f)
                    gameWin(winnerCar.name, timeStr)
                    onWin?.invoke(winnerCar.name)
                } else {
                    // Terkunci, memantul pelan!
                    car.velX = -car.velX * 0.8f
                    car.velY = -car.velY * 0.8f
                    car.x += car.velX
                    car.y += car.velY
                }
            }
            
            onPositionUpdate?.invoke(car)
        }

        // --- BOT AI UPDATE ---
        if (isHost && !gameEnded) {
            for (bot in botPlayers) {
                if (bot.isDead) continue

                var targetX = finishLine.centerX()
                var targetY = finishLine.centerY()
                val portalActive = items.none { it.type == ItemType.COIN && it.isActive }
                
                if (!portalActive) {
                    var minDist = Float.MAX_VALUE
                    for (item in items) {
                        if (item.isActive && (item.type == ItemType.COIN || (item.type == ItemType.HEALTH && bot.hp <= 50))) {
                            val dist = Math.hypot((item.x - bot.x).toDouble(), (item.y - bot.y).toDouble()).toFloat()
                            if (dist < minDist) {
                                minDist = dist
                                targetX = item.x
                                targetY = item.y
                            }
                        }
                    }
                }

                val angleToTarget = Math.toDegrees(Math.atan2((targetY - bot.y).toDouble(), (targetX - bot.x).toDouble())).toFloat()
                var diff = angleToTarget - bot.angle
                diff = (diff + 180) % 360 - 180 // normalize -180 to 180
                
                // Obstacle Raycast
                val rad = Math.toRadians(bot.angle.toDouble())
                val rayLength = if (botSpeedMultiplier < 0.40f) 22f else 35f
                val frontX = bot.x + cos(rad).toFloat() * rayLength
                val frontY = bot.y + sin(rad).toFloat() * rayLength
                val frontRect = RectF(frontX - 15f, frontY - 15f, frontX + 15f, frontY + 15f)
                var blocked = false
                for (wall in mazeWalls) if (RectF.intersects(wall, frontRect)) blocked = true
                for (bw in blinkingWalls) if ((System.currentTimeMillis() + bw.offsetMs) % 4000 < 2000 && RectF.intersects(bw.rect, frontRect)) blocked = true

                if (blocked) {
                    bot.angle += if (botSpeedMultiplier < 0.40f) 6f else 12f // Turn clumsily right to avoid
                } else {
                    if (diff > 5) bot.angle += 5f
                    else if (diff < -5) bot.angle -= 5f
                }

                val accel = if (blocked) 0.15f else botSpeedMultiplier
                bot.velX += (accel * cos(Math.toRadians(bot.angle.toDouble()))).toFloat()
                bot.velY += (accel * sin(Math.toRadians(bot.angle.toDouble()))).toFloat()
                bot.velX *= 0.95f
                bot.velY *= 0.95f

                val nextX = bot.x + bot.velX
                val nextY = bot.y + bot.velY
                val nextRectX = RectF(nextX - 20, bot.y - 20, nextX + 20, bot.y + 20)
                val nextRectY = RectF(bot.x - 20, nextY - 20, bot.x + 20, nextY + 20)

                var cx = false; var cy = false
                val allW = mutableListOf<RectF>().apply { addAll(mazeWalls); addAll(blinkingWalls.filter { (System.currentTimeMillis() + it.offsetMs) % 4000 < 2000 }.map { it.rect }) }
                for (w in allW) {
                    if (RectF.intersects(w, nextRectX)) cx = true
                    if (RectF.intersects(w, nextRectY)) cy = true
                }
                if (cx) { bot.velX *= -0.5f; bot.hp -= 2 } else { bot.x = nextX }
                if (cy) { bot.velY *= -0.5f; bot.hp -= 2 } else { bot.y = nextY }

                // Bot Item
                val botRect = RectF(bot.x - 20, bot.y - 20, bot.x + 20, bot.y + 20)
                for (item in items) {
                    if (item.isActive && RectF.intersects(botRect, RectF(item.x - 20, item.y - 20, item.x + 20, item.y + 20))) {
                        item.isActive = false
                        if (item.type == ItemType.COIN) bot.coins++
                        else if (item.type == ItemType.HEALTH) bot.hp = Math.min(100, bot.hp + 40)
                        else if (item.type == ItemType.TELEPORT) {
                            postDelayed({ bot.x = 200f; bot.y = 200f }, 2000)
                        }
                        onItemPickedUp?.invoke(item.id)
                    }
                }

                // Bot Death
                if (bot.hp <= 0 && !bot.isDead) {
                    bot.isDead = true
                    val lostCoins = Math.min(bot.coins, 3)
                    bot.coins = Math.max(0, bot.coins - 3)
                    if (lostCoins > 0) {
                        val inactiveCoins = items.filter { it.type == ItemType.COIN && !it.isActive }.shuffled()
                        for (i in 0 until Math.min(lostCoins, inactiveCoins.size)) {
                            inactiveCoins[i].isActive = true
                            onItemDropped?.invoke(inactiveCoins[i].id)
                        }
                    }
                    postDelayed({ bot.x = 100f; bot.y = 150f; bot.velX = 0f; bot.velY = 0f; bot.hp = 100; bot.isDead = false }, 3000)
                }

                // Bot portal win check
                if (RectF.intersects(botRect, finishLine) && portalActive) {
                    finishTime = System.currentTimeMillis()
                    var winnerCar = bot
                    var maxCoins = bot.coins
                    for (enemy in otherCars.values) if (enemy.coins > maxCoins) { maxCoins = enemy.coins; winnerCar = enemy }
                    playerCar?.let { if (it.coins > maxCoins) { maxCoins = it.coins; winnerCar = it } }
                    for (b in botPlayers) if (b.coins > maxCoins) { maxCoins = b.coins; winnerCar = b }
                    
                    val timeStr = String.format("%.1fs", (finishTime - startTime) / 1000f)
                    gameWin(winnerCar.name, timeStr)
                    onWin?.invoke(winnerCar.name)
                }
                
                onPositionUpdate?.invoke(bot)
            }
        }
    }

    fun gameWin(name: String, timeResult: String = "") {
        gameEnded = true
        winnerName = name
        winnerTimeStr = timeResult
        invalidate()
    }

    fun resetGame(newSeed: Long) {
        gameEnded = false
        winnerName = ""
        winnerTimeStr = ""
        nitroTime = 0L
        teleportIndicatorTime = 0L
        startTime = System.currentTimeMillis()
        skidMarks.clear()
        particles.clear()
        setMazeSeed(newSeed)
        playerCar?.let {
            it.x = 100f
            it.y = if (it.color == Color.RED) 80f else 150f
            it.angle = 0f
            it.velX = 0f
            it.velY = 0f
            it.coins = 0
            it.hp = 100
            it.isDead = false
        }
        otherCars.values.forEach { it.coins = 0 }
        botPlayers.forEachIndexed { i, bot ->
            bot.x = 100f
            bot.y = 150f + ((i + 1) * 50f)
            bot.angle = 0f
            bot.velX = 0f
            bot.velY = 0f
            bot.coins = 0
            bot.hp = 100
            bot.isDead = false
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.parseColor("#1a1a1a"))

        // APPLY SCALING FOR ALL DRAWING
        canvas.save()
        canvas.scale(screenScaleX, screenScaleY)

        // Draw Zones
        for (zone in zones) {
            paint.color = if (zone.type == ZoneType.ICE) Color.parseColor("#44AEEEEE") else Color.parseColor("#448B4513")
            canvas.drawRect(zone.rect, paint)
            // draw subtle borders
            paint.color = if (zone.type == ZoneType.ICE) Color.parseColor("#88AEEEEE") else Color.parseColor("#888B4513")
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 3f
            canvas.drawRect(zone.rect, paint)
            paint.style = Paint.Style.FILL // back to fill
        }

        // Draw Walls
        paint.color = Color.parseColor("#444444")
        paint.style = Paint.Style.FILL
        for (wall in mazeWalls) canvas.drawRect(wall, paint)
        
        // Draw Blinking Walls
        val now = System.currentTimeMillis()
        for (bw in blinkingWalls) {
            val progress = (now + bw.offsetMs) % 4000
            if (progress < 2000) {
                // It's solid and active
                paint.color = Color.parseColor("#884444") // Reddish to indicate shifting
                canvas.drawRect(bw.rect, paint)
                
                paint.color = Color.RED
                paint.strokeWidth = 2f
                paint.style = Paint.Style.STROKE
                val height = 10f * (1f - (progress / 2000f))
                canvas.drawRect(bw.rect, paint)
                paint.style = Paint.Style.FILL
            } else {
                // Opening - just draw outline
                paint.color = Color.parseColor("#22880000")
                canvas.drawRect(bw.rect, paint)
            }
        }
        
        val portalActive = items.none { it.type == ItemType.COIN && it.isActive }
        
        // Draw Portal (Exit)
        paint.color = if (portalActive) Color.parseColor("#44000000") else Color.parseColor("#44880000") // shadow/glow
        canvas.drawCircle(finishLine.centerX(), finishLine.centerY(), finishLine.width() / 2 + 10f, paint)
        
        val portalPaint = Paint()
        val colorsP = if (portalActive) intArrayOf(Color.BLACK, Color.parseColor("#333333")) else intArrayOf(Color.parseColor("#330000"), Color.parseColor("#660000"))
        val stopsP = floatArrayOf(0.7f, 1f)
        portalPaint.shader = android.graphics.RadialGradient(
            finishLine.centerX(), finishLine.centerY(), finishLine.width() / 2,
            colorsP, stopsP, android.graphics.Shader.TileMode.CLAMP
        )
        canvas.drawCircle(finishLine.centerX(), finishLine.centerY(), finishLine.width() / 2, portalPaint)
        
        paint.color = if (portalActive) Color.WHITE else Color.RED
        paint.textSize = 24f
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = Typeface.DEFAULT_BOLD
        canvas.drawText("EXIT", finishLine.centerX(), finishLine.centerY() + 8f, paint)
        paint.typeface = Typeface.DEFAULT

        // Draw Skid Marks
        paint.color = Color.BLACK
        paint.style = Paint.Style.FILL
        for (mark in skidMarks) {
            paint.alpha = mark.alpha
            canvas.drawCircle(mark.x, mark.y, 4f, paint)
        }
        paint.alpha = 255 // Reset alpha

        // Draw Particles
        paint.style = Paint.Style.FILL
        for (p in particles) {
            paint.color = p.color
            paint.alpha = (255f * (p.life.toFloat() / p.maxLife.toFloat())).toInt()
            canvas.drawCircle(p.x, p.y, p.size, paint)
        }
        paint.alpha = 255

        // Draw Items
        for (item in items) {
            if (item.isActive) {
                if (item.type == ItemType.COIN) {
                    paint.color = Color.parseColor("#FFD700") // Gold
                    canvas.drawCircle(item.x, item.y, 12f, paint)
                    paint.color = Color.parseColor("#DAA520") // Darker Gold for inner
                    canvas.drawCircle(item.x, item.y, 8f, paint)
                } else {
                    paint.color = if (item.type == ItemType.NITRO) Color.CYAN else Color.MAGENTA
                    canvas.drawCircle(item.x, item.y, 15f, paint)
                    paint.color = Color.WHITE
                    paint.textSize = 15f
                    paint.textAlign = Paint.Align.CENTER
                    paint.typeface = Typeface.DEFAULT_BOLD
                    canvas.drawText(if (item.type == ItemType.NITRO) "N" else "T", item.x, item.y + 5f, paint)
                }
            }
        }

        // Draw Cars
        playerCar?.let { drawCar(canvas, it) }
        otherCars.values.forEach { drawCar(canvas, it) }
        botPlayers.forEach { drawCar(canvas, it) }

        // Fog of War (Battle Royale Shrink Dynamic)
        playerCar?.let { car ->
            if (!gameEnded && !car.isDead) {
                val fogPaint = Paint()
                // Radius pandangan fix, tapi Teleport charging memberikan extra view singkat
                val targetRadius = if (System.currentTimeMillis() < teleportIndicatorTime) 650f else 350f

                val colors = intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, Color.argb(253, 0, 0, 0))
                val stops = floatArrayOf(0f, 0.4f, 1f)
                fogPaint.shader = android.graphics.RadialGradient(car.x, car.y, targetRadius, colors, stops, android.graphics.Shader.TileMode.CLAMP)
                
                // Gambar selubung kabut yang menutupi keseluruhan map virtual
                canvas.drawRect(0f, 0f, VIRTUAL_WIDTH, VIRTUAL_HEIGHT, fogPaint)
            } else if (!gameEnded && car.isDead) { // Jika mati, pandangan hitam / blur
                paint.color = Color.argb(200, 0, 0, 0)
                canvas.drawRect(0f, 0f, VIRTUAL_WIDTH, VIRTUAL_HEIGHT, paint)
                paint.color = Color.RED
                paint.textSize = 50f
                paint.textAlign = Paint.Align.CENTER
                canvas.drawText("WASTED - RESPAWNING...", width / 2f / screenScaleX, height / 2f / screenScaleY, paint)
            }
        }

        // Info Text HUD
        canvas.restore()
        
        // Non-scaled UI overlay
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.textSize = 45f
        paint.textAlign = Paint.Align.LEFT
        
        // Show Coins
        val pCoins = playerCar?.coins ?: 0
        val allCoinsCollectedHUD = items.none { it.type == ItemType.COIN && it.isActive }
        paint.color = if (allCoinsCollectedHUD) Color.GREEN else Color.YELLOW
        canvas.drawText("P1 COINS: $pCoins (Max $TARGET_COINS)", 30f, 60f, paint)
        
        // Multi-Player Coins (HUD untuk max 4 player)
        var yOffset = 110f
        for ((idx, enemy) in otherCars.values.withIndex()) {
            paint.color = enemy.color
            canvas.drawText("P${idx+2} COINS: ${enemy.coins} (Max $TARGET_COINS)", 30f, yOffset, paint)
            yOffset += 50f
        }
        
        for ((idx, bot) in botPlayers.withIndex()) {
            paint.color = bot.color
            canvas.drawText("BOT${idx+1} COINS: ${bot.coins} (Max $TARGET_COINS)", 30f, yOffset, paint)
            yOffset += 50f
        }
        
        // Show Timer
        paint.color = Color.WHITE
        paint.textAlign = Paint.Align.RIGHT
        val nowTime = if (gameEnded) finishTime else System.currentTimeMillis()
        val elapsed = (nowTime - startTime) / 1000f
        canvas.drawText(String.format("TIME: %.1fs", elapsed), width.toFloat() - 30f, 60f, paint)
        
        // Draw Winner Overlay (Full screen, no scale)
        if (gameEnded) {
            paint.color = Color.argb(200, 0, 0, 0)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
            paint.color = Color.YELLOW
            paint.textSize = 70f
            paint.textAlign = Paint.Align.CENTER
            canvas.drawText("${winnerName.uppercase()} WINS!", width / 2f, height / 2f - 40f, paint)
            
            paint.color = Color.WHITE
            paint.textSize = 50f
            canvas.drawText("Time Record: $winnerTimeStr", width / 2f, height / 2f + 40f, paint)
        }
    }

    private fun drawCar(canvas: Canvas, car: Car) {
        if (car.isDead) return // Don't draw exploded cars

        canvas.save()
        canvas.translate(car.x, car.y)
        canvas.rotate(car.angle)
        
        paint.style = Paint.Style.FILL
        paint.color = car.color
        val isChargingTeleport = (car.id == playerCar?.id && System.currentTimeMillis() < teleportIndicatorTime)
        if (isChargingTeleport) {
            paint.alpha = if ((System.currentTimeMillis() / 100) % 2 == 0L) 100 else 255 // Blinking rapidly
        }

        canvas.drawRect(-30f, -20f, 30f, 20f, paint) // Physical car size in virtual space
        
        paint.color = Color.BLACK
        if (isChargingTeleport) paint.alpha = 100 else paint.alpha = 255
        canvas.drawRect(-10f, -15f, 15f, 15f, paint)
        
        paint.color = Color.YELLOW
        if (isChargingTeleport) paint.alpha = 100 else paint.alpha = 255
        canvas.drawRect(25f, -15f, 30f, -5f, paint)
        canvas.drawRect(25f, 5f, 30f, 15f, paint)
        
        canvas.restore()
        
        // Draw HP Bar
        paint.color = Color.RED
        canvas.drawRect(car.x - 25f, car.y - 45f, car.x + 25f, car.y - 40f, paint)
        paint.color = Color.GREEN
        canvas.drawRect(car.x - 25f, car.y - 45f, car.x - 25f + (50f * (car.hp.toFloat() / 100f)), car.y - 40f, paint)

        // Draw Name text
        paint.color = Color.WHITE
        paint.textSize = 25f
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText(car.name, car.x, car.y - 55f, paint)
    }

    fun handleInput(left: Boolean, right: Boolean, accel: Boolean) {
        leftDown = left
        rightDown = right
        accelDown = accel
    }
}
