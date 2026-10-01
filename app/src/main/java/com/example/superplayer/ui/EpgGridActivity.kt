package com.example.superplayer.ui

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.superplayer.R
import com.example.superplayer.data.EpgRepository
import com.example.superplayer.databinding.ActivityEpgGridBinding
import com.example.superplayer.model.Stream
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
 * apareciendo en la lista filtrada, y su nombre se pinta en dorado como
 * pista de que hay que mover la franja (botones de arriba) para encontrarla;
 * si la coincidencia SÍ está dentro de la ventana visible, además se resalta
 * esa celda en concreto.
 */
class EpgGridActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEpgGridBinding
    private lateinit var adapter: EpgGridAdapter

    /** Todos los canales con guía (sin filtrar por búsqueda); ver applySearch. */
    private var allChannels: List<Stream> = emptyList()
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
            onChannelClick = { openPlayer(it) }
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
        PlayerActivity.pendingChannelList = allChannels
        startActivity(Intent(this, PlayerActivity::class.java))
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_search, menu)
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
        val filtered = if (query.isBlank()) {
            allChannels
        } else {
            allChannels.filter {
                EpgGridMath.matchesSearch(it.name, EpgRepository.allEntries(it.tvgId), query)
            }
        }
        adapter.searchQuery = query
        adapter.submit(filtered)
        if (filtered.isEmpty()) {
            binding.emptyView.text = getString(R.string.epg_grid_search_empty, query)
            binding.emptyView.visibility = View.VISIBLE
        } else {
            binding.emptyView.visibility = View.GONE
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    companion object {
        var pendingChannels: List<Stream> = emptyList()
    }
}
