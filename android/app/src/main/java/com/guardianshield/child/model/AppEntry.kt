package com.guardianshield.child.model

import android.graphics.drawable.Drawable

/** Um app instalado e "abrível" no aparelho, pronto para renderizar na Home/Gaveta nativas. */
data class AppEntry(
    val packageName: String,
    val label: String,
    val icon: Drawable
)
