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
import androidx.recyclerview.widget.LinearLayoutManager
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
    private lateinit var rowsAdapter: CineRowsAdapter
    private var rowPosterWidth = 0
    private var rowPosterHeight = 0
    private var rows: List<Row> = emptyList()
    private var query = ""
    private var posterHeight = 0
    private var selectedCategory = 0 // 0 = todas

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

        // Filas al estilo Netflix: carátulas en horizontal, un poco más pequeñas que en la parrilla.
        rowPosterWidth = ((if (isTv) 150f else 118f) * resources.displayMetrics.density).toInt()
        rowPosterHeight = (rowPosterWidth * 1.5f).toInt()
        rowsAdapter = CineRowsAdapter(emptyList(), { rowPosterHeight }, { rowPosterWidth }) { stream -> showDetails(stream) }
        binding.rowsView.layoutManager = LinearLayoutManager(this)
        binding.rowsView.adapter = rowsAdapter
        binding.rowsView.setHasFixedSize(false)

        binding.cineSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString().orEmpty()
                applyFilter()
            }
        })
        setupCategorySpinner()

        reload()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun looksLikeMovies(name: String): Boolean = MOVIE_NAME.containsMatchIn(name)

    /** Una película con su clave de orden y su nombre normalizado ya calculados (así buscar y ordenar miles no bloquea la pantalla). */
    private class Entry(val stream: Stream, val sortKey: String, val norm: String)

    private var entries: List<Entry> = emptyList()
    private var reloadToken = 0

    private fun reload() {
        val token = ++reloadToken
        val list = if (selectedCategory == 0) categories else listOfNotNull(categories.getOrNull(selectedCategory - 1))
        val allCategories = categories
        Thread {
            val built = try {
                list.flatMap { it.streams }
                    .distinctBy { it.id }
                    .map { Entry(it, MovieInfo.cleanTitle(it.name).first.lowercase(Locale.ROOT), normalize(it.name)) }
                    .sortedBy { it.sortKey }
            } catch (e: Throwable) {
                emptyList()
            }
            val builtRows = try { buildRows(allCategories) } catch (e: Throwable) { emptyList() }
            runOnUiThread {
                if (isFinishing || isDestroyed || token != reloadToken) return@runOnUiThread
                entries = built
                rows = builtRows
                applyFilter()
            }
        }.start()
    }

    private val markRegex = Regex("\\p{M}+")

    private fun normalize(text: String): String =
        Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD).replace(markRegex, "")

    /** Una fila de Cine: título y sus películas. */
    class Row(val title: String, val streams: List<Stream>)

    /**
     * Filas automáticas: primero una por cada categoría de la lista (las que
     * existan, en su orden) y luego una por género (Acción, Animación,
     * Comedia...) con las películas cuya ficha ya se conoce, de modo que
     * salen solas según se van descubriendo.
     */
    private fun buildRows(source: List<Category>): List<Row> {
        val out = ArrayList<Row>()
        val all = LinkedHashMap<String, Stream>()
        for (category in source) {
            val streams = category.streams.distinctBy { it.id }
            if (streams.isEmpty()) continue
            out.add(Row(category.name, streams.take(ROW_MAX)))
            for (s in streams) all.putIfAbsent(s.id, s)
        }
        val byGenre = HashMap<String, MutableList<Stream>>()
        for (stream in all.values) {
            val genre = MovieInfo.peek(this, stream.name)?.genre ?: continue
            for (g in genre.split(",", "/", "&")) {
                val name = g.trim()
                if (name.length < 3) continue
                byGenre.getOrPut(name) { ArrayList() }.add(stream)
            }
        }
        byGenre.entries
            .filter { it.value.size >= GENRE_MIN }
            .sortedByDescending { it.value.size }
            .take(GENRE_ROWS)
            .forEach { out.add(Row("🎭 " + it.key, it.value.take(ROW_MAX))) }
        return out
    }

    private fun applyFilter() {
        val q = normalize(query.trim())
        if (q.isEmpty() && selectedCategory == 0) {
            binding.rowsView.visibility = View.VISIBLE
            binding.recyclerView.visibility = View.GONE
            rowsAdapter.submit(rows)
            binding.emptyView.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
            return
        }
        binding.rowsView.visibility = View.GONE
        binding.recyclerView.visibility = View.VISIBLE
        val shown = (if (q.isEmpty()) entries else entries.filter { it.norm.contains(q) }).map { it.stream }
        adapter.submit(shown)
        binding.emptyView.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun setupCategorySpinner() {
        val names = ArrayList<String>()
        names.add(getString(R.string.cine_all_categories))
        categories.forEach { names.add("${it.name} (${it.streams.size})") }
        val textColor = ContextCompat.getColor(this, R.color.on_background)
        val adapter = object : android.widget.ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, names) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent) as TextView
                v.setTextColor(textColor)
                return v
            }

            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getDropDownView(position, convertView, parent) as TextView
                v.setTextColor(textColor)
                v.setPadding(32, 28, 32, 28)
                return v
            }
        }
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.cineCategorySpinner.adapter = adapter
        binding.cineCategorySpinner.setPopupBackgroundDrawable(
            android.graphics.drawable.ColorDrawable(ContextCompat.getColor(this, R.color.surface_dark))
        )
        binding.cineCategorySpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position != selectedCategory) {
                    selectedCategory = position
                    reload()
                }
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
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

        private const val ROW_MAX = 60
        private const val GENRE_MIN = 4
        private const val GENRE_ROWS = 12

        private val MOVIE_NAME = Regex(
            "pel[ií]cula|peli|cine|movie|film|vod|estreno",
            RegexOption.IGNORE_CASE
        )
    }
}
