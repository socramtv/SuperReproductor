package com.example.superplayer.ui

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.recyclerview.widget.LinearLayoutManager
import coil.load
import com.example.superplayer.R
import com.example.superplayer.sports.MatchCache
import com.example.superplayer.sports.MatchFeed
import com.example.superplayer.data.EpgRepository
import com.example.superplayer.data.FavoritesStore
import com.example.superplayer.databinding.ActivityEpgGridBinding
import com.example.superplayer.model.Stream
import com.example.superplayer.reminder.ReminderManager
import com.example.superplayer.player.PlayerActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Vista de parrilla de la guía EPG: franjas de horas en horizontal, canales
 * en filas, como complemento a las líneas de texto "Ahora/Después/Esta
 * noche" que ya se ven en la lista de canales normal. Recibe los canales a
 * través de [pendingChannels] (mismo motivo que StreamListActivity.
 * pendingStreams: evitar el límite de tamaño de los Bundles/Binder con una
 * playlist grande), ya filtrados a los que tienen tvg-id (ver
 * MainActivity.openEpgGrid); esta pantalla, además, solo se queda con los
 * que de verdad tienen algún dato de guía cargado (EpgRepository.hasData).
 *
 * La franja horaria visible es fija (3 horas, ver EpgGridMath.WINDOW_HOURS)
 * y se mueve con los botones "◀ 3 h" / "Ahora" / "3 h ▶" en vez de con un
 * scroll libre e ilimitado: un mando de TV no puede "arrastrar", así que
 * mover la franja con un botón normal (perfectamente navegable con el
 * D-pad) es más fiable que depender de un gesto. Dentro de cada franja sí
 * se puede desplazar libremente con el dedo si el contenido no cabe entero
 * en la pantalla.
 *
 * La cabecera de horas y el "carril" de programas de cada fila son, cada
 * uno, su propio HorizontalScrollView; se mantienen sincronizados a mano
 * (ver registerSyncedScroll) para que se desplacen siempre juntos, como si
 * fueran uno solo.
 *
 * El icono de lupa (ver onCreateOptionsMenu/applySearch) busca a la vez
 * entre el nombre de los canales y el título de su programación, en TODA
 * la guía ya descargada (no solo en la franja de 3 horas visible en ese
 * momento): un canal con una coincidencia fuera de la ventana actual sigue
 * apareciendo en la lista filtrada, y su nombre se pinta en dorado. Si la
 * coincidencia es de TÍTULO de programa (no solo de nombre de canal, que
 * no tiene un instante propio) y cae fuera de la franja visible, la franja
 * se mueve sola hasta ahí (ver jumpToSearchMatchIfNeeded); si ya se ve
 * dentro de la franja actual, en vez de moverla se resalta esa celda en
 * concreto.
 *
 * Tocar una celda CON programa abre un diálogo con su título completo (las
 * celdas lo recortan a dos líneas), su horario y -si la guía XMLTV trae un
 * <icon> para ese programa- su póster (ver showProgrammeDetails); las
 * celdas vacías (sin datos de guía) no hacen nada al tocarlas.
 */
class EpgGridActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEpgGridBinding
    private lateinit var adapter: EpgGridAdapter

    /** Todos los canales con guía (sin filtrar por búsqueda); ver applySearch. */
    private var allChannels: List<Stream> = emptyList()

    /** true = el corazón de la barra está activo: solo se ven los canales favoritos (ver toggleFavoritesOnly). */
    private var onlyFavorites = false
    private var currentQuery = ""
    private var favoritesItem: MenuItem? = null

    // Permiso de notificaciones (Android 13+) para poder ver los avisos de los recordatorios.
    private val notificationPermissionLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                android.widget.Toast.makeText(this, R.string.reminder_no_permission, android.widget.Toast.LENGTH_LONG).show()
            }
        }
    private var dataStart: Long = 0L
    private var dataEnd: Long = 0L
    private var windowStart: Long = 0L

    // Scroll horizontal compartido entre la cabecera de horas y todas las
    // filas actualmente en pantalla (ver registerSyncedScroll/
    // unregisterSyncedScroll): un único valor de verdad (sharedScrollX) que
    // se reparte a cualquier HorizontalScrollView que se enganche, y que se
    // actualiza en cuanto CUALQUIERA de ellos se desplaza.
    private var sharedScrollX = 0
    private val syncedScrollViews = mutableSetOf<HorizontalScrollView>()
    private var applyingProgrammaticScroll = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEpgGridBinding.inflate(layoutInflater)
        setContentView(binding.root)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        applySystemBarInsets(top = binding.toolbar, bottom = binding.channelsRecyclerView)

        allChannels = pendingChannels.filter { EpgRepository.hasData(it.tvgId) }
        val range = EpgRepository.dataRange()

        if (allChannels.isEmpty() || range == null) {
            binding.emptyView.visibility = View.VISIBLE
            return
        }
        dataStart = range.first
        dataEnd = range.second
        windowStart = EpgGridMath.clampWindowStart(
            EpgGridMath.roundDownToHour(System.currentTimeMillis()),
            dataStart,
            dataEnd
        )

        adapter = EpgGridAdapter(
            channels = allChannels,
            windowProvider = { windowStart to (windowStart + EpgGridMath.WINDOW_MILLIS) },
            densityProvider = { resources.displayMetrics.density },
            onRowScrollAttached = { registerSyncedScroll(it) },
            onRowScrollDetached = { unregisterSyncedScroll(it) },
            onChannelClick = { openPlayer(it) },
            onCellClick = { stream, cell -> showProgrammeDetails(stream, cell) },
            isReminderSet = { stream, entry -> ReminderManager.isSet(this, stream.id, entry.startMillis) },
            onCellFocus = { stream, cell -> showFocusedProgramme(stream, cell) },
            matchInfo = { entry ->
                MatchCache.find(entry.title, entry.startMillis)?.let { MatchCache.shortStatus(it) }
            }
        )
        binding.channelsRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.channelsRecyclerView.adapter = adapter

        registerSyncedScroll(binding.headerScroll)

        binding.prevWindowButton.setOnClickListener { shiftWindow(-EpgGridMath.WINDOW_MILLIS) }
        binding.nextWindowButton.setOnClickListener { shiftWindow(EpgGridMath.WINDOW_MILLIS) }
        binding.nowWindowButton.setOnClickListener {
            windowStart = EpgGridMath.clampWindowStart(
                EpgGridMath.roundDownToHour(System.currentTimeMillis()),
                dataStart,
                dataEnd
            )
            renderWindow()
        }

        renderWindow()
        refreshMatches()
    }

    // Marcadores y escudos de los partidos que salgan en la guía (ver sports/MatchCache): se piden al abrir
    // y, mientras haya algún partido en juego, cada minuto con la pantalla abierta.
    private val matchHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val matchRefreshRunnable = Runnable { refreshMatches() }

    private fun refreshMatches() {
        matchHandler.removeCallbacks(matchRefreshRunnable)
        MatchCache.refresh {
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                adapter.notifyDataSetChanged()
                if (MatchCache.hasLive()) matchHandler.postDelayed(matchRefreshRunnable, 60_000L)
            }
        }
    }

    override fun onStop() {
        super.onStop()
        matchHandler.removeCallbacksAndMessages(null)
    }

    override fun onStart() {
        super.onStart()
        if (::adapter.isInitialized) refreshMatches()
    }

    /**
     * Barra de abajo con el programa enfocado (mando de TV): canal, título y
     * horario, para saber en cuál estás antes de pulsar OK (detalles y
     * recordatorio).
     */
    private fun showFocusedProgramme(stream: Stream, cell: EpgGridMath.Cell) {
        val entry = cell.entry ?: return
        val reminder = if (ReminderManager.isSet(this, stream.id, entry.startMillis)) "  ⏰" else ""
        binding.focusInfoText.text = getString(
            R.string.epg_grid_focus_info, stream.name, entry.title, EpgRepository.formatRange(entry)
        ) + reminder
        binding.focusInfoText.visibility = View.VISIBLE
    }

    private fun shiftWindow(deltaMillis: Long) {
        windowStart = EpgGridMath.clampWindowStart(windowStart + deltaMillis, dataStart, dataEnd)
        renderWindow()
    }

    /** Vuelve a pintar todo lo que depende de [windowStart]: etiqueta, botones, cabecera de horas y filas. */
    private fun renderWindow() {
        val windowEnd = windowStart + EpgGridMath.WINDOW_MILLIS
        val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        binding.windowRangeLabel.text = getString(
            R.string.epg_grid_window_range,
            fmt.format(Date(windowStart)),
            fmt.format(Date(windowEnd))
        )

        val minStart = EpgGridMath.roundDownToHour(dataStart)
        val maxStart = maxOf(minStart, dataEnd - EpgGridMath.WINDOW_MILLIS)
        binding.prevWindowButton.isEnabled = windowStart > minStart
        binding.nextWindowButton.isEnabled = windowStart < maxStart

        rebuildHourHeader(windowStart, windowEnd)

        // Franja nueva = página nueva: siempre se empieza a ver desde su
        // borde izquierdo, no desde donde se hubiera quedado desplazada la
        // franja anterior (cuyo contenido ya no pinta nada aquí).
        sharedScrollX = 0
        applyingProgrammaticScroll = true
        for (view in syncedScrollViews) view.scrollTo(0, 0)
        applyingProgrammaticScroll = false

        adapter.notifyDataSetChanged()
    }

    private fun rebuildHourHeader(windowStart: Long, windowEnd: Long) {
        val container = binding.hourHeaderContainer
        container.removeAllViews()
        val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        val density = resources.displayMetrics.density
        var hourStart = windowStart
        while (hourStart < windowEnd) {
            val hourEnd = (hourStart + 3_600_000L).coerceAtMost(windowEnd)
            val left = EpgGridMath.xForTime(hourStart, windowStart, density)
            val right = EpgGridMath.xForTime(hourEnd, windowStart, density)
            val label = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams((right - left).coerceAtLeast(1), LinearLayout.LayoutParams.MATCH_PARENT)
                text = fmt.format(Date(hourStart))
                textSize = 12f
                setTextColor(ContextCompat.getColor(this@EpgGridActivity, R.color.on_surface_muted))
                gravity = Gravity.CENTER_VERTICAL
                val pad = (4 * density).toInt()
                setPadding(pad, 0, 0, 0)
            }
            container.addView(label)
            hourStart = hourEnd
        }
    }

    /** Engancha un HorizontalScrollView (cabecera o una fila) al scroll compartido: a partir de ahora se mueve con el resto. */
    private fun registerSyncedScroll(view: HorizontalScrollView) {
        syncedScrollViews.add(view)
        view.scrollTo(sharedScrollX, 0)
        view.setOnScrollChangeListener { _, scrollX, _, _, _ ->
            if (applyingProgrammaticScroll || scrollX == sharedScrollX) return@setOnScrollChangeListener
            sharedScrollX = scrollX
            applyingProgrammaticScroll = true
            for (other in syncedScrollViews) {
                if (other !== view) other.scrollTo(scrollX, 0)
            }
            applyingProgrammaticScroll = false
        }
    }

    /** Retira una fila del scroll compartido al salir de la pantalla (RecyclerView reciclándola para otra posición). */
    private fun unregisterSyncedScroll(view: HorizontalScrollView) {
        syncedScrollViews.remove(view)
    }

    private fun openPlayer(stream: Stream) {
        PlayerActivity.pendingStream = stream
        // TODOS los canales de la parrilla (los que tienen guía EPG), no
        // solo los que queden tras un filtro de búsqueda en curso: así el
        // gesto de "canal siguiente/anterior" en el reproductor recorre el
        // mismo conjunto de siempre (mismo criterio que StreamListActivity
        // con su propio buscador).
        PlayerActivity.pendingChannelList = if (onlyFavorites) baseChannels() else allChannels
        startActivity(Intent(this, PlayerActivity::class.java))
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_epg_grid, menu)
        favoritesItem = menu.findItem(R.id.action_epg_favorites)
        updateFavoritesIcon()
        val searchItem = menu.findItem(R.id.action_search)
        val searchView = searchItem.actionView as SearchView
        searchView.queryHint = getString(R.string.epg_grid_search_hint)
        styleSearchView(this, searchView)
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?) = true
            override fun onQueryTextChange(newText: String?): Boolean {
                applySearch(newText.orEmpty())
                return true
            }
        })
        return true
    }

    /**
     * Filtra las filas mostradas por nombre de canal o título de programa,
     * buscando en TODA la guía ya descargada de cada canal (ver
     * EpgRepository.allEntries/EpgGridMath.matchesSearch), no solo en la
     * franja de horas visible ahora mismo. En blanco, vuelve a enseñar
     * todos los canales. Si la búsqueda llega antes de que haya guía
     * cargada (pantalla mostrando el aviso de "sin EPG", adapter sin
     * inicializar todavía), no hace nada: no hay nada que filtrar.
     */
    private fun applySearch(query: String) {
        if (!::adapter.isInitialized) return
        currentQuery = query
        val base = baseChannels()
        val filtered = if (query.isBlank()) {
            base
        } else {
            base.filter {
                EpgGridMath.matchesSearch(it.name, EpgRepository.allEntries(it.tvgId), query)
            }
        }
        adapter.searchQuery = query
        adapter.submit(filtered)
        if (filtered.isEmpty()) {
            binding.emptyView.text = when {
                query.isNotBlank() -> getString(R.string.epg_grid_search_empty, query)
                onlyFavorites -> getString(R.string.epg_grid_favorites_empty)
                else -> getString(R.string.epg_grid_empty)
            }
            binding.emptyView.visibility = View.VISIBLE
        } else {
            binding.emptyView.visibility = View.GONE
        }
        jumpToSearchMatchIfNeeded(query, filtered)
    }

    /** Los canales sobre los que actúa la búsqueda: todos los que tienen guía, o solo los favoritos si el corazón está activo. */
    private fun baseChannels(): List<Stream> {
        if (!onlyFavorites) return allChannels
        val favorites = FavoritesStore(this).getAll()
        return allChannels.filter { it.id in favorites }
    }

    /** Botón del corazón: alterna entre ver todos los canales con guía o solo los favoritos (combinado con la búsqueda). */
    private fun toggleFavoritesOnly() {
        onlyFavorites = !onlyFavorites
        updateFavoritesIcon()
        applySearch(currentQuery)
    }

    private fun updateFavoritesIcon() {
        favoritesItem?.setIcon(if (onlyFavorites) R.drawable.ic_heart_filled else R.drawable.ic_heart_outline)
    }

    /**
     * Si la búsqueda tiene una coincidencia de TÍTULO de programa (no solo
     * de nombre de canal, que no tiene un instante propio al que saltar)
     * fuera de la franja horaria visible ahora mismo, mueve la franja sola
     * hasta ahí (ver EpgGridMath.bestSearchJumpTarget) en vez de dejar que
     * haya que encontrarla a mano con los botones "◀ 3 h"/"3 h ▶" de
     * arriba. Si la coincidencia ya se ve en la franja actual, o no hay
     * ninguna coincidencia de título, no toca la franja para nada.
     */
    private fun jumpToSearchMatchIfNeeded(query: String, filtered: List<Stream>) {
        if (query.isBlank()) return
        val entries = filtered.flatMap { EpgRepository.allEntries(it.tvgId) }
        val target = EpgGridMath.bestSearchJumpTarget(entries, query, System.currentTimeMillis()) ?: return
        val windowEnd = windowStart + EpgGridMath.WINDOW_MILLIS
        if (target >= windowStart && target < windowEnd) return // ya se ve, no hace falta moverla
        windowStart = EpgGridMath.clampWindowStart(EpgGridMath.roundDownToHour(target), dataStart, dataEnd)
        renderWindow()
    }

    /**
     * Diálogo con los detalles de un programa de la parrilla al tocar su
     * celda (ver EpgGridAdapter.onCellClick): título completo (las celdas
     * lo recortan a dos líneas), horario y -si la guía XMLTV trae un
     * <icon> para ese programa (ver EpgRepository.parseXmlTv)- su póster.
     * Las celdas vacías (sin programa) no llaman a esto.
     */
    private fun showProgrammeDetails(stream: Stream, cell: EpgGridMath.Cell) {
        val entry = cell.entry ?: return
        val density = resources.displayMetrics.density
        val sidePad = (24 * density).toInt()
        val topPad = (16 * density).toInt()

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(sidePad, topPad, sidePad, 0)
        }

        MatchCache.find(entry.title, entry.startMillis)?.let { addMatchCard(container, it, density) }

        if (!entry.iconUrl.isNullOrBlank()) {
            val posterHeightPx = (170 * density).toInt()
            val posterMarginPx = (12 * density).toInt()
            val poster = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, posterHeightPx).apply {
                    bottomMargin = posterMarginPx
                }
                scaleType = ImageView.ScaleType.CENTER_CROP
            }
            poster.load(entry.iconUrl) {
                placeholder(R.drawable.ic_placeholder)
                error(R.drawable.ic_placeholder)
            }
            container.addView(poster)
        }

        container.addView(
            TextView(this).apply {
                text = EpgRepository.formatRange(entry)
                textSize = 14f
                setTextColor(ContextCompat.getColor(this@EpgGridActivity, R.color.on_surface_muted))
            }
        )

        val builder = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(entry.title)
            .setView(container)
            .setPositiveButton(android.R.string.ok, null)
        // Recordatorio: solo para programas que todavía no han empezado.
        if (entry.startMillis > System.currentTimeMillis()) {
            if (ReminderManager.isSet(this, stream.id, entry.startMillis)) {
                builder.setNeutralButton(R.string.reminder_remove_button) { _, _ ->
                    ReminderManager.remove(this, stream.id, entry.startMillis)
                    android.widget.Toast.makeText(this, R.string.reminder_removed_toast, android.widget.Toast.LENGTH_SHORT).show()
                    adapter.notifyDataSetChanged()
                }
            } else {
                builder.setNeutralButton(R.string.reminder_set_button) { _, _ ->
                    if (android.os.Build.VERSION.SDK_INT >= 33 &&
                        ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) !=
                        android.content.pm.PackageManager.PERMISSION_GRANTED
                    ) {
                        notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    }
                    if (ReminderManager.add(this, stream, entry)) {
                        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(entry.startMillis))
                        android.widget.Toast.makeText(
                            this,
                            getString(R.string.reminder_set_toast, ReminderManager.LEAD_MINUTES, time),
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                        adapter.notifyDataSetChanged()
                    }
                }
            }
        }
        builder.show()
    }

    /** Tarjeta de partido en los detalles de un programa de fútbol: escudos, marcador, estado, competición y goles/expulsiones. */
    private fun addMatchCard(container: LinearLayout, m: MatchFeed.Match, density: Float) {
        val crestPx = (56 * density).toInt()
        val onBg = ContextCompat.getColor(this, R.color.on_background)
        val muted = ContextCompat.getColor(this, R.color.on_surface_muted)

        fun crest(url: String?) = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(crestPx, crestPx)
            scaleType = ImageView.ScaleType.FIT_CENTER
            load(url) {
                placeholder(R.drawable.ic_placeholder)
                error(R.drawable.ic_placeholder)
            }
        }
        fun teamColumn(name: String, logo: String?) = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(crest(logo))
            addView(TextView(this@EpgGridActivity).apply {
                text = name
                textSize = 12f
                gravity = android.view.Gravity.CENTER
                maxLines = 2
                setTextColor(onBg)
            })
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        row.addView(teamColumn(m.home, m.homeLogo))
        row.addView(TextView(this).apply {
            text = if (m.state == "pre") "vs" else "${m.homeScore} - ${m.awayScore}"
            textSize = 26f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = android.view.Gravity.CENTER
            setPadding((8 * density).toInt(), 0, (8 * density).toInt(), 0)
            setTextColor(onBg)
        })
        row.addView(teamColumn(m.away, m.awayLogo))
        container.addView(row)

        val status = when (m.state) {
            "in" -> getString(R.string.match_live, m.clock.ifBlank { "—" })
            "post" -> getString(R.string.match_final)
            else -> if (m.startMillis > 0) {
                getString(R.string.match_upcoming, SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(m.startMillis)))
            } else ""
        }
        container.addView(TextView(this).apply {
            text = listOf(status, m.league).filter { it.isNotBlank() }.joinToString(" · ")
            textSize = 13f
            gravity = android.view.Gravity.CENTER
            setTextColor(muted)
            setPadding(0, (6 * density).toInt(), 0, (8 * density).toInt())
        })

        val events = m.details.map { d ->
            val icon = if (d.kind == MatchFeed.Kind.RED_CARD) "🟥" else "⚽"
            val extra = when {
                d.ownGoal -> " (en propia)"
                d.penalty -> " (penalti)"
                else -> ""
            }
            "$icon ${d.minute} ${d.player.ifBlank { d.team }}$extra".replace("  ", " ")
        }
        if (events.isNotEmpty()) {
            container.addView(TextView(this).apply {
                text = events.joinToString("\n")
                textSize = 12f
                setTextColor(onBg)
                setPadding(0, 0, 0, (12 * density).toInt())
            })
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        if (item.itemId == R.id.action_epg_favorites) {
            toggleFavoritesOnly()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    companion object {
        var pendingChannels: List<Stream> = emptyList()
    }
}
