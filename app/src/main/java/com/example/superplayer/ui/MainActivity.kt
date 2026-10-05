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
import com.example.superplayer.data.BackupManager
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

    // Qué lista hay cargada ("slot_1".."slot_5", "file" o "sample"): el
    // filtro de categorías se guarda por lista (ver AppPrefs.getHiddenCategories).
    private var currentListKey: String = "sample"

    // Última lista mostrada en streamAdapter (resultados de la búsqueda
    // actual); openPlayer() se la pasa a PlayerActivity para que el gesto de
    // "canal siguiente/anterior" recorra esos mismos resultados.
    private var currentSearchResults: List<Stream> = emptyList()

    // Buscador global (ver README, "Buscador global"): índice con los canales
    // de la lista cargada MÁS los de las copias guardadas de los demás
    // huecos (TV/Lista 2/Cine/TDT/Radio), con el nombre ya normalizado (sin
    // tildes ni mayúsculas). Se reconstruye en segundo plano al abrir la
    // búsqueda si desde la última vez cambió alguna lista.
    private class SearchEntry(val stream: Stream, val normName: String)
    @Volatile private var searchIndex: List<SearchEntry>? = null
    private var searchIndexDirty = true
    private var searchIndexBuilding = false
    private var lastQuery: String = ""
    @Volatile private var autoRefreshing = false

    private val openDocumentLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) loadFromUri(uri)
        }

    // Copia de seguridad (ver BackupManager): el sistema pide dónde guardar el
    // archivo / cuál abrir, así que no hacen falta permisos de almacenamiento.
    private val exportBackupLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
            if (uri != null) exportBackupTo(uri)
        }
    private val importBackupLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) importBackupFrom(uri)
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
        applySystemBarInsets(top = binding.headerLogo, bottom = binding.recyclerView)

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
        updateSlotButtonLabels()
        autoRefreshLists()
    }

    private fun slotDefs() = listOf(
        Triple(1, binding.listButton1, R.string.list_slot_1),
        Triple(2, binding.listButton2, R.string.list_slot_2),
        Triple(3, binding.listButton3, R.string.list_slot_3),
        Triple(4, binding.listButton4, R.string.list_slot_4),
        Triple(5, binding.listButton5, R.string.list_slot_5)
    )

    /** Texto de cada botón de lista: su nombre y, debajo y más pequeño, cuándo se actualizó por última vez (si ya hay copia guardada). */
    private fun updateSlotButtonLabels() {
        for ((slot, button, labelRes) in slotDefs()) {
            val label = getString(labelRes)
            val saved = PlaylistCache.lastSavedAt(this, slot)
            if (saved == null) {
                button.text = label
            } else {
                val text = android.text.SpannableString("$label\n${formatAge(saved)}")
                text.setSpan(
                    android.text.style.RelativeSizeSpan(0.75f),
                    label.length + 1, text.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                button.text = text
            }
        }
    }

    private fun formatAge(savedAtMillis: Long): String {
        val minutes = ((System.currentTimeMillis() - savedAtMillis) / 60_000L).coerceAtLeast(0)
        return when {
            minutes < 1 -> getString(R.string.list_updated_now)
            minutes < 60 -> getString(R.string.list_updated_minutes, minutes.toInt())
            minutes < 24 * 60 -> getString(R.string.list_updated_hours, (minutes / 60).toInt())
            else -> getString(R.string.list_updated_days, (minutes / (24 * 60)).toInt())
        }
    }

    /**
     * Al abrir la app, vuelve a descargar en segundo plano las listas de los
     * huecos cuya copia guardada tenga más de AUTO_REFRESH_MIN_AGE_MS (o no
     * tenga copia), sin mostrar nada ni cambiar la lista que se esté viendo:
     * solo deja la copia al día (para el buscador global, para abrir sin red
     * y para el "actualizada hace..." de los botones). Una descarga o
     * análisis que falle se ignora y se deja la copia anterior tal cual.
     */
    private fun autoRefreshLists() {
        if (autoRefreshing) return
        autoRefreshing = true
        Thread {
            var changed = false
            for (slot in 1..5) {
                val url = AppPrefs.getListUrl(this, slot)
                if (url.isNullOrBlank()) continue
                val last = PlaylistCache.lastSavedAt(this, slot)
                if (last != null && System.currentTimeMillis() - last < AUTO_REFRESH_MIN_AGE_MS) continue
                try {
                    val raw = PlaylistRepository.downloadRaw(url)
                    PlaylistRepository.parse(raw) // solo se guarda si se entiende (igual que al cargar a mano)
                    PlaylistCache.save(this, slot, raw)
                    changed = true
                } catch (e: Exception) {
                }
            }
            runOnUiThread {
                autoRefreshing = false
                if (isFinishing || isDestroyed || !changed) return@runOnUiThread
                searchIndexDirty = true
                updateSlotButtonLabels()
            }
        }.start()
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
                    setPlaylist(finalData, "slot_$slot")
                    updateSlotButtonLabels()
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
        updateSlotButtonLabels()
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
            setPlaylist(PlaylistRepository.loadFromAssets(this, "sample_playlist.json"), "sample")
        } catch (e: Exception) {
            Toast.makeText(
                this,
                "No se pudo cargar la lista de ejemplo (${e.message}). Carga tu propio JSON.",
                Toast.LENGTH_LONG
            ).show()
            setPlaylist(PlaylistData(emptyList()), "sample")
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
        setPlaylist(data, "file")
        if (announce) {
            val totalStreams = data.categories.sumOf { it.streams.size }
            Toast.makeText(
                this,
                getString(R.string.load_success, data.categories.size, totalStreams),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun setPlaylist(data: PlaylistData, listKey: String) {
        playlist = data
        currentListKey = listKey
        searchIndexDirty = true
        binding.recyclerView.adapter = categoryAdapter
        categoryAdapter.submit(buildCategoryListWithFavorites())
        updateCategoryFilterButton()
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

    /**
     * Categorías de la lista cargada menos las ocultas por el filtro, en el
     * orden elegido con "Ordenar" (las que no estén en ese orden, p. ej.
     * nuevas en la lista remota, van al final, en su orden original).
     */
    private fun visibleCategories(): List<Category> {
        val hidden = AppPrefs.getHiddenCategories(this, currentListKey)
        val shown = if (hidden.isEmpty()) playlist.categories else playlist.categories.filter { it.name !in hidden }
        val order = AppPrefs.getCategoryOrder(this, currentListKey)
        if (order.isEmpty()) return shown
        val position = HashMap<String, Int>()
        order.forEachIndexed { i, name -> position.putIfAbsent(name, i) }
        // sortedBy es estable: las que no tienen posición conservan su orden relativo.
        return shown.sortedBy { position[it.name] ?: Int.MAX_VALUE }
    }

    /** Botón de filtro: oculto si la lista tiene una sola categoría o ninguna; si hay filtro activo, dice cuántas se ven. */
    private fun updateCategoryFilterButton() {
        val total = playlist.categories.size
        val button = binding.categoryFilterButton
        val orderButton = binding.categoryOrderButton
        if (total < 2) {
            button.visibility = View.GONE
            orderButton.visibility = View.GONE
            return
        }
        button.visibility = View.VISIBLE
        orderButton.visibility = View.VISIBLE
        orderButton.setOnClickListener { showCategoryOrderDialog() }
        val shown = visibleCategories().size
        button.text = if (shown == total) getString(R.string.category_filter_button)
        else getString(R.string.category_filter_button_active, shown, total)
        button.setOnClickListener { showCategoryFilterDialog() }
    }

    /**
     * Casillas con todas las categorías de la lista cargada (como "Spain /
     * Populares / Deportivas..." de las apps de listas): marcadas = se ven.
     * "Invertir" cambia todas a la vez; "Aceptar" guarda el filtro de ESTA
     * lista (cada hueco tiene el suyo). Favoritos no depende del filtro: sus
     * canales salen siempre, estén en una categoría oculta o no.
     */
    private fun showCategoryFilterDialog() {
        val cats = playlist.categories
        val hidden = AppPrefs.getHiddenCategories(this, currentListKey)
        val checked = BooleanArray(cats.size) { cats[it].name !in hidden }
        val labels = Array(cats.size) { "${cats[it].name} (${cats[it].streams.size})" }
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.category_filter_title)
            .setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.category_filter_invert, null)
            .create()
        dialog.show()
        // Se asignan después de show() para que "Invertir" y un "Aceptar"
        // sin ninguna categoría marcada no cierren el diálogo solos.
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            for (i in checked.indices) {
                checked[i] = !checked[i]
                dialog.listView.setItemChecked(i, checked[i])
            }
        }
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (checked.none { it }) {
                Toast.makeText(this, R.string.category_filter_none_selected, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val newHidden = cats.filterIndexed { i, _ -> !checked[i] }.map { it.name }.toSet()
            AppPrefs.setHiddenCategories(this, currentListKey, newHidden)
            categoryAdapter.submit(buildCategoryListWithFavorites())
            updateCategoryFilterButton()
            dialog.dismiss()
        }
    }

    /**
     * Ventana para ordenar las categorías de la lista cargada: arrastrando
     * una fila (pulsación larga) o con las flechas ▲ ▼ de cada fila (para
     * el mando de la TV). El orden se guarda por lista; "Restablecer" vuelve
     * al orden original de la lista.
     */
    private fun showCategoryOrderDialog() {
        val hidden = AppPrefs.getHiddenCategories(this, currentListKey)
        // Punto de partida: el orden ya guardado (si hay) aplicado a TODAS las categorías.
        val saved = AppPrefs.getCategoryOrder(this, currentListKey)
        val position = HashMap<String, Int>()
        saved.forEachIndexed { i, name -> position.putIfAbsent(name, i) }
        val names = playlist.categories.map { it.name }
            .sortedBy { position[it] ?: Int.MAX_VALUE }
            .toMutableList()
        val orderAdapter = CategoryOrderAdapter(names, hidden)

        val recycler = androidx.recyclerview.widget.RecyclerView(this).apply {
            layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this@MainActivity)
            adapter = orderAdapter
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                (resources.displayMetrics.heightPixels * 0.55f).toInt()
            )
        }
        androidx.recyclerview.widget.ItemTouchHelper(object : androidx.recyclerview.widget.ItemTouchHelper.SimpleCallback(
            androidx.recyclerview.widget.ItemTouchHelper.UP or androidx.recyclerview.widget.ItemTouchHelper.DOWN, 0
        ) {
            override fun onMove(
                rv: androidx.recyclerview.widget.RecyclerView,
                from: androidx.recyclerview.widget.RecyclerView.ViewHolder,
                to: androidx.recyclerview.widget.RecyclerView.ViewHolder
            ): Boolean {
                orderAdapter.move(from.bindingAdapterPosition, to.bindingAdapterPosition)
                return true
            }

            override fun onSwiped(viewHolder: androidx.recyclerview.widget.RecyclerView.ViewHolder, direction: Int) {}
        }).attachToRecyclerView(recycler)

        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val hint = android.widget.TextView(this@MainActivity).apply {
                setText(R.string.category_order_hint)
                textSize = 12f
                alpha = 0.7f
                val pad = (16 * resources.displayMetrics.density).toInt()
                setPadding(pad, 0, pad, (8 * resources.displayMetrics.density).toInt())
            }
            addView(hint)
            addView(recycler)
        }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.category_order_title)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                AppPrefs.setCategoryOrder(this, currentListKey, orderAdapter.names.toList())
                categoryAdapter.submit(buildCategoryListWithFavorites())
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.category_order_reset) { _, _ ->
                AppPrefs.setCategoryOrder(this, currentListKey, emptyList())
                categoryAdapter.submit(buildCategoryListWithFavorites())
            }
            .show()
    }

    private fun buildCategoryListWithFavorites(): List<Category> {
        val favIds = favoritesStore.getAll()
        val favStreams = playlist.categories.flatMap { it.streams }.filter { favIds.contains(it.id) }
        val result = mutableListOf<Category>()
        if (favStreams.isNotEmpty()) {
            result.add(Category(getString(R.string.favorites_category_name), favStreams))
        }
        result.addAll(visibleCategories())
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
            override fun onMenuItemActionExpand(item: MenuItem): Boolean {
                refreshSearchIndexIfNeeded()
                return true
            }
            override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
                lastQuery = ""
                binding.recyclerView.adapter = categoryAdapter
                return true
            }
        })
        updateThemeMenuItem(menu.findItem(R.id.action_theme_toggle))
        return true
    }

    private fun applySearch(query: String) {
        lastQuery = query
        if (query.isBlank()) {
            binding.recyclerView.adapter = categoryAdapter
            return
        }
        // Todas las palabras tienen que aparecer (en cualquier orden), sin
        // distinguir mayúsculas ni tildes: "futbol 1" encuentra "Fútbol 1 HD".
        val words = normalizeForSearch(query).split(' ').filter { it.isNotEmpty() }
        val index = searchIndex
        val matches = if (index != null) {
            index.filter { e -> words.all { e.normName.contains(it) } }.map { it.stream }
        } else {
            // Índice aún construyéndose: mientras tanto, solo la lista actual.
            playlist.categories.flatMap { it.streams }
                .filter { s -> normalizeForSearch(s.name).let { n -> words.all { n.contains(it) } } }
        }
        currentSearchResults = matches
        streamAdapter.submit(matches)
        binding.recyclerView.adapter = streamAdapter
    }

    private fun refreshSearchIndexIfNeeded() {
        if (!searchIndexDirty || searchIndexBuilding) return
        searchIndexBuilding = true
        searchIndexDirty = false
        val current = playlist
        Thread {
            val seen = HashSet<String>()
            val entries = ArrayList<SearchEntry>()
            fun add(streams: List<Stream>) {
                for (st in streams) {
                    if (seen.add(st.id)) entries.add(SearchEntry(st, normalizeForSearch(st.name)))
                }
            }
            // Primero la lista que se está viendo (manda si un canal está repetido)...
            add(current.categories.flatMap { it.streams })
            // ...y luego las copias guardadas de los 5 huecos. Una copia que
            // no se pueda leer se ignora: la búsqueda sigue con el resto.
            for (slot in 1..5) {
                try {
                    val raw = PlaylistCache.load(this, slot) ?: continue
                    add(PlaylistRepository.parse(raw).categories.flatMap { it.streams })
                } catch (e: Exception) {
                }
            }
            runOnUiThread {
                searchIndexBuilding = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                searchIndex = entries
                // Si mientras tanto se cargó otra lista, queda pendiente para la próxima.
                if (lastQuery.isNotBlank()) applySearch(lastQuery)
            }
        }.start()
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
            R.id.action_backup_export -> {
                exportBackupLauncher.launch("socram-tv-copia.json")
                true
            }
            R.id.action_backup_import -> {
                importBackupLauncher.launch(arrayOf("*/*"))
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
    private fun exportBackupTo(uri: Uri) {
        try {
            val text = BackupManager.export(this)
            contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                ?: throw IllegalStateException("no se pudo abrir el archivo")
            Toast.makeText(this, R.string.backup_export_ok, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.backup_export_error, e.message ?: ""), Toast.LENGTH_LONG).show()
        }
    }

    private fun importBackupFrom(uri: Uri) {
        try {
            val text = contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                ?: throw IllegalStateException("no se pudo abrir el archivo")
            val result = BackupManager.restore(this, text)
            // Refresca lo que se ve: categorías (filtros), favoritos y accesos directos.
            categoryAdapter.submit(buildCategoryListWithFavorites())
            updateCategoryFilterButton()
            refreshShortcuts()
            Toast.makeText(
                this,
                getString(R.string.backup_import_ok, result.newFavorites, result.listUrls, result.filters),
                Toast.LENGTH_LONG
            ).show()
            if (result.darkModeChanged) {
                AppCompatDelegate.setDefaultNightMode(
                    if (AppPrefs.isDarkMode(this)) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
                )
            }
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.backup_import_error, e.message ?: ""), Toast.LENGTH_LONG).show()
        }
    }

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

// Una lista cuya copia guardada tenga menos horas que esto no se vuelve a descargar sola al abrir la app (ver autoRefreshLists).
private const val AUTO_REFRESH_MIN_AGE_MS = 3L * 60 * 60 * 1000

private val DIACRITICS = Regex("\\p{M}+")

/** Minúsculas y sin tildes/diéresis (ñ -> n), para comparar nombres al buscar. */
private fun normalizeForSearch(text: String): String =
    java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFD)
        .replace(DIACRITICS, "")
        .lowercase(java.util.Locale.ROOT)
        .trim()
