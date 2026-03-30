package com.hanyajasa.gamemobilbalapmultiplayer

import android.content.Context
import android.graphics.*
import android.media.AudioManager
import android.media.ToneGenerator
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

enum class ItemType { NITRO, TELEPORT, COIN, HEALTH, TRAP_OIL, TRAP_MINE }

data class Item(
    val id: Int,
    var x: Float,
    var y: Float,
    val type: ItemType,
    var isActive: Boolean = true
)

data class SkidMark(val x: Float, val y: Float, var alpha: Int = 200)

enum class ZoneType { ICE, MUD, OIL }
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

data class Trap(
    val id: Int,
    val x: Float,
    val y: Float,
    val type: ItemType, // OIL or MINE
    val ownerId: String
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
    private val traps = mutableListOf<Trap>()
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
        traps.clear()
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
                } else if (randVal < 0.65f) { // 15% to spawn item
                    val cx = i * cellW + cellW / 2
                    val cy = j * cellH + cellH / 2
                    val type = when (random.nextInt(5)) {
                        0 -> ItemType.NITRO
                        1 -> ItemType.TELEPORT
                        2 -> ItemType.HEALTH
                        3 -> ItemType.TRAP_OIL
                        else -> ItemType.TRAP_MINE
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

    private fun handleCarCollision(car: Car, other: Car) {
        val dx = other.x - car.x
        val dy = other.y - car.y
        val dist = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
        val minDist = 45f // Jarak minimum tabrakan mobil (30x20 rect)

        if (dist < minDist && dist > 0) {
            // Hitung kecepatan relatif
            val relVelX = car.velX - other.velX
            val relVelY = car.velY - other.velY
            val relSpeed = Math.sqrt((relVelX * relVelX + relVelY * relVelY).toDouble()).toFloat()

            if (relSpeed > 2f) { // Hanya tabrakan cukup keras yang berdampak
                val damageMultiplier = if (car.carClass == CarClass.TANK) 0.5f else 1.0f
                val damage = (relSpeed * 2 * damageMultiplier).toInt()
                car.hp -= damage
                other.hp -= (relSpeed * 2).toInt()
                
                // Efek suara tabrakan
                toneGen.startTone(ToneGenerator.TONE_CDMA_SOFT_ERROR_LITE, 100)
                
                // Partikel benturan
                val midX = (car.x + other.x) / 2
                val midY = (car.y + other.y) / 2
                for (i in 0 until 8) {
                    val pAngle = Math.random() * Math.PI * 2
                    val pSpeed = Math.random() * 5 + 2
                    particles.add(Particle(midX, midY, (pSpeed * Math.cos(pAngle)).toFloat(), (pSpeed * Math.sin(pAngle)).toFloat(), 12, 12, Color.WHITE, 4f))
                }

                // Bounce Effect
                val nx = dx / dist
                val ny = dy / dist
                val p = 2f * (car.velX * nx + car.velY * ny - other.velX * nx - other.velY * ny) / 2f
                
                val bounceMultiplier = if (car.carClass == CarClass.TANK) 0.3f else 0.8f
                car.velX -= p * nx * bounceMultiplier
                car.velY -= p * ny * bounceMultiplier
                other.velX += p * nx * 0.8f
                other.velY += p * ny * 0.8f
                
                // Pisahkan sedikit agar tidak nempel
                val overlap = minDist - dist
                car.x -= nx * overlap / 2
                car.y -= ny * overlap / 2
                other.x += nx * overlap / 2
                other.y += ny * overlap / 2
            }
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
            
            val baseMaxSpeed = if (car.carClass == CarClass.SPEEDSTER) 10f else 8f
            var maxSpeed = if (System.currentTimeMillis() < nitroTime) baseMaxSpeed * 1.8f else baseMaxSpeed
            var acceleration = if (car.carClass == CarClass.SPEEDSTER) 0.7f else 0.5f
            var friction = 0.95f // decelerate gradually
            
            if (car.isDead) {
                onPositionUpdate?.invoke(car)
                return@let // Skip input processing while dead
            }

            val carCenter = RectF(car.x - 10f, car.y - 10f, car.x + 10f, car.y + 10f)
            for (zone in zones) {
                if (RectF.intersects(zone.rect, carCenter)) {
                    if (zone.type == ZoneType.ICE || zone.type == ZoneType.OIL) {
                        friction = 0.995f // Sangat licin (susah berhenti)
                        acceleration = 0.15f // Susah mulai bergerak
                        maxSpeed += 2f
                    } else if (zone.type == ZoneType.MUD) {
                        friction = 0.70f // Cepat berhenti
                        maxSpeed = 3.5f // Sangat lambat
                    }
                }
            }

            // --- TRAP COLLISION CHECK ---
            val tIt = traps.iterator()
            while (tIt.hasNext()) {
                val trap = tIt.next()
                val dx = trap.x - car.x
                val dy = trap.y - car.y
                val dist = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
                if (dist < 30f) {
                    if (trap.type == ItemType.TRAP_MINE) {
                        val mineDamage = if (car.carClass == CarClass.TANK) 20 else 40
                        car.hp -= mineDamage
                        car.velX = -car.velX * 1.5f
                        car.velY = -car.velY * 1.5f
                        toneGen.startTone(ToneGenerator.TONE_CDMA_HIGH_L, 300)
                        // Explosion particles
                        for (i in 0 until 15) {
                            val pAngle = Math.random() * Math.PI * 2
                            val pSpeed = Math.random() * 8 + 2
                            particles.add(Particle(trap.x, trap.y, (pSpeed * Math.cos(pAngle)).toFloat(), (pSpeed * Math.sin(pAngle)).toFloat(), 20, 20, Color.RED, 5f))
                        }
                    } else if (trap.type == ItemType.TRAP_OIL) {
                        zones.add(Zone(RectF(trap.x - 40f, trap.y - 40f, trap.x + 40f, trap.y + 40f), ZoneType.OIL))
                        toneGen.startTone(ToneGenerator.TONE_CDMA_SOFT_ERROR_LITE, 100)
                    }
                    tIt.remove()
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
                
                val spreadAngle = Math.toRadians((car.angle + 180 + (java.util.Random().nextInt(40) - 20)).toDouble())
                val pSpeed = java.util.Random().nextFloat() * 2f + 1f
                val pColor = if (System.currentTimeMillis() < nitroTime) Color.CYAN else Color.GRAY
                
                if (java.util.Random().nextFloat() < 0.4f) {
                    particles.add(Particle(rearX, rearY, (pSpeed * cos(spreadAngle)).toFloat(), (pSpeed * sin(spreadAngle)).toFloat(), 20, 20, pColor, 4f))
                }
            } else {
                car.velX *= friction
                car.velY *= friction
            }
            
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
                val radSide = Math.toRadians((car.angle + 90).toDouble())
                skidMarks.add(SkidMark(rearX + 10f * cos(radSide).toFloat(), rearY + 10f * sin(radSide).toFloat()))
                skidMarks.add(SkidMark(rearX - 10f * cos(radSide).toFloat(), rearY - 10f * sin(radSide).toFloat()))
            }

            // --- CAR TO CAR COLLISIONS ---
            for (other in otherCars.values) if (!other.isDead) handleCarCollision(car, other)
            for (bot in botPlayers) if (!bot.isDead) handleCarCollision(car, bot)

            val nextX = car.x + car.velX
            val nextY = car.y + car.velY
            
            var collisionX = false; var collisionY = false; var collisionCorner = false; var collisionOccurred = false
            
            val rectX = RectF(nextX - 20, car.y - 20, nextX + 20, car.y + 20)
            val rectY = RectF(car.x - 20, nextY - 20, car.x + 20, nextY + 20)
            val rectBoth = RectF(nextX - 20, nextY - 20, nextX + 20, nextY + 20)
            
            val activeWalls = mutableListOf<RectF>().apply {
                addAll(mazeWalls)
                val now = System.currentTimeMillis()
                for (bw in blinkingWalls) if ((now + bw.offsetMs) % 4000 < 2000) add(bw.rect)
            }
            
            for (wall in activeWalls) {
                if (RectF.intersects(wall, rectX)) { collisionX = true; collisionOccurred = true }
                if (RectF.intersects(wall, rectY)) { collisionY = true; collisionOccurred = true }
                if (RectF.intersects(wall, rectBoth)) { collisionCorner = true; collisionOccurred = true }
            }
            
            if (collisionOccurred) {
                val speedSq = car.velX * car.velX + car.velY * car.velY
                if (speedSq > 5f) {
                    val wallDamage = if (car.carClass == CarClass.TANK) (speedSq / 6f).toInt() else (speedSq / 3f).toInt()
                    car.hp -= wallDamage
                    
                    if (car.hp <= 0 && !car.isDead) {
                        val lostCoins = Math.min(car.coins, 3)
                        car.isDead = true
                        car.coins = Math.max(0, car.coins - 3)
                        if (lostCoins > 0) {
                            val inactiveCoins = items.filter { it.type == ItemType.COIN && !it.isActive }.shuffled()
                            for (i in 0 until Math.min(lostCoins, inactiveCoins.size)) {
                                inactiveCoins[i].isActive = true
                                onItemDropped?.invoke(inactiveCoins[i].id)
                            }
                        }
                        toneGen.startTone(ToneGenerator.TONE_CDMA_HIGH_L, 500)
                        for (i in 0 until 40) {
                            val angle = Math.random() * Math.PI * 2
                            val pSpeed = Math.random() * 12 + 2
                            particles.add(Particle(car.x, car.y, (pSpeed * Math.cos(angle)).toFloat(), (pSpeed * Math.sin(angle)).toFloat(), 30, 30, if (Math.random() > 0.5) Color.RED else Color.YELLOW, 6f))
                        }
                        postDelayed({
                            car.x = 100f; car.y = 150f; car.velX = 0f; car.velY = 0f; car.angle = 0f; car.hp = 100; car.isDead = false
                        }, 3000)
                        onPositionUpdate?.invoke(car)
                        return@let
                    } else {
                        toneGen.startTone(ToneGenerator.TONE_CDMA_SOFT_ERROR_LITE, 150)
                    }
                }
            }
            
            val bounceWall = if (car.carClass == CarClass.TANK) 0.2f else 0.5f
            if (collisionX) car.velX = -car.velX * bounceWall else car.x = nextX
            if (collisionY) car.velY = -car.velY * bounceWall else car.y = nextY
            if (!collisionX && !collisionY && collisionCorner) { car.velX = -car.velX * bounceWall; car.velY = -car.velY * bounceWall }
            
            val carRect = RectF(car.x - 20, car.y - 20, car.x + 20, car.y + 20)
            
            // Item & Collector Class checking
            val pickUpRadius = if (car.carClass == CarClass.COLLECTOR) 120f else 30f
            for (item in items) {
                if (item.isActive) {
                    val dx = item.x - car.x
                    val dy = item.y - car.y
                    val dist = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
                    
                    if (dist < pickUpRadius) {
                        if (car.carClass == CarClass.COLLECTOR && dist > 30f) {
                            // Magnet effect
                            item.x -= dx * 0.1f
                            item.y -= dy * 0.1f
                        } else if (dist < 30f) {
                            item.isActive = false
                            when(item.type) {
                                ItemType.NITRO -> nitroTime = System.currentTimeMillis() + 3000
                                ItemType.TELEPORT -> {
                                    teleportIndicatorTime = System.currentTimeMillis() + 2000
                                    toneGen.startTone(ToneGenerator.TONE_CDMA_KEYPAD_VOLUME_KEY_LITE, 1000)
                                    postDelayed({
                                        if (!car.isDead) {
                                            val safeCells = mutableListOf<PointF>()
                                            for (i in 0 until 5) for (j in 0 until 8) {
                                                val cx = i * (VIRTUAL_WIDTH / 5) + (VIRTUAL_WIDTH / 10)
                                                val cy = j * (VIRTUAL_HEIGHT / 8) + (VIRTUAL_HEIGHT / 16)
                                                if (mazeWalls.none { RectF.intersects(it, RectF(cx-15f, cy-15f, cx+15f, cy+15f)) }) safeCells.add(PointF(cx, cy))
                                            }
                                            if (safeCells.isNotEmpty()) {
                                                val d = safeCells.random(); car.x = d.x; car.y = d.y; car.velX = 0f; car.velY = 0f
                                            }
                                        }
                                    }, 2000)
                                }
                                ItemType.HEALTH -> car.hp = Math.min(100, car.hp + 40)
                                ItemType.COIN -> car.coins++
                                ItemType.TRAP_OIL, ItemType.TRAP_MINE -> {
                                    val rad = Math.toRadians((car.angle + 180).toDouble())
                                    traps.add(Trap(traps.size, car.x + 50f * cos(rad).toFloat(), car.y + 50f * sin(rad).toFloat(), item.type, car.id))
                                }
                            }
                            toneGen.startTone(ToneGenerator.TONE_PROP_PROMPT, 100)
                            onItemPickedUp?.invoke(item.id)
                        }
                    }
                }
            }
            
            if (RectF.intersects(finishLine, carRect)) {
                if (items.none { it.type == ItemType.COIN && it.isActive }) {
                    finishTime = System.currentTimeMillis()
                    var winnerCar = car; var maxCoins = car.coins
                    for (e in otherCars.values) if (e.coins > maxCoins) { maxCoins = e.coins; winnerCar = e }
                    for (b in botPlayers) if (b.coins > maxCoins) { maxCoins = b.coins; winnerCar = b }
                    gameWin(winnerCar.name, String.format("%.1fs", (finishTime - startTime) / 1000f))
                    onWin?.invoke(winnerCar.name)
                } else { car.velX = -car.velX * 0.8f; car.velY = -car.velY * 0.8f; car.x += car.velX; car.y += car.velY }
            }
            onPositionUpdate?.invoke(car)
        }

        // --- BOT AI UPDATE (Simplified) ---
        if (isHost && !gameEnded) {
            for (bot in botPlayers) {
                if (bot.isDead) continue
                playerCar?.let { if (!it.isDead) handleCarCollision(bot, it) }
                for (b in botPlayers) if (b.id != bot.id && !b.isDead) handleCarCollision(bot, b)
                
                var tx = finishLine.centerX(); var ty = finishLine.centerY()
                val portalActive = items.none { it.type == ItemType.COIN && it.isActive }
                if (!portalActive) {
                    var minDist = Float.MAX_VALUE
                    for (it in items) if (it.isActive && (it.type == ItemType.COIN || (it.type == ItemType.HEALTH && bot.hp <= 50))) {
                        val d = Math.hypot((it.x - bot.x).toDouble(), (it.y - bot.y).toDouble()).toFloat()
                        if (d < minDist) { minDist = d; tx = it.x; ty = it.y }
                    }
                }
                val angleToTarget = Math.toDegrees(Math.atan2((ty - bot.y).toDouble(), (tx - bot.x).toDouble())).toFloat()
                var diff = (angleToTarget - bot.angle + 180) % 360 - 180
                val rad = Math.toRadians(bot.angle.toDouble())
                val frontRect = RectF(bot.x + cos(rad).toFloat()*35 - 15, bot.y + sin(rad).toFloat()*35 - 15, bot.x + cos(rad).toFloat()*35 + 15, bot.y + sin(rad).toFloat()*35 + 15)
                var blocked = mazeWalls.any { RectF.intersects(it, frontRect) }
                if (blocked) bot.angle += 12f else { if (diff > 5) bot.angle += 5f else if (diff < -5) bot.angle -= 5f }
                val accel = if (blocked) 0.15f else botSpeedMultiplier
                bot.velX += (accel * cos(Math.toRadians(bot.angle.toDouble()))).toFloat(); bot.velY += (accel * sin(Math.toRadians(bot.angle.toDouble()))).toFloat()
                bot.velX *= 0.95f; bot.velY *= 0.95f
                
                val nx = bot.x + bot.velX; val ny = bot.y + bot.velY
                var cx = mazeWalls.any { RectF.intersects(it, RectF(nx-20, bot.y-20, nx+20, bot.y+20)) }
                var cy = mazeWalls.any { RectF.intersects(it, RectF(bot.x-20, ny-20, bot.x+20, ny+20)) }
                if (cx) bot.velX *= -0.5f else bot.x = nx
                if (cy) bot.velY *= -0.5f else bot.y = ny
                
                val botRect = RectF(bot.x-20, bot.y-20, bot.x+20, bot.y+20)
                for (it in items) if (it.isActive && RectF.intersects(botRect, RectF(it.x-20, it.y-20, it.x+20, it.y+20))) {
                    it.isActive = false; if (it.type == ItemType.COIN) bot.coins++ else if (it.type == ItemType.HEALTH) bot.hp = Math.min(100, bot.hp+40)
                    onItemPickedUp?.invoke(it.id)
                }
                if (bot.hp <= 0) { bot.isDead = true; postDelayed({ bot.x = 100f; bot.y = 150f; bot.hp = 100; bot.isDead = false }, 3000) }
                onPositionUpdate?.invoke(bot)
            }
        }
    }

    fun gameWin(name: String, timeResult: String = "") {
        gameEnded = true; winnerName = name; winnerTimeStr = timeResult; invalidate()
    }

    fun resetGame(newSeed: Long) {
        gameEnded = false; winnerName = ""; winnerTimeStr = ""; nitroTime = 0L; teleportIndicatorTime = 0L; startTime = System.currentTimeMillis()
        skidMarks.clear(); particles.clear(); traps.clear(); setMazeSeed(newSeed)
        playerCar?.let { it.x = 100f; it.y = if (it.color == Color.RED) 80f else 150f; it.angle = 0f; it.velX = 0f; it.velY = 0f; it.coins = 0; it.hp = 100; it.isDead = false }
        botPlayers.forEachIndexed { i, bot -> bot.x = 100f; bot.y = 150f+((i+1)*50f); bot.angle = 0f; bot.velX = 0f; bot.velY = 0f; bot.coins = 0; bot.hp = 100; bot.isDead = false }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.parseColor("#1a1a1a"))
        canvas.save()
        canvas.scale(screenScaleX, screenScaleY)

        for (zone in zones) {
            paint.color = when (zone.type) { ZoneType.ICE -> Color.parseColor("#44AEEEEE"); ZoneType.MUD -> Color.parseColor("#448B4513"); ZoneType.OIL -> Color.parseColor("#88222222") }
            canvas.drawRect(zone.rect, paint)
        }
        paint.color = Color.parseColor("#444444"); paint.style = Paint.Style.FILL
        for (wall in mazeWalls) canvas.drawRect(wall, paint)
        
        val portalActive = items.none { it.type == ItemType.COIN && it.isActive }
        paint.color = if (portalActive) Color.WHITE else Color.RED
        canvas.drawCircle(finishLine.centerX(), finishLine.centerY(), finishLine.width() / 2, paint)

        for (mark in skidMarks) { paint.color = Color.BLACK; paint.alpha = mark.alpha; canvas.drawCircle(mark.x, mark.y, 4f, paint) }
        paint.alpha = 255

        for (trap in traps) {
            paint.color = if (trap.type == ItemType.TRAP_OIL) Color.BLACK else Color.RED
            canvas.drawCircle(trap.x, trap.y, 12f, paint)
        }

        for (p in particles) { paint.color = p.color; paint.alpha = (255f * (p.life.toFloat() / p.maxLife.toFloat())).toInt() ?: 255; canvas.drawCircle(p.x, p.y, p.size, paint) }
        paint.alpha = 255

        for (item in items) if (item.isActive) {
            paint.color = if (item.type == ItemType.COIN) Color.YELLOW else Color.WHITE
            canvas.drawCircle(item.x, item.y, 12f, paint)
        }

        playerCar?.let { drawCar(canvas, it) }
        otherCars.values.forEach { drawCar(canvas, it) }
        botPlayers.forEach { drawCar(canvas, it) }

        // Fog of War
        playerCar?.let { car ->
            if (!gameEnded && !car.isDead) {
                val fogPaint = Paint()
                val targetRadius = if (System.currentTimeMillis() < teleportIndicatorTime) 650f else 350f
                fogPaint.shader = RadialGradient(car.x, car.y, targetRadius, intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, Color.BLACK), floatArrayOf(0f, 0.4f, 1f), Shader.TileMode.CLAMP)
                canvas.drawRect(0f, 0f, VIRTUAL_WIDTH, VIRTUAL_HEIGHT, fogPaint)
            }
        }

        // --- MINIMAP / RADAR ---
        val mmSize = 250f
        val mmPadding = 20f
        val mmX = VIRTUAL_WIDTH - mmSize - mmPadding
        val mmY = mmPadding
        val mmScaleX = mmSize / VIRTUAL_WIDTH
        val mmScaleY = mmSize / VIRTUAL_HEIGHT

        // Background Minimap
        paint.color = Color.argb(180, 0, 0, 0)
        canvas.drawRect(mmX, mmY, mmX + mmSize, mmY + mmSize, paint)
        paint.color = Color.WHITE; paint.style = Paint.Style.STROKE; paint.strokeWidth = 2f
        canvas.drawRect(mmX, mmY, mmX + mmSize, mmY + mmSize, paint); paint.style = Paint.Style.FILL

        // Draw Walls on Minimap
        paint.color = Color.DKGRAY
        for (wall in mazeWalls) canvas.drawRect(mmX + wall.left * mmScaleX, mmY + wall.top * mmScaleY, mmX + wall.right * mmScaleX, mmY + wall.bottom * mmScaleY, paint)

        // Draw Coins on Minimap
        paint.color = Color.YELLOW
        for (item in items) if (item.isActive && item.type == ItemType.COIN) canvas.drawCircle(mmX + item.x * mmScaleX, mmY + item.y * mmScaleY, 3f, paint)

        // Draw Cars on Minimap
        playerCar?.let { paint.color = Color.RED; canvas.drawCircle(mmX + it.x * mmScaleX, mmY + it.y * mmScaleY, 5f, paint) }
        paint.color = Color.BLUE
        for (other in otherCars.values) if (!other.isDead) canvas.drawCircle(mmX + other.x * mmScaleX, mmY + other.y * mmScaleY, 4f, paint)
        paint.color = Color.MAGENTA
        for (bot in botPlayers) if (!bot.isDead) canvas.drawCircle(mmX + bot.x * mmScaleX, mmY + bot.y * mmScaleY, 4f, paint)
        
        // Draw Exit on Minimap
        paint.color = if (portalActive) Color.GREEN else Color.RED
        canvas.drawRect(mmX + finishLine.left * mmScaleX, mmY + finishLine.top * mmScaleY, mmX + finishLine.right * mmScaleX, mmY + finishLine.bottom * mmScaleY, paint)

        canvas.restore()
        
        // HUD UI
        paint.typeface = Typeface.DEFAULT_BOLD; paint.textSize = 40f; paint.textAlign = Paint.Align.LEFT
        playerCar?.let { 
            paint.color = Color.WHITE
            canvas.drawText("CLASS: ${it.carClass}", 30f, 60f, paint)
            canvas.drawText("COINS: ${it.coins}/10", 30f, 110f, paint)
        }
        
        if (gameEnded) {
            paint.color = Color.argb(200, 0, 0, 0); canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
            paint.color = Color.YELLOW; paint.textSize = 70f; paint.textAlign = Paint.Align.CENTER
            canvas.drawText("${winnerName.uppercase()} WINS!", width / 2f, height / 2f, paint)
        }
    }

    private fun drawCar(canvas: Canvas, car: Car) {
        if (car.isDead) return
        canvas.save(); canvas.translate(car.x, car.y); canvas.rotate(car.angle)
        paint.color = car.color; canvas.drawRect(-30f, -20f, 30f, 20f, paint)
        
        // Visual indicator for class
        paint.color = Color.WHITE; paint.style = Paint.Style.STROKE; paint.strokeWidth = 2f
        if (car.carClass == CarClass.TANK) canvas.drawRect(-35f, -25f, 35f, 25f, paint)
        else if (car.carClass == CarClass.COLLECTOR) canvas.drawCircle(0f, 0f, 40f, paint)
        paint.style = Paint.Style.FILL
        
        canvas.restore()
        paint.color = Color.RED; canvas.drawRect(car.x - 25f, car.y - 45f, car.x + 25f, car.y - 40f, paint)
        paint.color = Color.GREEN; canvas.drawRect(car.x - 25f, car.y - 45f, car.x - 25f + (50f * (car.hp.toFloat() / 100f)), car.y - 40f, paint)
        paint.color = Color.WHITE; paint.textSize = 25f; paint.textAlign = Paint.Align.CENTER; canvas.drawText(car.name, car.x, car.y - 55f, paint)
    }

    fun handleInput(left: Boolean, right: Boolean, accel: Boolean) {
        leftDown = left; rightDown = right; accelDown = accel
    }
}
