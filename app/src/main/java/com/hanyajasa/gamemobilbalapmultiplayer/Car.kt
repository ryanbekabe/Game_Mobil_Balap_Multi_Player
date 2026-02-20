package com.hanyajasa.gamemobilbalapmultiplayer

data class Car(
    val id: String,
    var x: Float,
    var y: Float,
    var angle: Float,
    var color: Int,
    var name: String,
    var velX: Float = 0f,
    var velY: Float = 0f,
    var coins: Int = 0,
    var hp: Int = 100,
    var isDead: Boolean = false
)
