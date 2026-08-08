package com.guardianshield.child

import android.content.Intent
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.guardianshield.child.util.GuardianPrefs
import com.guardianshield.child.util.VideoTransform

/**
 * "Simulação de recorte": mostra o vídeo escolhido tocando de verdade, em tela cheia, e
 * deixa o usuário belisco pra dar zoom e arrastar pra posicionar — exatamente como vai
 * aparecer na Home depois. Só grava a escolha (URI + zoom/posição) quando "Salvar" é
 * tocado; "Cancelar" não muda nada do que já estava configurado.
 */
class VideoCropActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_VIDEO_URI = "extra_video_uri"
    }

    private lateinit var textureView: TextureView
    private var mediaPlayer: MediaPlayer? = null
    private var videoUri: Uri? = null
    private var videoWidth = 0
    private var videoHeight = 0

    // Estado do enquadramento sendo ajustado nesta tela (começa do que já estava salvo,
    // ou do padrão centralizado/sem zoom se for um vídeo novo).
    private var userScale = 1f
    private var panX = 0.5f
    private var panY = 0.5f

    private lateinit var scaleGestureDetector: ScaleGestureDetector
    private var lastTouchX = 0f
    private var lastTouchY = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_video_crop)

        val uriExtra = intent.getStringExtra(EXTRA_VIDEO_URI) ?: GuardianPrefs.videoWallpaperUri(this)
        if (uriExtra == null) {
            finish()
            return
        }
        videoUri = Uri.parse(uriExtra)

        // Se for o mesmo vídeo já configurado, começa do enquadramento salvo (ajuste fino).
        // Se for um vídeo novo, começa centralizado e sem zoom.
        if (uriExtra == GuardianPrefs.videoWallpaperUri(this)) {
            userScale = GuardianPrefs.videoCropScale(this)
            panX = GuardianPrefs.videoCropPanX(this)
            panY = GuardianPrefs.videoCropPanY(this)
        }

        textureView = findViewById(R.id.cropVideoPreview)
        scaleGestureDetector = ScaleGestureDetector(this, ScaleListener())

        textureView.setOnTouchListener { _, event ->
            scaleGestureDetector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastTouchX = event.x
                    lastTouchY = event.y
                }
                MotionEvent.ACTION_MOVE -> {
                    if (event.pointerCount == 1 && !scaleGestureDetector.isInProgress) {
                        applyPanDelta(event.x - lastTouchX, event.y - lastTouchY)
                        lastTouchX = event.x
                        lastTouchY = event.y
                        updateTransform()
                    }
                }
            }
            true
        }

        if (textureView.isAvailable) {
            startPreview()
        } else {
            textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                    startPreview()
                }
                override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                    updateTransform()
                }
                override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                    releasePlayer()
                    return true
                }
                override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
            }
        }

        findViewById<Button>(R.id.cropCancelButton).setOnClickListener {
            setResult(RESULT_CANCELED)
            finish()
        }
        findViewById<Button>(R.id.cropSaveButton).setOnClickListener {
            val uri = videoUri
            if (uri != null) {
                GuardianPrefs.setVideoWallpaperUri(this, uri.toString())
                GuardianPrefs.setVideoCrop(this, userScale, panX, panY)
            }
            setResult(RESULT_OK)
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        releasePlayer()
    }

    private fun startPreview() {
        val uri = videoUri ?: return
        val surfaceTexture = textureView.surfaceTexture ?: return
        try {
            val player = MediaPlayer()
            player.setSurface(Surface(surfaceTexture))
            player.setDataSource(this, uri)
            player.isLooping = true
            player.setVolume(0f, 0f)
            player.setOnPreparedListener { mp ->
                videoWidth = mp.videoWidth
                videoHeight = mp.videoHeight
                updateTransform()
                mp.start()
            }
            player.setOnErrorListener { _, _, _ -> true }
            player.prepareAsync()
            mediaPlayer = player
        } catch (e: Exception) {
            finish()
        }
    }

    private fun releasePlayer() {
        mediaPlayer?.apply {
            try {
                stop()
            } catch (e: Exception) {
                // já parado, sem problema
            }
            release()
        }
        mediaPlayer = null
    }

    private fun updateTransform() {
        if (videoWidth > 0 && videoHeight > 0) {
            VideoTransform.apply(textureView, videoWidth, videoHeight, userScale, panX, panY)
        }
    }

    /** Converte o arrasto em pixels pra fração de "folga" consumida (0..1) em cada eixo. */
    private fun applyPanDelta(dxPixels: Float, dyPixels: Float) {
        if (videoWidth <= 0 || videoHeight <= 0) return
        val viewWidth = textureView.width.toFloat()
        val viewHeight = textureView.height.toFloat()
        val baseScale = maxOf(viewWidth / videoWidth, viewHeight / videoHeight)
        val scale = baseScale * userScale
        val slackX = videoWidth * scale - viewWidth
        val slackY = videoHeight * scale - viewHeight
        if (slackX > 0) panX = (panX - dxPixels / slackX).coerceIn(0f, 1f)
        if (slackY > 0) panY = (panY - dyPixels / slackY).coerceIn(0f, 1f)
    }

    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            userScale = (userScale * detector.scaleFactor).coerceIn(1f, GuardianPrefs.MAX_VIDEO_CROP_SCALE)
            updateTransform()
            return true
        }
    }
}
