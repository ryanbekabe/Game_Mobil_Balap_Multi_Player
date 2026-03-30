package com.hanyajasa.gamemobilbalapmultiplayer

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.animation.AnimationUtils
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class SplashActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        val title = findViewById<TextView>(R.id.splashTitle)
        val subtitle = findViewById<TextView>(R.id.splashSubtitle)

        // Simple fade-in animation
        val fadeIn = AnimationUtils.loadAnimation(this, android.R.anim.fade_in)
        fadeIn.duration = 1000
        title.startAnimation(fadeIn)
        subtitle.startAnimation(fadeIn)

        // Delay for 3 seconds then start MainActivity
        Handler(Looper.getMainLooper()).postDelayed({
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }, 3000)
    }
}