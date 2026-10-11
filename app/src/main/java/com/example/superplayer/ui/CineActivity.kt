package com.example.superplayer.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.recyclerview.widget.GridLayoutManager
import coil.load
import com.example.superplayer.R
import com.example.superplayer.data.AppPrefs
import com.example.superplayer.data.FavoritesStore
import com.example.superplayer.data.MovieInfo
import com.example.superplayer.databinding.ActivityCineBinding
import com.example.superplayer.model.Category
import com.example.superplayer.model.Stream
import com.example.superplayer.player.PlayerActivity
import java.text.Normalizer
import java.util.Locale

/**
 * Cine: parrilla de carátulas de las películas de tus listas, con buscador,
 * y al tocar una, su ficha (carátula grande, año, género y sinopsis) con
 * "Ver". Las categorías que cuentan como cine se detectan por el nombre
 * (película, cine, movie, film, vod, estreno...) y se pueden elegir a mano
 * con el botón "Categorías".
 */
class CineActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCineBinding
    private lateinit var adapter: CineAdapter
    private var allMovies: List<Stream> = emptyList()
    private var query = ""
    private var posterHeight = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCineBinding.inflate(layoutInflater)
        setContentView(binding.root)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = getString(R.string.cine_title)
        applySystemBarInsets(top = binding.toolbar, bottom = binding.recyclerView)

        val isTv = packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
        val widthDp = resources.displayMetrics.widthPixels / resources.displayMetrics.density
        val columns = if (isTv) 6 else (widthDp / 120f).toInt().coerceIn(3, 8)
        val cellWidthPx = resources.displayMetrics.widthPixels / columns - (20 * resources.displayMetrics.density).toInt()
        posterHeight = (cellWidthPx * 1.5f).toInt()

        adapter = CineAdapter(emptyList(), { posterHeight }) { stream -> showDetails(stream) }
        binding.recyclerView.layoutManager = GridLayoutManager(this, columns)
        binding.recyclerView.adapter = adapter

        binding.cineSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString().orEmpty()
                applyFilter()
            }
        })
        binding.cineCategoriesButton.setOnClickListener { showCategoryChooser() }

        reload()
        if (AppPrefs.getTmdbKey(this).isBlank()) {
            android.widget.Toast.makeText(this, R.string.cine_tmdb_key_missing, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menuInflater.inflate(R.menu.menu_cine, menu)
        return true
    }

    private fun showTmdbKeyDialog() {
        val input = android.widget.EditText(this).apply {
            setText(AppPrefs.getTmdbKey(this@CineActivity))
            hint = getString(R.string.cine_tmdb_key_hint)
            setSingleLine(true)
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.cine_tmdb_key_title)
            .setView(box)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val key = input.text.toString().trim()
                AppPrefs.setTmdbKey(this, key)
                adapter.notifyDataSetChanged()
                if (key.isNotEmpty()) {
                    Thread {
                        val code = MovieInfo.checkTmdbKey(key)
                        runOnUiThread {
                            val msg = when (code) {
                                200 -> R.string.cine_tmdb_key_ok
                                401 -> R.string.cine_tmdb_key_bad
                                else -> R.string.cine_tmdb_key_unknown
                            }
                            android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show()
                        }
                    }.start()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        if (item.itemId == R.id.action_tmdb_key) {
            showTmdbKeyDialog()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun looksLikeMovies(name: String): Boolean = MOVIE_NAME.containsMatchIn(name)

    /** Categorías elegidas a mano; si no hay elección, las que parecen de cine por su nombre. */
    private fun chosenCategories(): List<Category> {
        val chosen = AppPrefs.getCineCategories(this)
        return if (chosen != null) categories.filter { it.name in chosen }
        else categories
    }

    private fun reload() {
        allMovies = chosenCategories()
            .flatMap { it.streams }
            .distinctBy { it.id }
            .sortedBy { MovieInfo.cleanTitle(it.name).first.lowercase(Locale.ROOT) }
        applyFilter()
    }

    private fun normalize(text: String): String =
        Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")

    private fun applyFilter() {
        val q = normalize(query.trim())
        val shown = if (q.isEmpty()) allMovies else allMovies.filter { normalize(it.name).contains(q) }
        adapter.submit(shown)
        binding.emptyView.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun showCategoryChooser() {
        val names = categories.map { it.name }
        if (names.isEmpty()) return
        val current = AppPrefs.getCineCategories(this) ?: chosenCategories().map { it.name }.toSet()
        val checked = BooleanArray(names.size) { names[it] in current }
        AlertDialog.Builder(this)
            .setTitle(R.string.cine_categories_title)
            .setMultiChoiceItems(names.toTypedArray(), checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                AppPrefs.setCineCategories(this, names.filterIndexed { i, _ -> checked[i] }.toSet())
                reload()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showDetails(stream: Stream) {
        val density = resources.displayMetrics.density
        val pad = (16 * density).toInt()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
        }
        val poster = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (260 * density).toInt())
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        val meta = TextView(this).apply {
            textSize = 13f
            setTextColor(ContextCompat.getColor(this@CineActivity, R.color.primary))
            setPadding(0, (10 * density).toInt(), 0, (6 * density).toInt())
        }
        val synopsis = TextView(this).apply {
            textSize = 14f
            setTextColor(ContextCompat.getColor(this@CineActivity, R.color.on_background))
        }
        content.addView(poster)
        content.addView(meta)
        content.addView(synopsis)
        val extra = TextView(this).apply {
            textSize = 13f
            setTextColor(ContextCompat.getColor(this@CineActivity, R.color.on_background))
            setPadding(0, (10 * density).toInt(), 0, 0)
        }
        val trailer = TextView(this).apply {
            textSize = 15f
            text = getString(R.string.cine_trailer)
            setTextColor(ContextCompat.getColor(this@CineActivity, R.color.primary))
            setPadding(0, (12 * density).toInt(), 0, (8 * density).toInt())
            visibility = View.GONE
        }
        content.addView(extra)
        content.addView(trailer)
        val scroll = ScrollView(this)
        scroll.addView(content)

        fun fill(info: MovieInfo.Info?) {
            poster.load(info?.posterUrl ?: stream.icon) {
                placeholder(R.drawable.ic_placeholder)
                error(R.drawable.ic_placeholder)
            }
            val parts = listOfNotNull(
                info?.rating?.takeIf { it > 0.0 }?.let { "★ " + String.format(Locale.US, "%.1f", it) },
                info?.year,
                info?.runtimeMin?.takeIf { it > 0 }?.let { if (it >= 60) "${it / 60} h ${it % 60} min" else "$it min" },
                info?.genre,
                info?.certification?.takeIf { it.isNotBlank() }
            )
            meta.text = parts.joinToString(" · ")
            meta.visibility = if (parts.isEmpty()) View.GONE else View.VISIBLE
            val tag = info?.tagline?.takeIf { it.isNotBlank() }
            val over = info?.overview?.takeIf { it.isNotBlank() } ?: getString(R.string.cine_no_synopsis)
            synopsis.text = if (tag != null) "“$tag”\n\n$over" else over
            val lines = ArrayList<String>()
            info?.director?.takeIf { it.isNotBlank() }?.let {
                lines.add(getString(if (info?.isTv == true) R.string.cine_creator else R.string.cine_director) + ": " + it)
            }
            if (info != null && info.cast.isNotEmpty()) {
                lines.add(getString(R.string.cine_cast) + ": " + info.cast.joinToString(", "))
            }
            extra.text = lines.joinToString("\n\n")
            extra.visibility = if (lines.isEmpty()) View.GONE else View.VISIBLE
            val tk = info?.trailerKey
            if (tk.isNullOrBlank()) {
                trailer.visibility = View.GONE
            } else {
                trailer.visibility = View.VISIBLE
                trailer.setOnClickListener {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.youtube.com/watch?v=$tk")))
                    } catch (e: Exception) {
                        android.widget.Toast.makeText(this@CineActivity, R.string.cine_trailer_error, android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        val cached = MovieInfo.peek(this, stream.name)
        fill(cached)
        if (MovieInfo.needsFetch(this, stream.name)) {
            if (cached == null) synopsis.text = getString(R.string.cine_searching)
            MovieInfo.request(this, stream.name) { found ->
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        fill(found)
                        adapter.notifyDataSetChanged()
                    }
                }
            }
        }

        val favorites = FavoritesStore(this)
        val title = cached?.title?.takeIf { it.isNotBlank() } ?: MovieInfo.cleanTitle(stream.name).first
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(scroll)
            .setPositiveButton(R.string.cine_watch) { _, _ ->
                PlayerActivity.pendingStream = stream
                PlayerActivity.pendingChannelList = emptyList()
                startActivity(Intent(this, PlayerActivity::class.java))
            }
            .setNeutralButton(
                if (favorites.isFavorite(stream.id)) R.string.channel_opt_unfavorite else R.string.channel_opt_favorite
            ) { _, _ -> favorites.toggle(stream) }
            .setNegativeButton(R.string.channel_check_close, null)
            .show()
    }

    companion object {
        /** Las categorías de la lista cargada (las rellena MainActivity antes de abrir esta pantalla). */
        var categories: List<Category> = emptyList()

        private val MOVIE_NAME = Regex(
            "pel[ií]cula|peli|cine|movie|film|vod|estreno",
            RegexOption.IGNORE_CASE
        )
    }
}
