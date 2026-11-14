package dev.mtzforge.sampleicons

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

class InfoActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply {
            setPadding(48, 48, 48, 48)
            text = "Pack de iconos de prueba para MTZ Forge.\nÁbrelo desde MTZ Forge → Pack instalado."
        })
    }
}
