package com.hortus1556.exilum.data

import com.hortus1556.exilum.BuildConfig
import com.hortus1556.exilum.model.ArtistResult
import com.hortus1556.exilum.model.ModuleResult
import com.hortus1556.exilum.model.SearchListResult
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import nl.adaptivity.xmlutil.serialization.*
import timber.log.Timber

class ModArchiveService(private val client: HttpClient) {

    private suspend inline fun <reified T> executeRequest(
        request: String,
        additionalParams: Map<String, Any?> = emptyMap()
    ): Result<T> = runCatching {
        val httpResponse = client.get("/xml-tools.php") {
            parameter("key", BuildConfig.API_KEY)
            parameter("request", request)
            additionalParams.forEach { (key, value) ->
                if (value != null) parameter(key, value)
            }
        }
        if (BuildConfig.DEBUG) {
            Timber.d("URL: ${httpResponse.call.request.url}")
        }

        // Every caller is on the main thread - the pager's loads as much as the
        // module page - and a page of results is 25 whole module records:
        // turned from XML into objects there, each next page hitched the list
        // as it arrived in the middle of a scroll.
        val data: T = withContext(Dispatchers.Default) {
            val response = httpResponse.bodyAsText()
            val decoded: T = XML.decodeFromString(response)
            decoded
        }

        val error = when (data) {
            is ModuleResult -> data.error
            is SearchListResult -> data.error
            is ArtistResult -> data.error
            else -> null
        }

        if (!error.isNullOrEmpty()) throw Exception(error)

        return@runCatching data
    }.onFailure {
        // A caller that was cancelled has not had its request fail, and is
        // not to be handed a result to show as an error.
        if (it is CancellationException) currentCoroutineContext().ensureActive()
    }

    suspend fun getArtistById(
        id: Int,
        page: Int? = null
    ): Result<SearchListResult> = executeRequest(
        request = "view_modules_by_artistid",
        additionalParams = mapOf("query" to id, "page" to page)
    )

    suspend fun getArtistSearch(
        query: String,
        page: Int? = null
    ): Result<ArtistResult> = executeRequest(
        request = "search_artist",
        additionalParams = mapOf("query" to query, "page" to page)
    )

    suspend fun getModuleById(id: Int): Result<ModuleResult> = executeRequest(
        request = "view_by_moduleid",
        additionalParams = mapOf("query" to id)
    )

    suspend fun getRandomModule(): Result<ModuleResult> = executeRequest(
        request = "random"
    )

    suspend fun searchByFileNameOrTitle(
        query: String,
        page: Int? = null
    ): Result<SearchListResult> = executeRequest(
        request = "search",
        additionalParams = mapOf(
            "type" to "filename_or_songtitle",
            "query" to query,
            "page" to page
        )
    )
}
