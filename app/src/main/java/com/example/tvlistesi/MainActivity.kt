package com.example.tvlistesi

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.app.UiModeManager
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Menu
import android.view.MenuItem
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.tvlistesi.cast.CastReceiver
import com.example.tvlistesi.data.ApkUpdater
import com.example.tvlistesi.data.Channel
import com.example.tvlistesi.data.ExternalPlayer
import com.example.tvlistesi.data.PlaylistParser
import com.example.tvlistesi.data.PlaylistRepository
import com.example.tvlistesi.data.SavedList
import com.example.tvlistesi.databinding.ActivityMainBinding
import com.example.tvlistesi.databinding.DialogCastReceiverBinding
import com.example.tvlistesi.databinding.DialogSaveListBinding
import com.example.tvlistesi.databinding.DialogSavedListsBinding
import com.example.tvlistesi.databinding.DialogSettingsBinding
import com.example.tvlistesi.databinding.DialogStreamUrlBinding
import com.example.tvlistesi.databinding.ItemSavedListBinding
import com.example.tvlistesi.ui.ChannelAdapter
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: ChannelAdapter

    private var allChannels: List<Channel> = emptyList()
    private var query: String = ""
    private var selectedGroup: String? = null
    private var refreshing = false
    private var updateCheckRequested = false

    /** Yazarken her harfte tüm listeyi filtrelememek için gecikmeli filtre. */
    private val filterRunnable = Runnable { applyFilter() }

    /** Seçilen liste ya da eklenen yayın geri döndüğünde arayüzü güncelle. */
    private val listLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            if (result.data?.getBooleanExtra(EXTRA_STREAM_ADDED, false) == true) {
                applyChannelsToUi()
                Toast.makeText(this, getString(R.string.liste_guncellendi, 1), Toast.LENGTH_SHORT).show()
            } else {
                refresh(showSuccessToast = true)
            }
        }
    }

    /** APK kurulum sonucu. */
    private val installLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        Toast.makeText(
            this,
            ApkUpdater.installResultMessage(this, result.resultCode),
            Toast.LENGTH_LONG
        ).show()
    }

    companion object {
        const val EXTRA_STREAM_ADDED = "streamAdded"
    }

    /** Uygulama simgesindeki kumanda kısayolunu PlayerActivity'ye yönlendirir. */
    private fun handleCastShortcut(incoming: Intent): Boolean {
        if (incoming.action != PlayerActivity.ACTION_OPEN_CAST_REMOTE) return false
        startActivity(
            Intent(this, PlayerActivity::class.java).setAction(PlayerActivity.ACTION_OPEN_CAST_REMOTE)
        )
        finish()
        return true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        if (handleCastShortcut(intent)) return

        // Eski örnek sunucu adresini yerleşik listeye taşı.
        PlaylistRepository.migrateDemoServer(this)

        // Android TV kutusunda alıcı açılışta hazır olsun.
        if (isTvDevice()) CastReceiver.start(applicationContext)

        adapter = ChannelAdapter(
            onClick = { channel -> openPlayer(channel) },
            onLongClick = { channel -> copyStreamUrl(channel) }
        )
        binding.channelList.layoutManager = LinearLayoutManager(this)
        binding.channelList.adapter = adapter
        binding.channelList.setHasFixedSize(true)

        binding.searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString().orEmpty()
                binding.searchInput.removeCallbacks(filterRunnable)
                binding.searchInput.postDelayed(filterRunnable, 150)
            }
        })

        binding.retryButton.setOnClickListener { refresh(showSuccessToast = true) }
        binding.settingsButton.setOnClickListener { showSettingsDialog() }
        updateSubtitle()

        // Telegram gibi uygulamalardan .m3u8 bağlantısına dokunulduğunda
        // doğrudan oynatıcıyı aç; kanal listesini yüklemekle uğraşma.
        handleViewIntent(intent)

        lifecycleScope.launch {
            val cached = PlaylistRepository.loadCache(this@MainActivity)
            if (cached.isNotEmpty()) {
                allChannels = cached
                buildGroupFilter()
                applyFilter()
            }

            when {
                cached.isEmpty() -> refresh()
                PlaylistRepository.autoRefresh(this@MainActivity) &&
                    !PlaylistRepository.isFresh(
                        this@MainActivity,
                        PlaylistRepository.serverUrl(this@MainActivity)
                    ) -> refresh(force = false)

                else -> showEmptyStateIfNeeded()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Uygulama açıldığında/ön plana döndüğünde güncellemeyi bir kez kontrol et.
        if (!updateCheckRequested) {
            updateCheckRequested = true
            checkForUpdate()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Uygulama zaten açıksa VIEW intent'i veya kumanda kısayolu burada gelir.
        if (!handleCastShortcut(intent)) handleViewIntent(intent)
    }

    /** Telegram/Tarayıcı'dan gelen http(s) yayın bağlantısını oynatıcıya devreder. */
    private fun handleViewIntent(intent: Intent): Boolean {
        if (intent.action != Intent.ACTION_VIEW) return false
        val data = intent.data ?: return false
        val url = data.toString()
        if (url.substringBefore('?').endsWith(".m3u", ignoreCase = true)) {
            // M3U liste bağlantısı (ör. film listeleri): tek kanal yerine
            // tüm listeyi etkin liste olarak yükle.
            binding.progress.isVisible = true
            lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) {
                    PlaylistRepository.setServerUrl(this@MainActivity, url)
                    PlaylistRepository.refresh(this@MainActivity, url)
                }
                binding.progress.isVisible = false
                result.onSuccess { count ->
                    applyChannelsToUi()
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.liste_guncellendi, count),
                        Toast.LENGTH_SHORT
                    ).show()
                }.onFailure { error ->
                    Toast.makeText(
                        this@MainActivity,
                        getString(
                            R.string.liste_yuklenemedi,
                            error.message ?: getString(R.string.bilinmeyen_hata)
                        ),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            return true
        }
        if (PlaylistParser.directChannel(url) == null) return false

        // Bağlantıyı olduğu gibi listeye ekle; sunucu adresini değiştirme.
        // Disk yazması ana iş parçacığını yavaşlatmasın diye IO'ya taşınır.
        val host = data.host
        lifecycleScope.launch {
            val added = withContext(Dispatchers.IO) {
                PlaylistRepository.addStream(this@MainActivity, url)
            }
            if (added != null) {
                applyChannelsToUi()
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.liste_guncellendi, 1),
                    Toast.LENGTH_SHORT
                ).show()
            }
            PlayerActivity.startUrl(
                this@MainActivity,
                url,
                added?.name ?: host ?: getString(R.string.canli_yayin),
                null
            )
        }
        return true
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_lists -> {
            showSavedListsDialog()
            true
        }

        R.id.action_refresh -> {
            refresh(showSuccessToast = true)
            true
        }

        R.id.action_settings -> {
            showSettingsDialog()
            true
        }

        R.id.action_open_stream -> {
            showOpenStreamDialog()
            true
        }

        R.id.action_check_links -> {
            listLauncher.launch(Intent(this, LinkCheckActivity::class.java))
            true
        }

        R.id.action_update -> {
            checkForUpdate(showResult = true)
            true
        }

        R.id.action_share_apk -> {
            ApkUpdater.shareInstalledApk(this)
            true
        }

        R.id.action_discover -> {
            listLauncher.launch(Intent(this, DiscoverActivity::class.java))
            true
        }

        R.id.action_dlna -> {
            startActivity(Intent(this, DlnaBrowserActivity::class.java))
            true
        }

        R.id.action_cast_receiver -> {
            showCastReceiverDialog()
            true
        }

        R.id.action_rotate -> {
            toggleOrientation()
            true
        }

        else -> super.onOptionsItemSelected(item)
    }

    private fun toggleOrientation() {
        requestedOrientation =
            if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            } else {
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
    }

    private fun isTvDevice(): Boolean {
        val uiMode = getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        return packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
            uiMode?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
    }

    /** TV kutusundaki uygulama içi yayın alıcısını açar/kapatır ve PIN'i gösterir. */
    private fun showCastReceiverDialog() {
        val binding = DialogCastReceiverBinding.inflate(layoutInflater)

        fun render() {
            binding.statusText.text = if (CastReceiver.isRunning) {
                getString(R.string.yansitma_alici_acik, CastReceiver.localIp, CastReceiver.deviceName)
            } else {
                getString(R.string.yansitma_alici_kapali)
            }
            binding.pinText.text = if (CastReceiver.isRunning) {
                getString(R.string.yansitma_pin, CastReceiver.pin)
            } else {
                getString(R.string.yansitma_pin, "------")
            }
            binding.toggleButton.text = getString(
                if (CastReceiver.isRunning) R.string.yansitma_alici_durdur
                else R.string.yansitma_alici_baslat
            )
        }

        binding.toggleButton.setOnClickListener {
            if (CastReceiver.isRunning) {
                CastReceiver.stop()
            } else {
                CastReceiver.start(applicationContext)
            }
            render()
        }
        render()

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.yansitma_alici)
            .setView(binding.root)
            .setNegativeButton(R.string.kapat, null)
            .show()
    }

    /** Doğrudan HLS/DASH/MP4 linkini kanal listesine gerek kalmadan açar. */
    private fun showOpenStreamDialog() {
        val dialogBinding = DialogStreamUrlBinding.inflate(layoutInflater)
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.yayin_ac)
            .setView(dialogBinding.root)
            .setNegativeButton(R.string.iptal, null)
            .setPositiveButton(R.string.oynat, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val url = dialogBinding.streamInput.text?.toString()?.trim().orEmpty()
                if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) {
                    dialogBinding.streamLayout.error = getString(R.string.gecersiz_adres)
                    return@setOnClickListener
                }
                lifecycleScope.launch {
                    val added = withContext(Dispatchers.IO) {
                        PlaylistRepository.addStream(this@MainActivity, url)
                    }
                    if (added != null) {
                        applyChannelsToUi()
                        Toast.makeText(
                            this@MainActivity,
                            getString(R.string.liste_guncellendi, 1),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    PlayerActivity.startUrl(
                        this@MainActivity,
                        url,
                        added?.name ?: getString(R.string.canli_yayin),
                        null
                    )
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
    }

    // ---------------------------------------------------------------- veri

    /** Bellekteki kanal listesini arayüze yansıtır (sunucudan yeniden indirmez). */
    private fun applyChannelsToUi() {
        allChannels = PlaylistRepository.channels
        buildGroupFilter()
        applyFilter()
        updateSubtitle()
    }

    private fun refresh(showSuccessToast: Boolean = false, force: Boolean = true) {
        if (refreshing) return
        refreshing = true
        binding.progress.isVisible = true

        lifecycleScope.launch {
            val result = PlaylistRepository.refresh(this@MainActivity, force = force)
            refreshing = false
            binding.progress.isVisible = false

            result.onSuccess { count ->
                allChannels = PlaylistRepository.channels
                buildGroupFilter()
                applyFilter()
                updateSubtitle()
                if (showSuccessToast) {
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.liste_guncellendi, count),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }.onFailure { error ->
                updateSubtitle()
                if (allChannels.isEmpty()) {
                    showEmptyState(
                        getString(
                            R.string.liste_yuklenemedi,
                            error.message ?: getString(R.string.bilinmeyen_hata)
                        ),
                        showRetry = true
                    )
                } else {
                    Toast.makeText(
                        this@MainActivity,
                        R.string.onbellek_kullaniliyor,
                        Toast.LENGTH_LONG
                    ).show()
                    applyFilter()
                }
            }
        }
    }

    private fun buildGroupFilter() {
        val groups = allChannels
            .mapNotNull { it.group?.takeIf { group -> group.isNotBlank() } }
            .distinct()
            .sorted()

        val items = ArrayList<String>(groups.size + 1)
        items += getString(R.string.tum_gruplar)
        items += groups

        binding.groupFilter.setAdapter(
            ArrayAdapter(this, R.layout.item_dropdown, items)
        )

        if (selectedGroup != null && !groups.contains(selectedGroup)) {
            selectedGroup = null
        }
        binding.groupFilter.setText(selectedGroup ?: items.first(), false)

        binding.groupFilter.setOnItemClickListener { _, _, position, _ ->
            selectedGroup = if (position == 0) null else items[position]
            applyFilter()
        }
    }

    private fun applyFilter() {
        val normalizedQuery = query.trim().lowercase(Locale.getDefault())

        val filtered = allChannels.filter { channel ->
            val groupMatches = selectedGroup == null || channel.group == selectedGroup
            val queryMatches = normalizedQuery.isEmpty() ||
                channel.name.lowercase(Locale.getDefault()).contains(normalizedQuery) ||
                channel.group?.lowercase(Locale.getDefault())?.contains(normalizedQuery) == true
            groupMatches && queryMatches
        }

        adapter.submitList(filtered)

        when {
            allChannels.isEmpty() -> showEmptyStateIfNeeded()
            filtered.isEmpty() -> showEmptyState(getString(R.string.sonuc_yok), showRetry = false)
            else -> hideEmptyState()
        }
    }

    private fun showEmptyStateIfNeeded() {
        if (allChannels.isEmpty() && !refreshing) {
            showEmptyState(getString(R.string.sunucu_adresi_kontrol), showRetry = true)
        }
    }

    private fun showEmptyState(message: String, showRetry: Boolean) {
        binding.emptyText.text = message
        binding.retryButton.isVisible = showRetry
        binding.settingsButton.isVisible = showRetry
        binding.emptyView.isVisible = true
    }

    private fun hideEmptyState() {
        binding.emptyView.isVisible = false
    }

    private fun updateSubtitle() {
        val timestamp = PlaylistRepository.lastUpdated(this)
        binding.toolbar.subtitle = if (timestamp > 0) {
            getString(
                R.string.son_guncelleme,
                SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(timestamp))
            )
        } else {
            getString(R.string.henuz_guncellenmedi)
        }
    }

    // ------------------------------------------------------------- eylemler

    private fun openPlayer(channel: Channel) {
        if (PlaylistRepository.externalPlayer(this)) {
            if (ExternalPlayer.open(this, channel)) return
            Toast.makeText(this, R.string.harici_oynatici_yok, Toast.LENGTH_SHORT).show()
        }

        // Kullanıcının seçtiği kanalı birebir oynat; filtreli listede ad/url
        // uyuşmazsa doğrudan adresi devreye gir.
        val index = PlaylistRepository.channels.indexOfFirst { it.url == channel.url }
        if (index >= 0) {
            PlayerActivity.start(this, index, hideList = true)
        } else {
            PlayerActivity.startUrl(this, channel.url, channel.name, null)
        }
    }

    private fun copyStreamUrl(channel: Channel) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(channel.name, channel.url))
        Toast.makeText(this, R.string.adres_kopyalandi, Toast.LENGTH_SHORT).show()
    }

    private fun showSettingsDialog() {
        val dialogBinding = DialogSettingsBinding.inflate(layoutInflater)
        dialogBinding.serverInput.setText(PlaylistRepository.serverUrl(this))
        dialogBinding.autoRefreshSwitch.isChecked = PlaylistRepository.autoRefresh(this)
        dialogBinding.externalPlayerSwitch.isChecked = PlaylistRepository.externalPlayer(this)
        dialogBinding.versionText.text = getString(R.string.surum, versionName())

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.sunucu_ayarlari)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.kaydet, null)
            .setNegativeButton(R.string.iptal, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val url = dialogBinding.serverInput.text?.toString()?.trim().orEmpty()

                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    dialogBinding.serverLayout.error = getString(R.string.gecersiz_adres)
                    return@setOnClickListener
                }

                dialogBinding.serverLayout.error = null
                PlaylistRepository.setServerUrl(this, url)
                PlaylistRepository.setAutoRefresh(this, dialogBinding.autoRefreshSwitch.isChecked)
                PlaylistRepository.setExternalPlayer(this, dialogBinding.externalPlayerSwitch.isChecked)
                dialog.dismiss()
                refresh(showSuccessToast = true)
            }
        }

        dialog.show()
    }

    // ------------------------------------------------------- kayıtlı listeler

    private fun showSavedListsDialog() {
        val dialogBinding = DialogSavedListsBinding.inflate(layoutInflater)
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(
                getString(
                    R.string.kayitli_listeler_sayisi,
                    PlaylistRepository.savedLists(this).size,
                    PlaylistRepository.MAX_SAVED_LISTS
                )
            )
            .setView(dialogBinding.root)
            .setNegativeButton(R.string.kapat, null)
            .create()

        fun render() {
            val lists = PlaylistRepository.savedLists(this)
                .filterNot { PlaylistRepository.isBuiltIn(it.url) }
            val activeUrl = PlaylistRepository.serverUrl(this)
            dialog.setTitle(
                getString(
                    R.string.kayitli_listeler_sayisi,
                    lists.size,
                    PlaylistRepository.MAX_SAVED_LISTS
                )
            )

            dialogBinding.listContainer.removeAllViews()
            // Yerleşik liste her zaman göründüğü için boş durum metni gerekmez.
            dialogBinding.emptyText.isVisible = false

            // Liste uzarken "kaydet" düğmesi ekranda kalsın: yüksekliği sınırla.
            dialogBinding.listScroll.layoutParams =
                dialogBinding.listScroll.layoutParams.apply {
                    height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                }
            dialogBinding.listScroll.post {
                val maxHeight = (resources.displayMetrics.heightPixels * 0.45f).toInt()
                if (dialogBinding.listScroll.height > maxHeight) {
                    dialogBinding.listScroll.layoutParams =
                        dialogBinding.listScroll.layoutParams.apply { height = maxHeight }
                }
            }

            fun addRow(saved: SavedList, deletable: Boolean) {
                val row = ItemSavedListBinding.inflate(
                    layoutInflater,
                    dialogBinding.listContainer,
                    false
                )
                row.savedName.text = saved.name
                row.savedUrl.text = if (PlaylistRepository.isBuiltIn(saved.url)) {
                    getString(R.string.yerlesik_liste_ipucu)
                } else {
                    saved.url
                }
                row.deleteSavedButton.isVisible = deletable

                // Etkin liste vurgulanır.
                if (saved.url == activeUrl) {
                    row.root.strokeColor =
                        ContextCompat.getColor(this@MainActivity, R.color.vurgu)
                    row.root.strokeWidth = (2 * resources.displayMetrics.density).toInt()
                }

                row.root.setOnClickListener {
                    PlaylistRepository.setServerUrl(this, saved.url)
                    dialog.dismiss()
                    refresh(showSuccessToast = true)
                }

                if (deletable) {
                    row.deleteSavedButton.setOnClickListener {
                        confirmDeleteSavedList(saved) { render() }
                    }
                }

                dialogBinding.listContainer.addView(row.root)
            }

            // Silinemeyen, APK içine gömülü liste her zaman en üstte.
            addRow(PlaylistRepository.builtInList(this), deletable = false)
            lists.forEach { addRow(it, deletable = true) }
        }

        dialogBinding.saveCurrentButton.setOnClickListener {
            showSaveListDialog { render() }
        }

        dialog.setOnShowListener { render() }
        dialog.show()
    }

    private fun confirmDeleteSavedList(saved: SavedList, onDeleted: () -> Unit) {
        MaterialAlertDialogBuilder(this)
            .setMessage(getString(R.string.liste_silinsin_mi, saved.name))
            .setPositiveButton(R.string.sil) { _, _ ->
                PlaylistRepository.deleteList(this, saved.url)
                Toast.makeText(this, R.string.liste_silindi, Toast.LENGTH_SHORT).show()
                onDeleted()
            }
            .setNegativeButton(R.string.iptal, null)
            .show()
    }

    private fun showSaveListDialog(onSaved: () -> Unit) {
        val currentUrl = PlaylistRepository.serverUrl(this)

        // Yerleşik liste menüde her zaman durduğu için tekrar kaydedilmez.
        if (PlaylistRepository.isBuiltIn(currentUrl)) {
            Toast.makeText(this, R.string.yerlesik_liste_kayitli, Toast.LENGTH_SHORT).show()
            return
        }

        val dialogBinding = DialogSaveListBinding.inflate(layoutInflater)
        dialogBinding.nameInput.setText(suggestedListName(currentUrl))
        dialogBinding.urlPreview.text = currentUrl

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.liste_kaydet)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.kaydet, null)
            .setNegativeButton(R.string.iptal, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = dialogBinding.nameInput.text?.toString()?.trim().orEmpty()

                if (name.isEmpty()) {
                    dialogBinding.nameLayout.error = getString(R.string.liste_adi_gerekli)
                    return@setOnClickListener
                }

                dialogBinding.nameLayout.error = null

                if (PlaylistRepository.saveList(this, name, currentUrl)) {
                    dialog.dismiss()
                    Toast.makeText(this, R.string.liste_kaydedildi, Toast.LENGTH_SHORT).show()
                    onSaved()
                } else {
                    dialogBinding.nameLayout.error = getString(
                        R.string.kayit_limiti,
                        PlaylistRepository.MAX_SAVED_LISTS
                    )
                }
            }
        }

        dialog.show()
    }

    /** Adresten okunabilir bir liste adı üretir: .../channels.json -> channels */
    private fun suggestedListName(url: String): String {
        val uri = runCatching { Uri.parse(url) }.getOrNull()
        val lastSegment = uri?.lastPathSegment.orEmpty()
        val baseName = lastSegment.substringBeforeLast('.').ifBlank { lastSegment }
        if (baseName.isNotBlank()) return baseName
        return uri?.host ?: getString(R.string.mevcut_liste)
    }

    /** GitHub'daki son sürümü kontrol eder; aynı sürümse APK'yı indirmez. */
    private fun checkForUpdate(showResult: Boolean = false) {
        // Açılışta her seferinde GitHub'a gitmek gerekmez; elle kontrol her zaman çalışır.
        if (!showResult && ApkUpdater.recentlyChecked(this)) return
        if (!showResult) ApkUpdater.markChecked(this)

        lifecycleScope.launch {
            var progress: androidx.appcompat.app.AlertDialog? = null
            if (showResult && !isFinishing && !isDestroyed) {
                progress = MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle(R.string.guncelleme_kontrol)
                    .setMessage(R.string.apk_indiriliyor)
                    .setCancelable(false)
                    .show()
            }
            try {
                val update = ApkUpdater.fixedUpdate().getOrThrow()
                val current = withContext(Dispatchers.IO) {
                    ApkUpdater.currentMeta(this@MainActivity).getOrThrow()
                }
                val releaseKey = ApkUpdater.releaseSeenKey(update.version)
                val newerRelease = ApkUpdater.isReleaseNewer(update.version, current)

                // Etiket kurulu sürümle aynıysa veya bildirim daha önce gösterildiyse
                // 8 MB'lık APK'yı indirmeden çık.
                if (!newerRelease || (
                        !showResult && releaseKey != null &&
                            releaseKey == ApkUpdater.lastSeen(this@MainActivity)
                    )
                ) {
                    progress?.dismiss()
                    progress = null
                    if (showResult && !newerRelease) {
                        showUpdateDialog(getString(R.string.guncelleme_guncel))
                    }
                    return@launch
                }

                val prepared = withContext(Dispatchers.IO) {
                    ApkUpdater.prepare(this@MainActivity, update, current).getOrThrow()
                }
                progress?.dismiss()
                progress = null

                if (prepared == null) {
                    if (showResult) showUpdateDialog(getString(R.string.guncelleme_guncel))
                } else {
                    val seen = releaseKey ?: ApkUpdater.seenKey(prepared)
                    ApkUpdater.prompt(
                        this@MainActivity,
                        prepared.update.copy(id = seen),
                        onUpdate = {
                            ApkUpdater.installPrepared(
                                this@MainActivity,
                                prepared,
                                installLauncher
                            )
                        },
                        onShare = {
                            ApkUpdater.shareApk(
                                this@MainActivity,
                                prepared.file,
                                prepared.update.fileName
                            )
                        }
                    )
                }
            } catch (error: Throwable) {
                if (showResult) {
                    showUpdateDialog(error.message ?: getString(R.string.bilinmeyen_hata))
                }
            } finally {
                runCatching { progress?.dismiss() }
            }
        }
    }

    private fun showUpdateDialog(message: String) {
        if (isFinishing || isDestroyed) return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.guncelleme_kontrol)
            .setMessage(message)
            .setPositiveButton(R.string.kapat, null)
            .show()
    }

    /** Ayarlar iletişim kutusunda gösterilecek uygulama sürümü. */
    private fun versionName(): String = runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName
    }.getOrNull().orEmpty().ifBlank { "?" }
}
