package ru.jux.launcher.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.jux.launcher.mods.Modrinth

class CatalogSearch(
    private val scope: CoroutineScope,
    private val fetch: (query: String, offset: Int) -> Modrinth.SearchPage,
) {
    var query by mutableStateOf("")
    var hits by mutableStateOf<List<Modrinth.SearchHit>>(emptyList())
        private set
    var total by mutableStateOf(0)
        private set
    var searching by mutableStateOf(false)
        private set
    var failed by mutableStateOf(false)
        private set

    private var job: Job? = null

    val hasMore: Boolean get() = hits.size < total

    fun run(more: Boolean = false) {
        job?.cancel()
        val offset = if (more) hits.size else 0
        searching = true
        failed = false
        job = scope.launch {
            try {
                val page = withContext(Dispatchers.IO) { fetch(query, offset) }
                hits = if (more) hits + page.hits.filter { hit -> hits.none { it.projectId == hit.projectId } } else page.hits
                total = page.totalHits
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed = true
            } finally {
                searching = false
            }
        }
    }

    fun reset() {
        job?.cancel()
        query = ""
        hits = emptyList()
        total = 0
    }
}
