package com.example.superplayer.ui

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.appcompat.widget.SearchView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.superplayer.R
import com.example.superplayer.data.ContinueWatching
import com.example.superplayer.data.FavoritesStore
import com.example.superplayer.databinding.ActivityStreamListBinding
import com.example.superplayer.model.Stream
import com.example.superplayer.player.PlayerActivity
import com.example.superplayer.player.ShortcutsHelper

/**
 * Muestra los canales de UNA categoría. Recibe la lista a través de
 * [pendingStreams] (se evita pasarla como extra del Intent porque una
 * playlist grande podría superar el límite de tamaño de los Bundles/Binder).
 */
class StreamListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityStreamListBinding
    private lateinit var favoritesStore: FavoritesStore
    private lateinit var adapter: StreamAdapter
    private var allStreams: List<Stream> = emptyList()
    private var isFavoritesCategory = false
    private var isContinueCategory = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStreamListBinding.inflate(layoutInflater)
        setContentView(binding.root)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        applySystemBarInsets(top = binding.toolbar, bottom = binding.recyclerView)

        favoritesStore = FavoritesStore(this)
        allStreams = pendingStreams
        val categoryName = intent.getStringExtra(EXTRA_CATEGORY_NAME).orEmpty()
        title = categoryName
        isFavoritesCategory = categoryName == getString(R.string.favorites_category_name)
        isContinueCategory = categoryName == getString(R.string.continue_category_name)

        adapter = StreamAdapter(
            items = allStreams,
            isFavorite = { favoritesStore.isFavorite(it.id) },
            onClick = { openPlayer(it) },
            onToggleFavorite = { toggleFavorite(it) },
            extraLine = { stream ->
                if (!isContinueCategory) null
                else ContinueWatching.getAll(this).firstOrNull { it.stream.id == stream.id }?.let {
                    getString(
                        R.string.continue_progress,
                        ContinueWatching.formatClock(it.positionMs),
                        ContinueWatching.formatClock(it.durationMs)
                    )
                }
            }
        )
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
        binding.emptyView.visibility = if (allStreams.isEmpty()) View.VISIBLE else View.GONE
    }

    override fun onResume() {
        super.onResume()
        adapter.notifyDataSetChanged()
    }

    private fun openPlayer(stream: Stream) {
        PlayerActivity.pendingStream = stream
        // La lista completa de la categoría (no la filtrada por búsqueda si
        // hubiera una en curso): así el gesto de "canal siguiente/anterior"
        // en el reproductor recorre todos los canales de la categoría.
        PlayerActivity.pendingChannelList = allStreams
        startActivity(Intent(this, PlayerActivity::class.java))
    }

    private fun toggleFavorite(stream: Stream) {
        val nowFav = favoritesStore.toggle(stream)
        Toast.makeText(
            this,
            getString(if (nowFav) R.string.added_to_favorites else R.string.removed_from_favorites),
            Toast.LENGTH_SHORT
        ).show()
        adapter.notifyDataSetChanged()
        // También desde aquí, para que un favorito marcado/quitado dentro de
        // una categoría (no solo desde la portada) actualice los accesos
        // directos al momento (ver MainActivity.refreshShortcuts).
        ShortcutsHelper.refresh(this, favoritesStore.getAllStreams())
    }

    /** "Ordenar favoritos": el orden manual se guarda y vale para la categoría Favoritos y para los accesos directos. */
    private fun showOrderFavoritesDialog() {
        val byId = allStreams.associateBy { it.id }
        showReorderDialog(
            activity = this,
            titleRes = R.string.favorites_order_title,
            items = allStreams.map { it.id },
            labelOf = { byId[it]?.name ?: it },
            onSave = { newIds ->
                // Los favoritos de otras listas (no visibles aquí) conservan su sitio, detrás de estos.
                val rest = favoritesStore.getOrder().filter { it !in newIds }
                favoritesStore.setOrder(newIds + rest)
                allStreams = favoritesStore.sortByOrder(allStreams)
                adapter.submit(allStreams)
                ShortcutsHelper.refresh(this, favoritesStore.getAllStreams())
            }
        )
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_stream_list, menu)
        menu.findItem(R.id.action_order_favorites).isVisible = isFavoritesCategory
        menu.findItem(R.id.action_clear_continue).isVisible = isContinueCategory
        val searchItem = menu.findItem(R.id.action_search)
        val searchView = searchItem.actionView as SearchView
        searchView.queryHint = getString(R.string.search_hint)
        styleSearchView(this, searchView)
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?) = true
            override fun onQueryTextChange(newText: String?): Boolean {
                val query = newText.orEmpty()
                val filtered = if (query.isBlank()) {
                    allStreams
                } else {
                    allStreams.filter { it.name.contains(query, ignoreCase = true) }
                }
                adapter.submit(filtered)
                return true
            }
        })
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        if (item.itemId == R.id.action_order_favorites) {
            showOrderFavoritesDialog()
            return true
        }
        if (item.itemId == R.id.action_clear_continue) {
            ContinueWatching.clear(this)
            Toast.makeText(this, R.string.continue_cleared, Toast.LENGTH_SHORT).show()
            allStreams = emptyList()
            adapter.submit(allStreams)
            binding.emptyView.visibility = View.VISIBLE
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    companion object {
        const val EXTRA_CATEGORY_NAME = "category_name"
        var pendingStreams: List<Stream> = emptyList()
    }
}
