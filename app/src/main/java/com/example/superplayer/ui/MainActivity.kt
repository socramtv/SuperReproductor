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
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.superplayer.R
import com.example.superplayer.data.AppPrefs
import com.example.superplayer.data.FavoritesStore
import com.example.superplayer.data.PlaylistRepository
import com.example.superplayer.databinding.ActivityMainBinding
import com.example.superplayer.model.Category
import com.example.superplayer.model.PlaylistData
import com.example.superplayer.model.Stream
import com.example.superplayer.player.PlayerActivity

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var favoritesStore: FavoritesStore
    private lateinit var categoryAdapter: CategoryAdapter
    private lateinit var streamAdapter: StreamAdapter
    private var playlist: PlaylistData = PlaylistData(emptyList())

    private val openDocumentLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) loadFromUri(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

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

        loadInitialPlaylist()
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
                "No se pudo leer ese archivo (${e.message}). Revisa que sea JSON válido.",
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
        startActivity(Intent(this, PlayerActivity::class.java))
    }

    private fun toggleFavorite(stream: Stream) {
        val nowFav = favoritesStore.toggle(stream.id)
        Toast.makeText(
            this,
            getString(if (nowFav) R.string.added_to_favorites else R.string.removed_from_favorites),
            Toast.LENGTH_SHORT
        ).show()
        categoryAdapter.submit(buildCategoryListWithFavorites())
        streamAdapter.notifyDataSetChanged()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        val searchItem = menu.findItem(R.id.action_search)
        val searchView = searchItem.actionView as SearchView
        searchView.queryHint = getString(R.string.search_hint)
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
        return true
    }

    private fun applySearch(query: String) {
        if (query.isBlank()) {
            binding.recyclerView.adapter = categoryAdapter
            return
        }
        val matches = playlist.categories.flatMap { it.streams }
            .filter { it.name.contains(query, ignoreCase = true) }
        streamAdapter.submit(matches)
        binding.recyclerView.adapter = streamAdapter
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_load_playlist -> {
                openDocumentLauncher.launch(arrayOf("application/json"))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}
