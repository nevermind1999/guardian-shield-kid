package com.guardianshield.child.util

import android.graphics.Matrix
import android.view.TextureView
import kotlin.math.max

/**
 * Calcula e aplica a matriz de um TextureView pra enquadrar um vídeo cobrindo toda a área
 * disponível (equivalente a centerCrop), com zoom e posição extras escolhidos pelo usuário
 * na simulação de recorte (VideoCropActivity). Usado tanto ali (preview ao vivo) quanto na
 * Home (renderização final), garantindo que os dois lugares calculem exatamente igual.
 */
object VideoTransform {

    fun apply(
        textureView: TextureView,
        videoWidth: Int,
        videoHeight: Int,
        userScale: Float,
        panX: Float,
        panY: Float
    ) {
        val viewWidth = textureView.width.toFloat()
        val viewHeight = textureView.height.toFloat()
        if (viewWidth <= 0f || viewHeight <= 0f || videoWidth <= 0 || videoHeight <= 0) return

        val baseScale = max(viewWidth / videoWidth, viewHeight / videoHeight)
        val scale = baseScale * userScale
        val scaledWidth = videoWidth * scale
        val scaledHeight = videoHeight * scale
        val slackX = scaledWidth - viewWidth
        val slackY = scaledHeight - viewHeight

        val matrix = Matrix()
        matrix.setScale(scale, scale)
        matrix.postTranslate(-slackX * panX, -slackY * panY)
        textureView.setTransform(matrix)
    }
}
