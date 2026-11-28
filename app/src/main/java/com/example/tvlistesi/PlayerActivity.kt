package com.example.tvlistesi

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.media.AudioManager
import android.provider.Settings
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.text.InputFilter
import android.widget.FrameLayout
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.annotation.OptIn
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.tvlistesi.cast.CastAction
import com.example.tvlistesi.cast.CastDevice
import com.example.tvlistesi.cast.CastPrefs
import com.example.tvlistesi.cast.CastReceiver
import com.example.tvlistesi.cast.CastSender
import com.example.tvlistesi.data.Channel
import com.example.tvlistesi.data.DlnaDevice
import com.example.tvlistesi.data.ExternalPlayer
import com.example.tvlistesi.data.PlaylistParser
import com.example.tvlistesi.data.PlaylistRepository
import com.example.tvlistesi.databinding.ActivityPlayerBinding
import com.example.tvlistesi.databinding.DialogCastRemoteBinding
import com.example.tvlistesi.databinding.DialogDlnaDevicesBinding
import com.example.tvlistesi.dlna.DlnaClient
import com.example.tvlistesi.dlna.DlnaDiscovery
import com.example.tvlistesi.ui.DlnaAddressDialog
import com.example.tvlistesi.ui.DlnaDeviceAdapter
import com.example.tvlistesi.ui.PlayerChannelAdapter
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

@OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlayerBinding
    private var player: ExoPlayer? = null
    private var currentIndex = 0

    private var externalUrl: String? = null
    private var externalTitle: String? = null
    private var externalMime: String? = null
    private var hideList = false
    private var castDevice: DlnaDevice? = null
    private var castTarget: CastDevice? = null
    private var remoteOnly = false
    private var remoteDialogShown = false

    private val channelAdapter = PlayerChannelAdapter { index -> play(index) }
    private var panelPreferred = true

    private val handler = Handler(Looper.getMainLooper())
    private var controlsVisible = true
    private var hiddenAt = 0L
    private var seeking = false
    private val hideControlsRunnable = Runnable { hideControls() }
    private val updateTimeRunnable = object : Runnable {
        override fun run() {
            updateSeekTime()
            handler.postDelayed(this, TIME_UPDATE_INTERVAL)
        }
    }

    // Kaydırma hareketleri
    private lateinit var audioManager: AudioManager
    private lateinit var gestureDetector: GestureDetector
    private var gestureEnabled = true
    private var downX = 0f
    private var downY = 0f
    private var adjustMode = ADJUST_NONE
    private var brightnessValue = -1f
    private var volumeValue = 0f
    private var originalBrightness = -1f
    private val hideIndicatorRunnable = Runnable { binding.gestureIndicator.isVisible = false }

    private val insetsController: WindowInsetsControllerCompat by lazy {
        WindowCompat.getInsetsController(window, window.decorView).also {
            it.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            binding.buffering.isVisible = false
            val detail = error.message?.take(140)?.replace('\n', ' ')?.trim()
            showError(
                getString(R.string.oynatma_hatasi, error.errorCodeName) +
                    if (detail.isNullOrBlank()) "" else "\n$detail"
            )
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            binding.buffering.isVisible = playbackState == Player.STATE_BUFFERING
            if (playbackState == Player.STATE_READY) hideError()
            updateSeekControls()
        }

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            updateSeekControls()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            binding.playPauseButton.setImageResource(
                if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
            )
            binding.playPauseButton.contentDescription = getString(
                if (isPlaying) R.string.duraklat else R.string.oynat
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Video tam ekran; üst/alt çubuklar sistem çubuklarının altına taşmadan yerleşsin.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        setupInsets()

        currentIndex = savedInstanceState?.getInt(EXTRA_INDEX)
            ?: intent.getIntExtra(EXTRA_INDEX, 0)

        externalUrl = savedInstanceState?.getString(EXTRA_URL) ?: intent.getStringExtra(EXTRA_URL)
        externalTitle = savedInstanceState?.getString(EXTRA_TITLE)
            ?: intent.getStringExtra(EXTRA_TITLE)
        externalMime = savedInstanceState?.getString(EXTRA_MIME)
            ?: intent.getStringExtra(EXTRA_MIME)
        hideList = savedInstanceState?.getBoolean(EXTRA_HIDE_LIST)
            ?: intent.getBooleanExtra(EXTRA_HIDE_LIST, false)
        panelPreferred = !hideList
        remoteOnly = intent.action == ACTION_OPEN_CAST_REMOTE

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        originalBrightness = window.attributes.screenBrightness
        gestureDetector = GestureDetector(this, gestureListener)

        binding.channelList.layoutManager = LinearLayoutManager(this)
        binding.channelList.adapter = channelAdapter
        binding.channelList.setHasFixedSize(true)

        binding.listButton.setOnClickListener { toggleChannelPanel() }
        binding.panelCloseButton.setOnClickListener {
            setPanelVisible(false, userAction = true)
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.channelPanel.isVisible) {
                    setPanelVisible(false, userAction = true)
                } else {
                    finish()
                }
            }
        })

        binding.backButton.setOnClickListener { finish() }
        binding.rotateButton.setOnClickListener { toggleOrientation() }
        binding.sizeButton.setOnClickListener { cycleScreenMode() }
        binding.castButton.setOnClickListener { showCastDialog() }
        binding.mirrorButton.setOnClickListener {
            if (castTarget != null) showCastRemoteDialog() else showCastSendDialog()
        }
        binding.externalButton.setOnClickListener { openExternally() }
        binding.retryButton.setOnClickListener { play(currentIndex) }
        binding.playPauseButton.setOnClickListener { togglePlayPause() }
        binding.rewindButton.setOnClickListener {
            showControls()
            seekBy(-SEEK_STEP_MS)
        }
        binding.forwardButton.setOnClickListener {
            showControls()
            seekBy(SEEK_STEP_MS)
        }
        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    binding.seekTime.text = getString(
                        R.string.zaman_bilgisi,
                        formatTime(progress.toLong()),
                        formatTime(seekBar?.max?.toLong() ?: 0L)
                    )
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                seeking = true
                showControls()
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                seeking = false
                seekToMs(seekBar?.progress?.toLong() ?: 0L)
                showControls()
            }
        })

        binding.playerView.setOnClickListener { showControls() }
        binding.root.setOnClickListener { showControls() }

        applyScreenMode(PlaylistRepository.screenMode(this))
        updatePanelWidth()
        showGestureHintOnce()
    }

    /** Kaydırma hareketlerini ilk kez kullanan kullanıcıya kısa bir ipucu gösterir. */
    private fun showGestureHintOnce() {
        val prefs = getSharedPreferences("tv_listesi_ayarlar", Context.MODE_PRIVATE)
        if (prefs.getBoolean("gesture_ipucu_gosterildi", false)) return
        Toast.makeText(this, R.string.gesture_ipucu, Toast.LENGTH_LONG).show()
        prefs.edit().putBoolean("gesture_ipucu_gosterildi", true).apply()
    }


    private fun togglePlayPause() {
        val exoPlayer = player ?: return
        when {
            exoPlayer.isPlaying -> exoPlayer.pause()
            exoPlayer.playbackState == Player.STATE_IDLE -> play(currentIndex)
            else -> exoPlayer.play()
        }
    }

    // ------------------------------------------------------------ zaman kaydırma

    /** Yayın ileri/geri sarılabiliyorsa zaman çizgisini ve süreyi gösterir. */
    private fun updateSeekControls() {
        val exoPlayer = player
        val seekable = exoPlayer != null &&
            exoPlayer.playbackState != Player.STATE_IDLE &&
            exoPlayer.isCurrentMediaItemSeekable

        binding.seekGroup.isVisible = seekable
        if (seekable) {
            val duration = exoPlayer!!.duration
            if (duration != C.TIME_UNSET && duration > 0L) {
                setSeekRange(duration)
            }
            updateSeekTime()
        } else {
            handler.removeCallbacks(updateTimeRunnable)
        }
    }

    private fun setSeekRange(durationMs: Long) {
        val max = durationMs.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
        if (binding.seekBar.max != max) binding.seekBar.max = max
    }

    private fun updateSeekTime() {
        val exoPlayer = player ?: return
        val duration = exoPlayer.duration
        if (!exoPlayer.isCurrentMediaItemSeekable ||
            duration == C.TIME_UNSET || duration <= 0L
        ) {
            binding.seekGroup.isVisible = false
            handler.removeCallbacks(updateTimeRunnable)
            return
        }

        binding.seekGroup.isVisible = true
        setSeekRange(duration)
        val position = exoPlayer.currentPosition.coerceAtLeast(0L)
        binding.seekTime.text =
            getString(R.string.zaman_bilgisi, formatTime(position), formatTime(duration))

        // Kullanıcı çizgiyi sürüklerken oynatıcının konumuyla üzerine yazmayalım.
        if (!seeking) {
            binding.seekBar.progress =
                position.coerceIn(0L, binding.seekBar.max.toLong()).toInt()
        }
    }

    /** Zaman çizgisindeki veya kumandadaki konuma sarar. */
    private fun seekToMs(positionMs: Long) {
        val exoPlayer = player ?: return
        if (!exoPlayer.isCurrentMediaItemSeekable) return
        val duration = exoPlayer.duration
        val target = if (duration == C.TIME_UNSET || duration <= 0L) {
            positionMs.coerceAtLeast(0L)
        } else {
            positionMs.coerceIn(0L, duration)
        }
        exoPlayer.seekTo(target)
        updateSeekTime()
    }

    /** Kumanda ve ekrandaki sarma düğmeleri 15 saniyelik adımlarla kaydırır. */
    private fun seekBy(deltaMs: Long) {
        val exoPlayer = player ?: return
        if (!exoPlayer.isCurrentMediaItemSeekable) {
            Toast.makeText(this, R.string.sar_yok, Toast.LENGTH_SHORT).show()
            return
        }
        seekToMs(exoPlayer.currentPosition + deltaMs)
        showSeekIndicator(deltaMs)
    }

    /** Telefondan gelen yansıtma komutunu TV oynatıcısında uygular. */
    private fun applyCastControl(action: String) {
        when (action) {
            CastAction.VOLUME_UP -> audioManager.adjustStreamVolume(
                AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI
            )

            CastAction.VOLUME_DOWN -> audioManager.adjustStreamVolume(
                AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI
            )

            CastAction.SEEK_BACK -> seekBy(-SEEK_STEP_MS)
            CastAction.SEEK_FORWARD -> seekBy(SEEK_STEP_MS)
            CastAction.PLAY_PAUSE -> {
                // Tamponlama sırasında da çalışması için isteği doğrudan değiştir.
                player?.let { it.playWhenReady = !it.playWhenReady }
            }
            CastAction.STOP -> {
                player?.pause()
                finish()
            }
        }
    }

    private fun showSeekIndicator(deltaMs: Long) {
        val exoPlayer = player ?: return
        val direction = if (deltaMs < 0) R.string.geri_sar else R.string.ileri_sar
        val seconds = abs(deltaMs) / 1000
        val value = if (deltaMs < 0) "-$seconds" else "+$seconds"

        binding.gestureIcon.setImageResource(R.drawable.ic_saat)
        binding.gestureLabel.text = getString(direction)
        binding.gestureValue.text = getString(R.string.saniye, value)
        val duration = exoPlayer.duration
        val percent = if (duration > 0L) {
            (exoPlayer.currentPosition * 100 / duration).toInt().coerceIn(0, 100)
        } else {
            0
        }
        binding.gestureProgress.setProgressCompat(percent, true)
        binding.gestureIndicator.isVisible = true
        handler.removeCallbacks(hideIndicatorRunnable)
        handler.postDelayed(hideIndicatorRunnable, 900L)
    }

    private fun formatTime(ms: Long): String {
        if (ms == C.TIME_UNSET || ms < 0L) return "--:--"
        val total = ms / 1000
        val hours = total / 3600
        val minutes = (total % 3600) / 60
        val seconds = total % 60
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
    }

    // ------------------------------------------------------- kaydırma hareketleri

    /**
     * Sağa/sola kaydırma kanal değiştirir.
     * Yukarı/aşağı kaydırmada ekranın sol yarısı parlaklığı, sağ yarısı sesi ayarlar.
     */
    private val gestureListener = object : GestureDetector.SimpleOnGestureListener() {

        override fun onDown(e: MotionEvent): Boolean {
            downX = e.x
            downY = e.y
            adjustMode = ADJUST_NONE
            brightnessValue = currentBrightness()
            volumeValue = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()
            return true
        }

        override fun onFling(
            e1: MotionEvent?,
            e2: MotionEvent,
            velocityX: Float,
            velocityY: Float
        ): Boolean {
            if (!gestureEnabled || e1 == null) return false

            val dx = e2.x - e1.x
            val dy = e2.y - e1.y
            if (abs(dx) < abs(dy) * 1.3f) return false

            val minDistance = binding.root.width * 0.12f
            val minVelocity = ViewConfiguration.get(this@PlayerActivity)
                .scaledMinimumFlingVelocity * 1.5f
            if (abs(dx) < minDistance && abs(velocityX) < minVelocity) return false

            if (dx < 0) play(currentIndex + 1) else play(currentIndex - 1)
            return true
        }

        override fun onScroll(
            e1: MotionEvent?,
            e2: MotionEvent,
            distanceX: Float,
            distanceY: Float
        ): Boolean {
            if (!gestureEnabled) return false

            val totalDx = e2.x - downX
            val totalDy = e2.y - downY
            if (abs(totalDy) < abs(totalDx) * 1.2f) return false

            if (adjustMode == ADJUST_NONE) {
                adjustMode = if (downX < binding.root.width / 2f) {
                    ADJUST_BRIGHTNESS
                } else {
                    ADJUST_VOLUME
                }
            }

            // GestureDetector: distanceY > 0 ise parmak yukarı doğru kaymış demektir.
            val fraction = distanceY / (binding.root.height.coerceAtLeast(1) * 0.7f)
            if (adjustMode == ADJUST_BRIGHTNESS) {
                brightnessValue = (brightnessValue + fraction).coerceIn(0.01f, 1f)
                applyBrightness(brightnessValue)
                showIndicator(
                    R.drawable.ic_brightness,
                    getString(R.string.parlaklik),
                    (brightnessValue * 100).roundToInt()
                )
            } else {
                val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                if (max > 0) {
                    volumeValue = (volumeValue + fraction * max).coerceIn(0f, max.toFloat())
                    val level = volumeValue.roundToInt()
                    if (audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) != level) {
                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, level, 0)
                    }
                    showIndicator(
                        R.drawable.ic_volume,
                        getString(R.string.ses_seviyesi),
                        level * 100 / max
                    )
                }
            }
            return true
        }
    }

    private fun currentBrightness(): Float {
        val override = window.attributes.screenBrightness
        if (override >= 0f) return override.coerceIn(0.01f, 1f)
        val system = runCatching {
            Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128)
        }.getOrDefault(128)
        return (system / 255f).coerceIn(0.01f, 1f)
    }

    private fun applyBrightness(value: Float) {
        val attributes = window.attributes
        attributes.screenBrightness = value.coerceIn(0.01f, 1f)
        window.attributes = attributes
    }

    private fun restoreBrightness() {
        val attributes = window.attributes
        attributes.screenBrightness = originalBrightness
        window.attributes = attributes
    }

    private fun showIndicator(iconRes: Int, label: String, percent: Int) {
        val safePercent = percent.coerceIn(0, 100)
        binding.gestureIcon.setImageResource(iconRes)
        binding.gestureLabel.text = label
        binding.gestureValue.text = getString(R.string.yuzde, safePercent)
        binding.gestureProgress.setProgressCompat(safePercent, true)
        binding.gestureIndicator.isVisible = true
        handler.removeCallbacks(hideIndicatorRunnable)
        handler.postDelayed(hideIndicatorRunnable, 900L)
    }

    /** Yatay ve dikey mod arasında geçiş yapar; yayın kesilmez. */
    private fun toggleOrientation() {
        requestedOrientation =
            if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            } else {
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
    }

    /** Geçerli kanalı cihazdaki harici bir oynatıcıya devreder. */
    private fun openExternally() {
        val channel = currentChannel() ?: return
        if (!ExternalPlayer.open(this, channel)) {
            Toast.makeText(this, R.string.harici_oynatici_yok, Toast.LENGTH_SHORT).show()
        }
    }

    /** DLNA sunucusundan açılan tek öğe için geçici kanal. */
    private fun externalChannel(): Channel? = externalUrl?.let { url ->
        Channel(
            id = "dlna",
            name = externalTitle ?: getString(R.string.dlna_oge),
            url = url,
            group = getString(R.string.dlna_sunucu_cihaz)
        )
    }

    private fun currentChannel(): Channel? =
        externalChannel() ?: PlaylistRepository.channels.getOrNull(currentIndex)

    /**
     * Görüntü modunu Sığdır → Kırp → Esnet → Sığdır sırasıyla değiştirir.
     * Seçim hatırlanır, sonraki açılışlarda da uygulanır.
     */
    private fun cycleScreenMode() {
        val next = when (binding.playerView.resizeMode) {
            AspectRatioFrameLayout.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        }

        applyScreenMode(next)
        PlaylistRepository.setScreenMode(this, next)
        Toast.makeText(
            this,
            getString(R.string.goruntu_modu, screenModeName(next)),
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun applyScreenMode(mode: Int) {
        val safeMode = when (mode) {
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
            AspectRatioFrameLayout.RESIZE_MODE_FILL -> mode
            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        }

        binding.playerView.resizeMode = safeMode
        binding.sizeButton.contentDescription =
            getString(R.string.goruntu_modu, screenModeName(safeMode))
    }

    private fun screenModeName(mode: Int): String = getString(
        when (mode) {
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> R.string.goruntu_kirp
            AspectRatioFrameLayout.RESIZE_MODE_FILL -> R.string.goruntu_esnet
            else -> R.string.goruntu_sigdir
        }
    )

    // ------------------------------------------------------------ kanal paneli

    /**
     * Sistem çubukları (durum/gezinme) gizlense de üst/alt çubukların ve panelin
     * çentik altında kalması için sabit dolgu uygular.
     */
    private fun setupInsets() {
        val topBarTop = binding.topBar.paddingTop
        val topBarLeft = binding.topBar.paddingLeft
        val topBarRight = binding.topBar.paddingRight
        val bottomBarBottom = binding.bottomBar.paddingBottom
        val bottomBarLeft = binding.bottomBar.paddingLeft
        val bottomBarRight = binding.bottomBar.paddingRight
        val panelTop = binding.channelPanel.paddingTop
        val panelBottom = binding.channelPanel.paddingBottom
        val panelRight = binding.channelPanel.paddingRight

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsetsIgnoringVisibility(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout()
            )
            binding.topBar.updatePadding(
                left = topBarLeft + bars.left,
                top = topBarTop + bars.top,
                right = topBarRight + bars.right
            )
            binding.bottomBar.updatePadding(
                left = bottomBarLeft + bars.left,
                right = bottomBarRight + bars.right,
                bottom = bottomBarBottom + bars.bottom
            )
            binding.channelPanel.updatePadding(
                top = panelTop + bars.top,
                right = panelRight + bars.right,
                bottom = panelBottom + bars.bottom
            )
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)
    }

    /**
     * Üst/alt çubukları ve kanal panelini gösterir, sistem çubuklarını geri getirir
     * ve 3 saniyelik gizleme sayacını başlatır.
     *
     * @param userInteraction Gerçek dokunma/tuş gibi kullanıcı etkileşimi ise true.
     *   false olduğunda (odak/pencere olayları) arayüz zaten görünürse sayaç
     *   sıfırlanmaz; böylece cihazlarda tekrarlanan sistem olayları gizlemeyi
     *   engellemez.
     */
    private fun showControls(userInteraction: Boolean = true) {
        if (!::binding.isInitialized) return

        val wasVisible = controlsVisible
        controlsVisible = true

        binding.topBar.animate().cancel()
        binding.bottomBar.animate().cancel()
        binding.channelPanel.animate().cancel()
        binding.topBar.isVisible = true
        binding.bottomBar.isVisible = true
        binding.topBar.alpha = 1f
        binding.bottomBar.alpha = 1f

        handler.removeCallbacks(updateTimeRunnable)
        if (binding.seekGroup.isVisible) handler.post(updateTimeRunnable)

        if (panelPreferred) setPanelVisible(true)

        insetsController.show(WindowInsetsCompat.Type.systemBars())

        // Görünürken gelen sistem/odak olayları sayacı sıfırlamasın.
        if (!userInteraction && wasVisible) return

        handler.removeCallbacks(hideControlsRunnable)
        handler.postDelayed(hideControlsRunnable, CONTROLS_HIDE_DELAY)
    }

    /** Kullanıcı işlem yapmazsa tüm arayüzü (panel ve sistem çubukları dahil) gizler. */
    private fun hideControls() {
        if (!controlsVisible) return
        controlsVisible = false
        hiddenAt = SystemClock.uptimeMillis()
        handler.removeCallbacks(updateTimeRunnable)

        listOf(binding.topBar, binding.bottomBar).forEach { bar ->
            bar.animate()
                .alpha(0f)
                .setDuration(200L)
                .withEndAction {
                    if (!controlsVisible) bar.isVisible = false
                }
                .start()
        }

        if (binding.channelPanel.isVisible) {
            binding.channelPanel.animate().cancel()
            binding.channelPanel.animate()
                .alpha(0f)
                .setDuration(200L)
                .withEndAction {
                    if (!controlsVisible) {
                        binding.channelPanel.isVisible = false
                        applyVideoBounds()
                    }
                }
                .start()
        }

        insetsController.hide(WindowInsetsCompat.Type.systemBars())
    }

    private fun toggleChannelPanel() {
        setPanelVisible(!binding.channelPanel.isVisible, userAction = true)
    }

    /** Sağdaki kanal panelini gösterir/gizler. */
    private fun setPanelVisible(visible: Boolean, userAction: Boolean = false) {
        if (userAction) panelPreferred = visible

        val allowed = canShowChannelPanel()
        val show = visible && panelPreferred && allowed
        val wasVisible = binding.channelPanel.isVisible

        binding.channelPanel.animate().cancel()
        binding.channelPanel.isVisible = show
        binding.channelPanel.alpha = 1f
        binding.listButton.isVisible = allowed
        updateListButtonDescription()
        applyVideoBounds()

        if (show && !wasVisible) {
            channelAdapter.selectedIndex = currentIndex
            scrollToCurrent()
        }
    }

    private fun canShowChannelPanel(): Boolean =
        !hideList && externalChannel() == null && PlaylistRepository.channels.isNotEmpty()

    /** Kanal listesini oynatıcıya bağlar; tek öğe (DLNA) modunda paneli gizler. */
    private fun refreshChannelList() {
        val single = externalChannel() != null
        val channels = PlaylistRepository.channels

        binding.listButton.isVisible = !hideList && !single && channels.isNotEmpty()
        if (hideList || single || channels.isEmpty()) {
            binding.channelPanel.isVisible = false
            updateListButtonDescription()
            return
        }

        binding.channelCount.text = getString(R.string.kanal_sayisi, channels.size)
        channelAdapter.submitList(channels.toList()) { scrollToCurrent() }
        setPanelVisible(panelPreferred)
    }

    private fun scrollToCurrent() {
        if (!binding.channelPanel.isVisible) return

        binding.channelList.post {
            if (!binding.channelPanel.isVisible) return@post
            (binding.channelList.layoutManager as? LinearLayoutManager)
                ?.scrollToPositionWithOffset(currentIndex, 0)
        }
    }

    private fun updateListButtonDescription() {
        binding.listButton.contentDescription = getString(
            if (binding.channelPanel.isVisible) {
                R.string.kanal_listesini_gizle
            } else {
                R.string.kanal_listesini_goster
            }
        )
    }

    /** Panel açıkken videoyu sol tarafta kalan alana sığdırır. */
    private fun applyVideoBounds() {
        binding.root.post {
            val rootWidth = binding.root.width
            if (rootWidth <= 0) return@post

            val params = binding.playerView.layoutParams as? FrameLayout.LayoutParams
                ?: return@post
            val panelShown = binding.channelPanel.isVisible
            val panelWidth = binding.channelPanel.layoutParams.width

            val targetWidth = if (panelShown) {
                (rootWidth - panelWidth).coerceAtLeast(0)
            } else {
                ViewGroup.LayoutParams.MATCH_PARENT
            }

            if (params.width != targetWidth) {
                params.width = targetWidth
                binding.playerView.layoutParams = params
            }

            binding.buffering.translationX =
                if (panelShown) -panelWidth / 2f else 0f
        }
    }

    /** Panel genişliğini ekran boyutuna ve yöne göre ayarlar. */
    private fun updatePanelWidth() {
        val metrics = resources.displayMetrics
        val widthDp = metrics.widthPixels / metrics.density
        val landscape =
            resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

        val panelDp = if (landscape) {
            (widthDp * 0.30f).coerceIn(240f, 340f)
        } else {
            (widthDp * 0.58f).coerceAtMost(260f)
        }

        binding.channelPanel.layoutParams =
            binding.channelPanel.layoutParams.apply {
                width = (panelDp * metrics.density).toInt()
            }

        if (binding.channelPanel.isVisible) applyVideoBounds()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updatePanelWidth()
        // Döndürme/sistem olayı sayacı sıfırlamasın.
        showControls(userInteraction = false)
    }

    // ------------------------------------------------------------ DLNA

    /** Ağdaki DLNA oynatıcılarını listeleyip geçerli kanalı gönderir. */
    private fun showCastDialog() {
        val channel = currentChannel()
        if (channel == null) {
            Toast.makeText(this, R.string.kanal_bulunamadi, Toast.LENGTH_SHORT).show()
            return
        }

        val dialogBinding = DialogDlnaDevicesBinding.inflate(layoutInflater)
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dlna_gonder)
            .setView(dialogBinding.root)
            .setNegativeButton(R.string.kapat, null)
            .create()

        val found = mutableListOf<DlnaDevice>()
        val manual = mutableListOf<DlnaDevice>()
        val adapter = DlnaDeviceAdapter { device ->
            sendToDevice(channel, device, dialog, dialogBinding)
        }

        dialogBinding.deviceList.layoutManager = LinearLayoutManager(this)
        dialogBinding.deviceList.adapter = adapter
        dialogBinding.stopButton.isVisible = castDevice != null

        fun render() {
            val devices = (found + manual).distinctBy { it.udn }
            adapter.submitList(devices)
            dialogBinding.statusText.isVisible = devices.isEmpty()
            if (devices.isEmpty()) {
                dialogBinding.statusText.text = getString(R.string.dlna_cihaz_yok)
            }
            dialogBinding.stopButton.isVisible = castDevice != null

            dialogBinding.deviceList.post {
                val maxHeight = (resources.displayMetrics.heightPixels * 0.5f).toInt()
                if (dialogBinding.deviceList.height > maxHeight) {
                    dialogBinding.deviceList.layoutParams =
                        dialogBinding.deviceList.layoutParams.apply { height = maxHeight }
                }
            }
        }

        fun load(force: Boolean) {
            dialogBinding.progress.isVisible = true
            dialogBinding.statusText.isVisible = true
            dialogBinding.statusText.text = getString(R.string.dlna_cihaz_ara)

            lifecycleScope.launch {
                val devices = DlnaDiscovery.discover(this@PlayerActivity, force)
                    .filter { it.isRenderer }

                if (isFinishing) return@launch

                found.clear()
                found += devices
                dialogBinding.progress.isVisible = false
                render()
            }
        }

        dialogBinding.refreshButton.setOnClickListener { load(force = true) }
        dialogBinding.addDeviceButton.setOnClickListener {
            DlnaAddressDialog.show(
                activity = this,
                requireRenderer = true,
                requireServer = false
            ) { device ->
                manual.removeAll { it.udn == device.udn }
                manual += device
                render()
            }
        }
        dialogBinding.stopButton.setOnClickListener { stopCasting(dialogBinding) }

        dialog.setOnShowListener { load(force = false) }
        dialog.show()
    }

    private fun sendToDevice(
        channel: Channel,
        device: DlnaDevice,
        dialog: AlertDialog,
        dialogBinding: DialogDlnaDevicesBinding
    ) {
        dialogBinding.progress.isVisible = true
        dialogBinding.statusText.isVisible = true
        dialogBinding.statusText.text = getString(R.string.dlna_gonderiliyor)
        DlnaClient.clearLastError()

        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                DlnaClient.setAvTransportUri(device, channel.url, channel.name) &&
                    DlnaClient.play(device)
            }

            if (isFinishing) return@launch

            dialogBinding.progress.isVisible = false

            if (ok) {
                // Aynı yayın hem cihazda hem uygulamada seslendirilmesin.
                player?.pause()
                castDevice = device
                dialog.dismiss()
                Toast.makeText(
                    this@PlayerActivity,
                    getString(R.string.dlna_gonderildi, channel.name, device.name),
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                val detail = DlnaClient.lastError
                val message = if (detail.isNullOrBlank()) {
                    getString(R.string.dlna_gonderilemedi)
                } else {
                    "${getString(R.string.dlna_gonderilemedi)} $detail"
                }
                dialogBinding.statusText.text = message
                Toast.makeText(this@PlayerActivity, message, Toast.LENGTH_LONG).show()
            }
        }
    }

    // ---------------------------------------------------- Uygulama içi yansıtma

    /** Aynı Wi-Fi'deki diğer TV Listesi uygulamalarına geçerli kanalı gönderir. */
    private fun showCastSendDialog() {
        val channel = currentChannel()
        if (channel == null) {
            Toast.makeText(this, R.string.kanal_bulunamadi, Toast.LENGTH_SHORT).show()
            return
        }

        val dialogBinding = DialogDlnaDevicesBinding.inflate(layoutInflater)
        dialogBinding.addDeviceButton.text = getString(R.string.yansitma_ip_ile_ekle)
        dialogBinding.refreshButton.text = getString(R.string.yenile)
        dialogBinding.stopButton.isVisible = false

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.yansitma_gonder)
            .setView(dialogBinding.root)
            .setNegativeButton(R.string.kapat, null)
            .create()

        val found = mutableListOf<CastDevice>()
        val manual = mutableListOf<CastDevice>()
        val adapter = DlnaDeviceAdapter { device ->
            val cast = (found + manual).firstOrNull { it.ip == device.udn }
            if (cast != null) promptCastPin(cast, channel, dialog, dialogBinding)
        }

        dialogBinding.deviceList.layoutManager = LinearLayoutManager(this)
        dialogBinding.deviceList.adapter = adapter

        fun asDlna(device: CastDevice) = DlnaDevice(
            name = device.name,
            udn = device.ip,
            location = "http://${device.ip}:8977",
            avTransportUrl = "cast"
        )

        fun render() {
            val devices = (found + manual).distinctBy { it.ip }
            adapter.submitList(devices.map(::asDlna))
            dialogBinding.statusText.isVisible = devices.isEmpty()
            if (devices.isEmpty()) {
                dialogBinding.statusText.text = getString(R.string.yansitma_cihaz_yok)
            }
        }

        fun load() {
            dialogBinding.progress.isVisible = true
            dialogBinding.statusText.isVisible = true
            dialogBinding.statusText.text = getString(R.string.yansitma_cihaz_ara)

            lifecycleScope.launch {
                val devices = CastSender.discover()
                if (isFinishing) return@launch
                found.clear()
                found += devices
                dialogBinding.progress.isVisible = false
                render()
            }
        }

        dialogBinding.refreshButton.setOnClickListener { load() }
        dialogBinding.addDeviceButton.setOnClickListener {
            val input = EditText(this).apply {
                inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                    android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                filters = arrayOf(InputFilter.LengthFilter(15))
                hint = "192.168.1.82"
            }
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.yansitma_ip_baslik)
                .setView(input)
                .setNegativeButton(R.string.iptal, null)
                .setPositiveButton(R.string.kaydet) { _, _ ->
                    val ip = input.text.toString().trim()
                    val octets = ip.split('.')
                    if (octets.size != 4 || octets.any { it.toIntOrNull() !in 0..255 }) {
                        Toast.makeText(this, R.string.yansitma_gecerli_ip, Toast.LENGTH_SHORT).show()
                    } else {
                        manual.removeAll { it.ip == ip }
                        manual += CastDevice(ip = ip, name = "TvListesi", model = "")
                        render()
                    }
                }
                .show()
        }

        dialog.setOnShowListener { load() }
        dialog.show()
    }

    private fun promptCastPin(
        device: CastDevice,
        channel: Channel,
        dialog: AlertDialog,
        dialogBinding: DialogDlnaDevicesBinding
    ) {
        val saved = CastPrefs.pinFor(this, device.ip)
        if (saved.length == 6) {
            sendToCastDevice(device, channel, saved, dialog, dialogBinding)
            return
        }

        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(InputFilter.LengthFilter(6))
            hint = "000000"
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.yansitma_pin_baslik)
            .setMessage(R.string.yansitma_pin_mesaj)
            .setView(input)
            .setNegativeButton(R.string.iptal, null)
            .setPositiveButton(R.string.yansitma_gonder) { _, _ ->
                val pin = input.text.toString().trim()
                if (pin.length != 6) {
                    Toast.makeText(this, R.string.yansitma_pin_hatali, Toast.LENGTH_SHORT).show()
                } else {
                    CastPrefs.savePin(this, device.ip, pin)
                    sendToCastDevice(device, channel, pin, dialog, dialogBinding)
                }
            }
            .show()
    }

    private fun sendToCastDevice(
        device: CastDevice,
        channel: Channel,
        pin: String,
        dialog: AlertDialog,
        dialogBinding: DialogDlnaDevicesBinding
    ) {
        dialogBinding.progress.isVisible = true
        dialogBinding.statusText.isVisible = true
        dialogBinding.statusText.text = getString(R.string.yansitma_gonderiliyor)

        lifecycleScope.launch {
            val error = withContext(Dispatchers.IO) {
                CastSender.send(device, pin, channel)
            }
            if (isFinishing) return@launch

            dialogBinding.progress.isVisible = false
            if (error.isBlank()) {
                // TV oynatırken telefondaki sesi kapat.
                player?.pause()
                castTarget = device
                CastPrefs.saveLastDevice(this@PlayerActivity, device)
                dialog.dismiss()
                Toast.makeText(
                    this@PlayerActivity,
                    getString(R.string.yansitma_gonderildi, channel.name, device.name),
                    Toast.LENGTH_SHORT
                ).show()
                showCastRemoteDialog()
            } else {
                if (error.contains("PIN", ignoreCase = true)) {
                    CastPrefs.clearPin(this@PlayerActivity, device.ip)
                }
                val message = "${getString(R.string.yansitma_gonderilemedi)} $error"
                dialogBinding.statusText.text = message
                Toast.makeText(this@PlayerActivity, message, Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Yansıtma sonrası telefondan TV oynatıcısını kontrol eder. */
    private fun showCastRemoteDialog() {
        val target = castTarget ?: return
        val binding = DialogCastRemoteBinding.inflate(layoutInflater)
        binding.statusText.text = getString(R.string.yansitma_kumanda_aciklama, target.name)
        binding.changeButton.isVisible = !remoteOnly

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.yansitma_kumanda)
            .setView(binding.root)
            .setNegativeButton(R.string.kapat, null)
            .setOnDismissListener { if (remoteOnly) finish() }
            .create()

        binding.volumeDownButton.setOnClickListener {
            sendCastControl(binding, CastAction.VOLUME_DOWN)
        }
        binding.volumeUpButton.setOnClickListener {
            sendCastControl(binding, CastAction.VOLUME_UP)
        }
        binding.rewindButton.setOnClickListener {
            sendCastControl(binding, CastAction.SEEK_BACK)
        }
        binding.forwardButton.setOnClickListener {
            sendCastControl(binding, CastAction.SEEK_FORWARD)
        }
        binding.playPauseButton.setOnClickListener {
            sendCastControl(binding, CastAction.PLAY_PAUSE)
        }
        binding.changeButton.setOnClickListener {
            dialog.dismiss()
            castTarget = null
            showCastSendDialog()
        }
        binding.disconnectButton.setOnClickListener {
            sendCastControl(binding, CastAction.STOP) {
                castTarget = null
                dialog.dismiss()
            }
        }

        dialog.show()
    }

    private fun sendCastControl(
        binding: DialogCastRemoteBinding,
        action: String,
        onDone: (() -> Unit)? = null
    ) {
        val target = castTarget ?: return
        val pin = CastPrefs.pinFor(this, target.ip)
        if (pin.length != 6) {
            Toast.makeText(this, R.string.yansitma_pin_hatali, Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            val error = withContext(Dispatchers.IO) {
                CastSender.control(target, pin, action)
            }
            if (isFinishing) return@launch
            if (error.isBlank()) {
                onDone?.invoke()
            } else {
                Toast.makeText(
                    this@PlayerActivity,
                    getString(R.string.yansitma_kumanda_gonderilemedi, error),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun stopCasting(dialogBinding: DialogDlnaDevicesBinding) {
        val device = castDevice ?: return
        dialogBinding.stopButton.isEnabled = false

        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { DlnaClient.stop(device) }
            castDevice = null
            dialogBinding.stopButton.isEnabled = true
            dialogBinding.stopButton.isVisible = false
            Toast.makeText(
                this@PlayerActivity,
                if (ok) R.string.dlna_durduruldu else R.string.dlna_gonderilemedi,
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(EXTRA_INDEX, currentIndex)
        outState.putString(EXTRA_URL, externalUrl)
        outState.putString(EXTRA_TITLE, externalTitle)
        outState.putString(EXTRA_MIME, externalMime)
        outState.putBoolean(EXTRA_HIDE_LIST, hideList)
    }

    override fun onStart() {
        super.onStart()
        showControls(userInteraction = true)
        CastReceiver.onStopRequest = {
            player?.pause()
            finish()
        }
        CastReceiver.controlHandler = { action -> applyCastControl(action) }

        if (remoteOnly) {
            // Uygulama simgesindeki kısayol: oynatıcıyı açmadan kumandayı göster.
            val target = CastPrefs.lastDevice(this)
            if (target == null || CastPrefs.pinFor(this, target.ip).length != 6) {
                Toast.makeText(this, R.string.kisayol_kumanda_yok, Toast.LENGTH_LONG).show()
                finish()
                return
            }
            castTarget = target
            remoteDialogShown = false
            return
        }

        // Doğrudan link açılırken kanal listesi henüz yüklenmemiş olabilir;
        // bu durumda da oynatıcıyı kurmak gerekir.
        if (externalChannel() != null || PlaylistRepository.channels.isNotEmpty()) {
            initializePlayer()
        } else {
            lifecycleScope.launch {
                val cached = PlaylistRepository.loadCache(this@PlayerActivity)
                if (cached.isEmpty() && externalChannel() == null) {
                    showError(getString(R.string.kanal_bulunamadi))
                } else if (!isFinishing && player == null) {
                    initializePlayer()
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(hideControlsRunnable)
        handler.removeCallbacks(hideIndicatorRunnable)
        handler.removeCallbacks(updateTimeRunnable)
        binding.gestureIndicator.isVisible = false
        restoreBrightness()
        releasePlayer()
        CastReceiver.onStopRequest = null
        CastReceiver.controlHandler = null
    }

    /**
     * Kumandanın kanal tuşları (CH+ / CH-), sayfa tuşları ve medya sonraki/önceki
     * tuşlarıyla kanal değiştirilebilsin; sarma tuşları yayını zaman kaydırır.
     * D-pad sağ/sol da kanal paneli kapalıyken 15 saniyelik atlama yapar; düğme
     * odağı varsa gezinme bozulmaz.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            // D-pad sağ/sol: kanal paneli kapalı ve odak bir düğmede değilse 15 sn sarar.
            // Arayüzü göstermeden çalışır; böylece art arda basmak tekrar tekrar atlar.
            if ((event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
                    event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) &&
                !binding.channelPanel.isVisible &&
                !binding.topBar.hasFocus() && !binding.bottomBar.hasFocus()
            ) {
                seekBy(
                    if (event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                        SEEK_STEP_MS
                    } else {
                        -SEEK_STEP_MS
                    }
                )
                return true
            }
            // Geri tuşu arayüzü açıp paneli göstermesin; mevcut duruma göre çalışsın.
            if (event.keyCode != KeyEvent.KEYCODE_BACK) showControls()
            when (event.keyCode) {
                KeyEvent.KEYCODE_CHANNEL_UP,
                KeyEvent.KEYCODE_PAGE_UP,
                KeyEvent.KEYCODE_MEDIA_NEXT -> {
                    play(currentIndex + 1)
                    return true
                }

                KeyEvent.KEYCODE_CHANNEL_DOWN,
                KeyEvent.KEYCODE_PAGE_DOWN,
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                    play(currentIndex - 1)
                    return true
                }

                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                KeyEvent.KEYCODE_HEADSETHOOK -> {
                    togglePlayPause()
                    return true
                }

                KeyEvent.KEYCODE_MEDIA_PLAY -> {
                    player?.play()
                    return true
                }

                KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                    player?.pause()
                    return true
                }

                KeyEvent.KEYCODE_MEDIA_REWIND -> {
                    seekBy(-SEEK_STEP_MS)
                    return true
                }

                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                    seekBy(SEEK_STEP_MS)
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /** Dokunmatik cihazlarda ilk dokunuş arayüzü geri getirir. */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // Hata ekranı veya açık kanal paneli üzerinde kaydırma yapılmaz.
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            gestureEnabled = !binding.errorView.isVisible &&
                !(binding.channelPanel.isVisible && ev.x >= binding.channelPanel.left)
        }
        if (gestureEnabled) gestureDetector.onTouchEvent(ev)

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val wasHidden = !controlsVisible
                showControls(userInteraction = true)
                // Arayüz gizliyken ilk dokunuş yalnızca arayüzü göstersin.
                if (wasHidden) return true
            }

            MotionEvent.ACTION_MOVE -> if (!controlsVisible) {
                showControls(userInteraction = true)
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && remoteOnly && !remoteDialogShown) {
            // Kısayol diyaloğu ancak pencere odak kazandıktan sonra gösterilir;
            // aksi halde dokunuşlar arka plandaki uygulamaya gidebilir.
            remoteDialogShown = true
            binding.root.post { if (!isFinishing) showCastRemoteDialog() }
        }
        // Sistem çubukları gizlenirken oluşan odak olayları arayüzü geri
        // getirmesin; yalnızca gerçekten dönüşte (diyalog/başka uygulama) göster.
        if (hasFocus && SystemClock.uptimeMillis() - hiddenAt > 700L) {
            showControls(userInteraction = false)
        }
    }

    private fun initializePlayer() {
        if (player != null) return

        val exoPlayer = ExoPlayer.Builder(this).build().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true
            )
            addListener(playerListener)
        }

        binding.playerView.player = exoPlayer
        player = exoPlayer
        refreshChannelList()
        play(currentIndex)
    }

    private fun releasePlayer() {
        binding.playerView.player = null
        player?.release()
        player = null
        binding.buffering.isVisible = false
    }

    private fun play(targetIndex: Int) {
        val single = externalChannel()
        val list = if (single != null) listOf(single) else PlaylistRepository.channels
        if (list.isEmpty()) {
            showError(getString(R.string.kanal_bulunamadi))
            return
        }

        currentIndex = ((targetIndex % list.size) + list.size) % list.size
        val channel = list[currentIndex]

        channelAdapter.selectedIndex = currentIndex
        scrollToCurrent()

        binding.channelName.text = channel.name
        binding.channelGroup.text = channel.group ?: getString(R.string.canli_yayin)
        hideError()
        showControls()

        val exoPlayer = player ?: return

        val itemBuilder = MediaItem.Builder()
            .setUri(channel.url)
            .setMediaMetadata(
                MediaMetadata.Builder().setTitle(channel.name).build()
            )

        // Bilinen tüm medya türlerinde doğru kaynak seçilsin. Bilinmeyen
        // uzantılarda MIME zorlanmaz; ExoPlayer içeriği kendi sınar.
        val declaredMime = if (single != null) externalMime else null
        val mime = declaredMime ?: PlaylistParser.mediaMimeType(channel.url)
        mime?.takeIf { it.isNotBlank() && !it.contains('*') }
            ?.let { itemBuilder.setMimeType(it) }

        seeking = false
        exoPlayer.setMediaItem(itemBuilder.build())
        updateSeekControls()
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
    }

    private fun showError(message: String) {
        binding.errorText.text = message
        binding.errorView.isVisible = true
    }

    private fun hideError() {
        binding.errorView.isVisible = false
    }

    companion object {
        const val ACTION_OPEN_CAST_REMOTE = "com.example.tvlistesi.OPEN_CAST_REMOTE"

        private const val EXTRA_INDEX = "kanal_indeksi"
        private const val EXTRA_URL = "dlna_adresi"
        private const val EXTRA_TITLE = "dlna_basligi"
        private const val EXTRA_MIME = "dlna_turu"
        private const val EXTRA_HIDE_LIST = "liste_gizli"
        private const val CONTROLS_HIDE_DELAY = 3_000L
        private const val SEEK_STEP_MS = 15_000L
        private const val TIME_UPDATE_INTERVAL = 1_000L
        private const val ADJUST_NONE = 0
        private const val ADJUST_BRIGHTNESS = 1
        private const val ADJUST_VOLUME = 2

        fun start(context: Context, index: Int, hideList: Boolean = false) {
            context.startActivity(
                Intent(context, PlayerActivity::class.java)
                    .putExtra(EXTRA_INDEX, index)
                    .putExtra(EXTRA_HIDE_LIST, hideList)
            )
        }

        /** Tek bir yayın adresini (DLNA öğesi veya doğrudan HLS linki) oynatır. */
        fun startUrl(
            context: Context,
            url: String,
            title: String,
            mimeType: String?,
            hideList: Boolean = true
        ) {
            val inferred = mimeType ?: PlaylistParser.mediaMimeType(url)
            val intent = Intent(context, PlayerActivity::class.java)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_MIME, inferred)
                .putExtra(EXTRA_HIDE_LIST, hideList)
            // Yansıtma alıcısı uygulama bağlamından açar; yeni görev gerekir.
            if (context !is android.app.Activity) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }
}
