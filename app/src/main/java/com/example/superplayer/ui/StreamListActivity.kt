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
import com.example.superplayer.data.FavoritesStore
import com.example.superplayer.databinding.ActivityStreamListBinding
import com.example.superplayer.model.Stream
import com.example.superplayer.player.PlayerActivity

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
        title = intent.getStringExtra(EXTRA_CATEGORY_NAME).orEmpty()

        adapter = StreamAdapter(
            items = allStreams,
            isFavorite = { favoritesStore.isFavorite(it.id) },
            onClick = { openPlayer(it) },
            onToggleFavorite = { toggleFavorite(it) }
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
        startActivity(Intent(this, PlayerActivity::class.java))
    }

    private fun toggleFavorite(stream: Stream) {
        val nowFav = favoritesStore.toggle(stream.id)
        Toast.makeText(
            this,
            getString(if (nowFav) R.string.added_to_favorites else R.string.removed_from_favorites),
            Toast.LENGTH_SHORT
        ).show()
        adapter.notifyDataSetChanged()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_search, menu)
        val searchItem = menu.findItem(R.id.action_search)
        val searchView = searchItem.actionView as SearchView
        searchView.queryHint = getString(R.string.search_hint)
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
        return super.onOptionsItemSelected(item)
    }

    companion object {
        const val EXTRA_CATEGORY_NAME = "category_name"
        var pendingStreams: List<Stream> = emptyList()
    }
}
