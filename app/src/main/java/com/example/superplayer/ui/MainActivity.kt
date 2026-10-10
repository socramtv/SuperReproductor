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
import com.example.superplayer.data.ChannelOverrides
import com.example.superplayer.data.EpgRepository
import com.example.superplayer.data.ContinueWatching
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
    /** La lista tal cual se cargó; [playlist] es esta misma con los canales ocultos/renombrados aplicados (ver ChannelOverrides). */
    private var rawPlaylist: PlaylistData = PlaylistData(emptyList())
    private var appliedOverridesVersion = -1

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

    // Búsqueda por voz (ver startVoiceSearch): el reconocedor de voz del sistema devuelve el texto.
    private val voiceLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val spoken = result.data
                    ?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)
                    ?.firstOrNull()
                if (!spoken.isNullOrBlank()) handleVoiceQuery(spoken)
            }
        }
    private var searchMenuItem: MenuItem? = null
    private var searchViewRef: SearchView? = null
    private val indexReadyCallbacks = ArrayList<() -> Unit>()

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
            onToggleFavorite = { toggleFavorite(it) },
            onLongClick = { stream ->
                showChannelOptionsDialog(
                    this, stream, favoritesStore.isFavorite(stream.id),
                    onToggleFavorite = { toggleFavorite(stream) },
                    onChanged = { refreshAfterChannelChange() }
                )
            }
        )

        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = categoryAdapter

        setupListSlotButtons()
        loadInitialPlaylist()
        handleAssistantIntent(intent)
        handleMatchIntent(intent)
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

    /** Texto de cada botón de lista: solo su nombre (la fecha de actualización se ve en la fila de estado de abajo). */
    private fun updateSlotButtonLabels() {
        for ((_, button, labelRes) in slotDefs()) {
            button.text = getString(labelRes)
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
     * Al abrir la app, vuelve a descargar en segundo plano (como mucho una
     * vez al día) las listas de los huecos cuya copia guardada tenga más de
     * AUTO_REFRESH_MIN_AGE_MS (o no tenga copia), sin mostrar nada ni cambiar la lista que se esté viendo:
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
                    loadFromRemoteUrl(slot, savedUrl, preferCache = true)
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
    private fun loadFromRemoteUrl(slot: Int, url: String, preferCache: Boolean = false) {
        // preferCache = true (pulsar el botón del hueco): si ya hay copia
        // guardada se abre al instante sin tocar la red; la lista solo se
        // vuelve a descargar con el botón "Actualizar" (ver refreshCurrentList)
        // o con la puesta al día diaria (ver autoRefreshLists). Sin copia, o si
        // la copia no se pudiera leer, se descarga igualmente.
        if (!(preferCache && PlaylistCache.lastSavedAt(this, slot) != null)) {
            Toast.makeText(this, R.string.loading_list, Toast.LENGTH_SHORT).show()
        }
        Thread {
            var data: PlaylistData? = null
            var downloadError: Exception? = null
            var usedCache = false
            if (preferCache) {
                val cachedRaw = PlaylistCache.load(this, slot)
                if (cachedRaw != null) {
                    try {
                        data = PlaylistRepository.parse(cachedRaw)
                    } catch (e: Exception) {
                        data = null
                    }
                }
            }
            if (data == null) {
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

    /** Botón "Actualizar": vuelve a descargar la lista remota que se está viendo (con la copia guardada como respaldo si no hay red). */
    private fun refreshCurrentList() {
        val slot = currentListKey.removePrefix("slot_").toIntOrNull() ?: return
        val url = AppPrefs.getListUrl(this, slot)
        if (url.isNullOrBlank()) return
        loadFromRemoteUrl(slot, url)
    }

    private fun formatCacheDate(millis: Long?): String {
        if (millis == null) return ""
        return java.text.SimpleDateFormat("d MMM, HH:mm", java.util.Locale.getDefault()).format(java.util.Date(millis))
    }

    override fun onResume() {
        super.onResume()
        updateSlotButtonLabels()
        // Canales ocultados/renombrados desde una lista: se reaplican sobre la lista cargada.
        reapplyChannelOverrides()
        updateCategoryFilterButton()
        // Al volver de ver algo: "Continuar viendo" y el orden de Favoritos pueden haber cambiado.
        if (playlist.categories.isNotEmpty()) categoryAdapter.submit(buildCategoryListWithFavorites())
        if (binding.recyclerView.adapter === streamAdapter) {
            streamAdapter.notifyDataSetChanged()
        }
    }

    private fun reapplyChannelOverrides() {
        if (appliedOverridesVersion == ChannelOverrides.version) return
        appliedOverridesVersion = ChannelOverrides.version
        playlist = ChannelOverrides.apply(this, rawPlaylist)
        com.example.superplayer.player.MultiViewActivity.pickerCategories = playlist.categories
        searchIndexDirty = true
    }

    /** Tras ocultar/renombrar un canal desde los resultados de búsqueda: repinta categorías y resultados. */
    private fun refreshAfterChannelChange() {
        reapplyChannelOverrides()
        updateCategoryFilterButton()
        categoryAdapter.submit(buildCategoryListWithFavorites())
        streamAdapter.notifyDataSetChanged()
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

    private fun setPlaylist(rawData: PlaylistData, listKey: String) {
        rawPlaylist = rawData
        appliedOverridesVersion = ChannelOverrides.version
        val data = ChannelOverrides.apply(this, rawData)
        playlist = data
        com.example.superplayer.player.MultiViewActivity.pickerCategories = data.categories
        currentListKey = listKey
        searchIndexDirty = true
        binding.recyclerView.adapter = categoryAdapter
        categoryAdapter.submit(buildCategoryListWithFavorites())
        updateCategoryFilterButton()
        binding.emptyView.visibility = if (data.categories.isEmpty()) View.VISIBLE else View.GONE
        // Sin bloquear nada: si esta lista trae guía EPG (o ya la teníamos
        // cargada de antes), se descarga/parsea sola en segundo plano.
        val allStreams = data.categories.flatMap { it.streams }
        // Avisos por programa: en cuanto la guía está cargada, se buscan los programas que encajan.
        val appCtx = applicationContext
        EpgRepository.load(data.epgUrl) {
            try { com.example.superplayer.reminder.ProgramAlerts.scan(appCtx, allStreams) } catch (e: Exception) { }
        }
        // Por si algún favorito cambió de logo/url desde la última vez, o se
        // marcó antes de que existiera esto (ver FavoritesStore); barato,
        // así que no pasa nada por hacerlo en cada carga de lista.
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

    /**
     * Fila de opciones de la lista cargada: un texto de estado y tres botones
     * redondos con icono: Categorías (filtro) / Ordenar / Actualizar.
     * Categorías y Ordenar solo si la lista tiene más de una categoría;
     * Actualizar solo si es una lista remota (hueco). Sin ninguna opción, la
     * fila entera se oculta.
     */
    private fun updateCategoryFilterButton() {
        val total = playlist.categories.size
        val multi = total >= 2
        val isRemoteSlot = currentListKey.startsWith("slot_")
        val row = binding.categoryButtonsRow
        if (!multi && !isRemoteSlot) {
            row.visibility = View.GONE
            return
        }
        row.visibility = View.VISIBLE

        val shown = if (multi) visibleCategories().size else total
        val parts = ArrayList<String>()
        if (multi) {
            parts.add(
                if (shown == total) getString(R.string.list_status_categories, total)
                else getString(R.string.list_status_categories_filtered, shown, total)
            )
        }
        if (isRemoteSlot) {
            val slot = currentListKey.removePrefix("slot_").toIntOrNull()
            val saved = if (slot != null) PlaylistCache.lastSavedAt(this, slot) else null
            if (saved != null) parts.add(getString(R.string.list_status_updated, formatAge(saved)))
        }
        binding.categoryStatusText.text = parts.joinToString(" · ")

        binding.categoryFilterButton.visibility = if (multi) View.VISIBLE else View.GONE
        binding.categoryOrderButton.visibility = if (multi) View.VISIBLE else View.GONE
        binding.categoryUpdateButton.visibility = if (isRemoteSlot) View.VISIBLE else View.GONE
        binding.categoryFilterButton.setOnClickListener { showCategoryFilterDialog() }
        binding.categoryOrderButton.setOnClickListener { showCategoryOrderDialog() }
        binding.categoryUpdateButton.setOnClickListener { refreshCurrentList() }
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
        val favStreams = favoritesStore.sortByOrder(
            playlist.categories.flatMap { it.streams }.filter { favIds.contains(it.id) }
        )
        val result = mutableListOf<Category>()
        // "Continuar viendo": películas/vídeos empezados (de cualquier lista), los más recientes primero.
        val continueStreams = ContinueWatching.getAll(this)
            .filter { !ChannelOverrides.isHidden(this, it.stream.id) }
            .map { ChannelOverrides.renamed(this, it.stream) }
        if (continueStreams.isNotEmpty()) {
            result.add(Category(getString(R.string.continue_category_name), continueStreams))
        }
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
        searchMenuItem = searchItem
        searchViewRef = searchView
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
        menu.findItem(R.id.action_profiles)?.title =
            getString(R.string.action_profiles_current, com.example.superplayer.data.Profiles.active(this).name)
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
                val callbacks = ArrayList(indexReadyCallbacks)
                indexReadyCallbacks.clear()
                callbacks.forEach { it() }
            }
        }.start()
    }


    // -----------------------------------------------------------------
    // Búsqueda global por voz: botón del micrófono en la barra (o la orden
    // que llega del asistente de voz, ver onNewIntent). Entiende "pon La 1",
    // "abre Eurosport", "busca fútbol"...: busca en TODAS las listas (la
    // cargada y las copias de los 5 huecos); si hay un canal claro lo abre
    // directamente, si no enseña los resultados en la búsqueda.
    // -----------------------------------------------------------------

    private fun startVoiceSearch() {
        val intent = Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, java.util.Locale.getDefault().toLanguageTag())
            putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, getString(R.string.voice_prompt))
        }
        try {
            voiceLauncher.launch(intent)
        } catch (e: Exception) {
            Toast.makeText(this, R.string.voice_unavailable, Toast.LENGTH_LONG).show()
        }
    }

    /** Quita las palabras de orden del principio ("pon", "abre", "busca"...) y dice si era solo una búsqueda. */
    private fun parseVoiceCommand(spoken: String): Pair<String, Boolean> {
        var text = normalizeForSearch(spoken).trim()
        var searchOnly = false
        val prefixes = listOf(
            "quiero ver", "quiero", "ponme", "pon me", "poner", "pon", "reproduce", "reproducir",
            "abre", "abrir", "ver", "cambia a", "cambiar a", "busca", "buscar", "el canal", "canal"
        )
        var changed = true
        while (changed) {
            changed = false
            for (prefix in prefixes) {
                if (text == prefix) continue
                if (text.startsWith("$prefix ")) {
                    if (prefix == "busca" || prefix == "buscar") searchOnly = true
                    text = text.removePrefix(prefix).trim()
                    changed = true
                    break
                }
            }
        }
        return text to searchOnly
    }

    private fun handleVoiceQuery(spoken: String) {
        val (query, searchOnly) = parseVoiceCommand(spoken)
        if (query.isBlank()) return
        whenSearchIndexReady {
            val pool = searchIndex?.map { it.stream } ?: playlist.categories.flatMap { it.streams }
            val words = query.split(' ').filter { it.isNotEmpty() }
            val matches = pool.filter { st -> normalizeForSearch(st.name).let { n -> words.all { w -> n.contains(w) } } }
            // Un canal "claro": el que se llama exactamente así; si no, el único que empieza así; si no, el único que coincide.
            val exact = matches.filter { normalizeForSearch(it.name) == query }
            val starting = matches.filter { normalizeForSearch(it.name).startsWith(query) }
            val clear: Stream? = when {
                exact.isNotEmpty() -> exact.first()
                starting.size == 1 -> starting.first()
                matches.size == 1 -> matches.first()
                else -> null
            }
            if (clear != null && !searchOnly) {
                Toast.makeText(this, getString(R.string.voice_opening, clear.name), Toast.LENGTH_SHORT).show()
                PlayerActivity.pendingStream = clear
                PlayerActivity.pendingChannelList = matches
                startActivity(Intent(this, PlayerActivity::class.java))
            } else {
                if (matches.isEmpty()) {
                    Toast.makeText(this, getString(R.string.voice_not_found, query), Toast.LENGTH_LONG).show()
                }
                searchMenuItem?.expandActionView()
                searchViewRef?.setQuery(query, true)
            }
        }
    }

    /** Ejecuta [callback] cuando el índice de búsqueda global esté listo (lo construye si hace falta). */
    private fun whenSearchIndexReady(callback: () -> Unit) {
        if (!searchIndexDirty && !searchIndexBuilding && searchIndex != null) {
            callback()
            return
        }
        indexReadyCallbacks.add(callback)
        refreshSearchIndexIfNeeded()
    }

    /** Orden de voz del asistente ("Ok Google, pon La 1 en Socram TV"): llega como MEDIA_PLAY_FROM_SEARCH. */
    private fun handleAssistantIntent(maybeIntent: Intent?) {
        val intent = maybeIntent ?: return
        if (intent.action != android.provider.MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH) return
        val query = intent.getStringExtra(android.app.SearchManager.QUERY)
        intent.action = null // para no repetirla al girar la pantalla
        if (!query.isNullOrBlank()) binding.root.post { handleVoiceQuery(query) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAssistantIntent(intent)
        handleMatchIntent(intent)
    }

    /** Toque en un aviso de gol/partido (ver sports/GoalAlerts): busca en la guía qué canal lo emite ahora. */
    private fun handleMatchIntent(maybeIntent: Intent?) {
        val intent = maybeIntent ?: return
        val home = intent.getStringExtra(com.example.superplayer.sports.GoalAlerts.EXTRA_HOME) ?: return
        val away = intent.getStringExtra(com.example.superplayer.sports.GoalAlerts.EXTRA_AWAY) ?: return
        intent.removeExtra(com.example.superplayer.sports.GoalAlerts.EXTRA_HOME)
        intent.removeExtra(com.example.superplayer.sports.GoalAlerts.EXTRA_AWAY)
        binding.root.post { openMatchChannel(home, away, 0) }
    }

    private fun openMatchChannel(home: String, away: String, attempt: Int) {
        // La guía se carga en segundo plano al abrir la app: si aún no está, se espera un poco.
        if (EpgRepository.dataRange() == null && attempt < 6) {
            binding.root.postDelayed({ openMatchChannel(home, away, attempt + 1) }, 2_500L)
            return
        }
        whenSearchIndexReady {
            val pool = (searchIndex?.map { it.stream } ?: playlist.categories.flatMap { it.streams }).distinctBy { it.id }
            val strong = ArrayList<Stream>()
            val weak = ArrayList<Stream>()
            for (st in pool) {
                val title = EpgRepository.schedule(st.tvgId).now?.title ?: continue
                val hasHome = com.example.superplayer.sports.GoalAlerts.matchesTeam(listOf(home), title)
                val hasAway = com.example.superplayer.sports.GoalAlerts.matchesTeam(listOf(away), title)
                if (hasHome && hasAway) strong.add(st) else if (hasHome || hasAway) weak.add(st)
            }
            val found = if (strong.isNotEmpty()) strong else weak
            when {
                found.isEmpty() -> Toast.makeText(this, R.string.goal_match_not_found, Toast.LENGTH_LONG).show()
                found.size == 1 -> {
                    PlayerActivity.pendingStream = found[0]
                    PlayerActivity.pendingChannelList = found
                    startActivity(Intent(this, PlayerActivity::class.java))
                }
                else -> androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle(getString(R.string.goal_match_channels, "$home - $away"))
                    .setItems(found.map { it.name }.toTypedArray()) { _, which ->
                        PlayerActivity.pendingStream = found[which]
                        PlayerActivity.pendingChannelList = found
                        startActivity(Intent(this, PlayerActivity::class.java))
                    }
                    .show()
            }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_goal_alerts -> {
                showGoalAlertsDialog(this)
                true
            }
            R.id.action_profiles -> {
                showProfilesDialog(this) { restartAfterProfileChange() }
                true
            }
            R.id.action_program_alerts -> {
                showProgramAlertsDialog(this, playlist.categories.flatMap { it.streams })
                true
            }
            R.id.action_timeshift -> {
                showTimeShiftDialog(this) { }
                true
            }
            R.id.action_now_live -> {
                startActivity(Intent(this, NowLiveActivity::class.java))
                true
            }
            R.id.action_voice -> {
                startVoiceSearch()
                true
            }
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
    /** Tras cambiar de perfil: se actualizan las cosas de fuera de la app y se reinicia la portada con los datos del perfil nuevo. */
    private fun restartAfterProfileChange() {
        try { com.example.superplayer.tv.WatchNextSync.sync(this) } catch (e: Exception) { }
        try { com.example.superplayer.widget.FavoritesWidgetProvider.refreshAll(this) } catch (e: Exception) { }
        try { com.example.superplayer.widget.HomeWidgetProvider.refreshAll(this) } catch (e: Exception) { }
        val intent = Intent(this, MainActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        startActivity(intent)
        finish()
    }

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
            // Refresca lo que se ve: categorías (filtros), canales ocultos/renombrados, favoritos y accesos directos.
            reapplyChannelOverrides()
            categoryAdapter.submit(buildCategoryListWithFavorites())
            updateCategoryFilterButton()
            refreshShortcuts()
            Toast.makeText(
                this,
                getString(
                    R.string.backup_import_ok_full,
                    result.newFavorites, result.listUrls, result.filters,
                    result.hiddenChannels, result.renamedChannels, result.reminders, result.continueItems
                ),
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

// Una lista cuya copia guardada tenga menos de un día no se vuelve a descargar sola al abrir la app (ver autoRefreshLists).
private const val AUTO_REFRESH_MIN_AGE_MS = 24L * 60 * 60 * 1000

private val DIACRITICS = Regex("\\p{M}+")

/** Minúsculas y sin tildes/diéresis (ñ -> n), para comparar nombres al buscar. */
private fun normalizeForSearch(text: String): String =
    java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFD)
        .replace(DIACRITICS, "")
        .lowercase(java.util.Locale.ROOT)
        .trim()
