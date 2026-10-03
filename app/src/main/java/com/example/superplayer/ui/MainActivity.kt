package com.example.superplayer.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.appcompat.widget.SearchView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.WindowCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.superplayer.R
import com.example.superplayer.data.AppPrefs
import com.example.superplayer.data.EpgRepository
import com.example.superplayer.data.FavoritesStore
import com.example.superplayer.data.PlaylistCache
import com.example.superplayer.data.PlaylistRepository
import com.example.superplayer.databinding.ActivityMainBinding
import com.example.superplayer.model.Category
import com.example.superplayer.model.PlaylistData
import com.example.superplayer.model.Stream
import com.example.superplayer.player.PlayerActivity
import com.example.superplayer.player.ShortcutsHelper

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var favoritesStore: FavoritesStore
    private lateinit var categoryAdapter: CategoryAdapter
    private lateinit var streamAdapter: StreamAdapter
    private var playlist: PlaylistData = PlaylistData(emptyList())

    // Última lista mostrada en streamAdapter (resultados de la búsqueda
    // actual); openPlayer() se la pasa a PlayerActivity para que el gesto de
    // "canal siguiente/anterior" recorra esos mismos resultados.
    private var currentSearchResults: List<Stream> = emptyList()

    private val openDocumentLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) loadFromUri(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setSupportActionBar(binding.toolbar)
        // Sin esto, AppCompat rellena el título vacío del Toolbar con la
        // etiqueta de la app (android:label, "Socram TV+ 🇳🇬 🇪🇸") por su
        // cuenta, y aparecía un "Socra…" superpuesto al logo (ver README,
        // sección "Logo de cabecera"): el logo YA lleva el nombre dibujado,
        // así que aquí no hace falta ningún título de texto.
        supportActionBar?.setDisplayShowTitleEnabled(false)
        applySystemBarInsets(top = binding.headerFrame, bottom = binding.recyclerView)

        favoritesStore = FavoritesStore(this)

        categoryAdapter = CategoryAdapter(emptyList()) { category -> openCategory(category) }
        streamAdapter = StreamAdapter(
            items = emptyList(),
            isFavorite = { favoritesStore.isFavorite(it.id) },
            onClick = { openPlayer(it) },
            onToggleFavorite = { toggleFavorite(it) }
        )

        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = categoryAdapter

        setupListSlotButtons()
        loadInitialPlaylist()
    }

    private fun setupListSlotButtons() {
        val slots = listOf(
            Triple(1, binding.listButton1, getString(R.string.list_slot_1)),
            Triple(2, binding.listButton2, getString(R.string.list_slot_2)),
            Triple(3, binding.listButton3, getString(R.string.list_slot_3)),
            Triple(4, binding.listButton4, getString(R.string.list_slot_4)),
            Triple(5, binding.listButton5, getString(R.string.list_slot_5))
        )
        for ((slot, button, label) in slots) {
            button.setOnClickListener {
                val savedUrl = AppPrefs.getListUrl(this, slot)
                if (savedUrl.isNullOrBlank()) {
                    promptForListUrl(slot, label)
                } else {
                    loadFromRemoteUrl(slot, savedUrl)
                }
            }
            button.setOnLongClickListener {
                promptForListUrl(slot, label)
                true
            }
        }

        // A diferencia de los de arriba, este no guarda ninguna URL: elige un
        // archivo JSON/M3U del propio dispositivo (antes había también un
        // icono de carpeta redundante en la barra superior para esto mismo;
        // se quitó por quedarse ya este botón de aquí abajo).
        binding.loadFileButton.setOnClickListener {
            openDocumentLauncher.launch(arrayOf("*/*"))
        }
    }

    private fun promptForListUrl(slot: Int, label: String) {
        val input = android.widget.EditText(this).apply {
            hint = getString(R.string.list_slot_dialog_hint)
            setText(AppPrefs.getListUrl(this@MainActivity, slot).orEmpty())
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.list_slot_dialog_title, label))
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val url = input.text.toString().trim()
                if (url.isNotBlank()) {
                    AppPrefs.saveListUrl(this, slot, url)
                    loadFromRemoteUrl(slot, url)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Siempre intenta descargar de la URL primero (para recoger cambios del
     * archivo remoto); solo si eso falla (sin red, servidor caído, etc.) cae
     * a la última copia guardada de ese mismo hueco (ver PlaylistCache), si
     * la hay. La copia se guarda únicamente cuando la descarga Y el análisis
     * posterior salen bien, nunca con una respuesta a medias.
     */
    private fun loadFromRemoteUrl(slot: Int, url: String) {
        Toast.makeText(this, R.string.loading_list, Toast.LENGTH_SHORT).show()
        Thread {
            var data: PlaylistData? = null
            var downloadError: Exception? = null
            var usedCache = false
            try {
                val raw = PlaylistRepository.downloadRaw(url)
                data = PlaylistRepository.parse(raw)
                PlaylistCache.save(this, slot, raw)
            } catch (e: Exception) {
                downloadError = e
                val cachedRaw = PlaylistCache.load(this, slot)
                if (cachedRaw != null) {
                    data = try {
                        usedCache = true
                        PlaylistRepository.parse(cachedRaw)
                    } catch (e2: Exception) {
                        // No debería pasar (solo se guarda una copia si antes analizó
                        // bien), pero por si acaso: se trata como si no hubiera copia.
                        usedCache = false
                        null
                    }
                }
            }
            val finalData = data
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (finalData != null) {
                    setPlaylist(finalData)
                    if (usedCache) {
                        val savedAt = formatCacheDate(PlaylistCache.lastSavedAt(this, slot))
                        Toast.makeText(this, getString(R.string.list_load_offline_cached, savedAt), Toast.LENGTH_LONG).show()
                    } else {
                        val totalStreams = finalData.categories.sumOf { it.streams.size }
                        Toast.makeText(
                            this,
                            getString(R.string.load_success, finalData.categories.size, totalStreams),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                } else {
                    Toast.makeText(
                        this,
                        getString(R.string.list_load_error, downloadError?.message ?: ""),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }.start()
    }

    private fun formatCacheDate(millis: Long?): String {
        if (millis == null) return ""
        return java.text.SimpleDateFormat("d MMM, HH:mm", java.util.Locale.getDefault()).format(java.util.Date(millis))
    }

    override fun onResume() {
        super.onResume()
        if (binding.recyclerView.adapter === streamAdapter) {
            streamAdapter.notifyDataSetChanged()
        }
    }

    private fun loadInitialPlaylist() {
        val lastUri = AppPrefs.getLastPlaylistUri(this)
        if (lastUri != null) {
            try {
                loadFromUri(Uri.parse(lastUri), announce = false)
                return
            } catch (e: Exception) {
                // El archivo guardado ya no está disponible (se movió, se revocó el permiso, etc.)
                // Seguimos abajo con la lista de ejemplo.
            }
        }
        try {
            setPlaylist(PlaylistRepository.loadFromAssets(this, "sample_playlist.json"))
        } catch (e: Exception) {
            Toast.makeText(
                this,
                "No se pudo cargar la lista de ejemplo (${e.message}). Carga tu propio JSON.",
                Toast.LENGTH_LONG
            ).show()
            setPlaylist(PlaylistData(emptyList()))
        }
    }

    private fun loadFromUri(uri: Uri, announce: Boolean = true) {
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: SecurityException) {
            // Algunos proveedores no soportan permisos persistentes; seguimos igual.
        }
        val data = try {
            PlaylistRepository.loadFromUri(this, uri)
        } catch (e: Exception) {
            if (!announce) throw e // deja que loadInitialPlaylist() lo capture y caiga a la lista de ejemplo
            Toast.makeText(
                this,
                "No se pudo leer ese archivo (${e.message}). Revisa que sea un JSON o M3U válido.",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        AppPrefs.saveLastPlaylistUri(this, uri.toString())
        setPlaylist(data)
        if (announce) {
            val totalStreams = data.categories.sumOf { it.streams.size }
            Toast.makeText(
                this,
                getString(R.string.load_success, data.categories.size, totalStreams),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun setPlaylist(data: PlaylistData) {
        playlist = data
        binding.recyclerView.adapter = categoryAdapter
        categoryAdapter.submit(buildCategoryListWithFavorites())
        binding.emptyView.visibility = if (data.categories.isEmpty()) View.VISIBLE else View.GONE
        // Sin bloquear nada: si esta lista trae guía EPG (o ya la teníamos
        // cargada de antes), se descarga/parsea sola en segundo plano.
        EpgRepository.load(data.epgUrl)
        // Por si algún favorito cambió de logo/url desde la última vez, o se
        // marcó antes de que existiera esto (ver FavoritesStore); barato,
        // así que no pasa nada por hacerlo en cada carga de lista.
        val allStreams = data.categories.flatMap { it.streams }
        favoritesStore.refreshStoredStreams(allStreams)
        refreshShortcuts()
    }

    /** Vuelve a publicar los accesos directos del icono de la app a partir de los favoritos actuales (ver ShortcutsHelper). */
    private fun refreshShortcuts() {
        ShortcutsHelper.refresh(this, favoritesStore.getAllStreams())
    }

    private fun buildCategoryListWithFavorites(): List<Category> {
        val favIds = favoritesStore.getAll()
        val favStreams = playlist.categories.flatMap { it.streams }.filter { favIds.contains(it.id) }
        val result = mutableListOf<Category>()
        if (favStreams.isNotEmpty()) {
            result.add(Category(getString(R.string.favorites_category_name), favStreams))
        }
        result.addAll(playlist.categories)
        return result
    }

    private fun openCategory(category: Category) {
        StreamListActivity.pendingStreams = category.streams
        val intent = Intent(this, StreamListActivity::class.java)
        intent.putExtra(StreamListActivity.EXTRA_CATEGORY_NAME, category.name)
        startActivity(intent)
    }

    private fun openPlayer(stream: Stream) {
        PlayerActivity.pendingStream = stream
        PlayerActivity.pendingChannelList = currentSearchResults
        startActivity(Intent(this, PlayerActivity::class.java))
    }

    private fun toggleFavorite(stream: Stream) {
        val nowFav = favoritesStore.toggle(stream)
        Toast.makeText(
            this,
            getString(if (nowFav) R.string.added_to_favorites else R.string.removed_from_favorites),
            Toast.LENGTH_SHORT
        ).show()
        categoryAdapter.submit(buildCategoryListWithFavorites())
        streamAdapter.notifyDataSetChanged()
        refreshShortcuts()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        val searchItem = menu.findItem(R.id.action_search)
        val searchView = searchItem.actionView as SearchView
        searchView.queryHint = getString(R.string.search_hint)
        styleSearchView(this, searchView)
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?) = true
            override fun onQueryTextChange(newText: String?): Boolean {
                applySearch(newText.orEmpty())
                return true
            }
        })
        searchItem.setOnActionExpandListener(object : MenuItem.OnActionExpandListener {
            override fun onMenuItemActionExpand(item: MenuItem) = true
            override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
                binding.recyclerView.adapter = categoryAdapter
                return true
            }
        })
        updateThemeMenuItem(menu.findItem(R.id.action_theme_toggle))
        return true
    }

    private fun applySearch(query: String) {
        if (query.isBlank()) {
            binding.recyclerView.adapter = categoryAdapter
            return
        }
        val matches = playlist.categories.flatMap { it.streams }
            .filter { it.name.contains(query, ignoreCase = true) }
        currentSearchResults = matches
        streamAdapter.submit(matches)
        binding.recyclerView.adapter = streamAdapter
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_epg_grid -> {
                openEpgGrid()
                true
            }
            R.id.action_theme_toggle -> {
                toggleTheme()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    /**
     * Icono y texto del botón de claro/oscuro: describen a qué modo se
     * cambiaría al tocarlo, no el modo actual, así que muestran lo
     * contrario de cómo está la app ahora mismo.
     */
    private fun updateThemeMenuItem(item: MenuItem) {
        val isDark = AppCompatDelegate.getDefaultNightMode() == AppCompatDelegate.MODE_NIGHT_YES
        item.setIcon(if (isDark) R.drawable.ic_sun else R.drawable.ic_moon)
        item.title = getString(if (isDark) R.string.action_switch_to_light else R.string.action_switch_to_dark)
    }

    /**
     * Alterna el modo claro/oscuro de toda la app (se recuerda para la
     * próxima vez, ver Storage.kt/AppPrefs.isDarkMode y
     * SuperPlayerApp.onCreate). AppCompat recrea esta pantalla sola en
     * cuanto cambia el modo por defecto, así que no hace falta llamar a
     * recreate() ni refrescar nada más aquí: al volver a crearse,
     * onCreateOptionsMenu ya calcula el icono/texto para el nuevo modo.
     */
    private fun toggleTheme() {
        val goingDark = AppCompatDelegate.getDefaultNightMode() != AppCompatDelegate.MODE_NIGHT_YES
        AppPrefs.setDarkMode(this, goingDark)
        AppCompatDelegate.setDefaultNightMode(
            if (goingDark) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        )
    }

    /**
     * Abre la vista de parrilla de guía EPG (ver EpgGridActivity) con todos
     * los canales de la lista actual que tengan tvg-id, sin importar la
     * categoría: es una vista global de la guía, no de una categoría suelta.
     * Si ninguno tiene tvg-id (o la guía todavía no ha terminado de
     * descargarse), esa pantalla se encarga de mostrar el aviso
     * correspondiente; no hace falta comprobarlo aquí antes.
     */
    private fun openEpgGrid() {
        EpgGridActivity.pendingChannels = playlist.categories.flatMap { it.streams }
            .filter { !it.tvgId.isNullOrBlank() }
        startActivity(Intent(this, EpgGridActivity::class.java))
    }
}
