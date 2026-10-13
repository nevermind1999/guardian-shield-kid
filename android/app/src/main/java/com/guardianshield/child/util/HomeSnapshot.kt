package com.guardianshield.child.util

import android.graphics.Bitmap

/**
 * "Foto" desfocada da tela inicial, pra tela de bloqueio usar de fundo (o popup aparece
 * por cima da Home embaçada). Desfoque entre janelas do Android não existe em muitos
 * aparelhos (no Galaxy A06 vem desligado), então a própria Home desenha a si mesma
 * pequena e desfocada — ver LauncherHomeActivity.captureBlurredHome.
 */
object HomeSnapshot {
    /** Registrado pela Home enquanto ela existe. Chamado na thread principal. */
    @JvmStatic
    var provider: (() -> Bitmap?)? = null

    /** Último print da Home (já reduzido e desfocado) tirado pelo serviço de
     * acessibilidade — tem o papel de parede de verdade, então é o preferido. */
    @Volatile
    @JvmStatic
    var cachedScreen: Bitmap? = null

    @JvmStatic
    fun capture(): Bitmap? = cachedScreen ?: try {
        provider?.invoke()
    } catch (e: Exception) {
        null
    }

    /** Desfoque de caixa (horizontal + vertical), [passes] vezes — barato numa imagem já
     * reduzida (algumas dezenas de pixels de lado). */
    @JvmStatic
    fun boxBlur(src: Bitmap, radius: Int, passes: Int): Bitmap {
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        val tmp = IntArray(w * h)
        repeat(passes) {
            blurLine(pixels, tmp, w, h, radius, horizontal = true)
            blurLine(tmp, pixels, w, h, radius, horizontal = false)
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun blurLine(input: IntArray, output: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val outer = if (horizontal) h else w
        val inner = if (horizontal) w else h
        for (o in 0 until outer) {
            for (i in 0 until inner) {
                var a = 0; var red = 0; var g = 0; var b = 0; var n = 0
                for (k in -r..r) {
                    val j = (i + k).coerceIn(0, inner - 1)
                    val c = if (horizontal) input[o * w + j] else input[j * w + o]
                    a += c ushr 24; red += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF; n++
                }
                val idx = if (horizontal) o * w + i else i * w + o
                output[idx] = ((a / n) shl 24) or ((red / n) shl 16) or ((g / n) shl 8) or (b / n)
            }
        }
    }
}
