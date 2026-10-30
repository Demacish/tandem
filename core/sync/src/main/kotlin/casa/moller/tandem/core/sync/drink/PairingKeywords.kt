package casa.moller.tandem.core.sync.drink

import casa.moller.tandem.core.database.TandemDatabaseFactory
import casa.moller.tandem.core.database.entity.KeywordEntity
import casa.moller.tandem.core.model.drink.DrinkKeywords
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.network.dto.KeywordDto
import casa.moller.tandem.core.sync.account.ActiveSource
import casa.moller.tandem.core.sync.remote.RemoteCaller
import javax.inject.Inject
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Fas S (drycker lane): finds or creates the keywords of drinks on the server, used by the outbox
 * senders (`RECIPE_KEYWORDS` pairing fields, the `drink` CREATE). Tandoor's `POST /api/keyword/`
 * is a get-or-create by name (case ignored) that ignores `parent`, so a child is POSTed and then
 * moved (`PUT /api/keyword/{id}/move/{parent}/`, FACTS "Fas S c"). The roots are created with
 * their stable Swedish names ([DrinkKeywords.DRINK], [DrinkKeywords.PAIRS_WITH]) unless the
 * account already has one (also an English variant, docs/BYGGE.md "Drycker"). Every keyword the
 * server answers is stored at once, so the drinks index knows it before the next keyword read.
 *
 * A keyword with the name that already sits under ANOTHER parent is used as it is (never moved
 * away from where the household put it); one at the root is moved under its parent.
 *
 * Every keyword comes back with the name the SERVER gave it ([KeywordRef]: the stored root's own
 * name, the POST answer's): a nested keyword with another name would rename it for the whole space
 * (Fas S review, fix-data), so the senders never send a name of their own.
 */
internal class PairingKeywords @Inject constructor(private val databases: TandemDatabaseFactory, private val caller: RemoteCaller) {
    /** The pairing keyword [name] under "Passar med". */
    suspend fun pairing(remote: ActiveSource.Remote, name: String): Outcome<KeywordRef> = child(remote, name, PAIRS_WITH)

    /** The drink type [name] under "Dryck". */
    suspend fun type(remote: ActiveSource.Remote, name: String): Outcome<KeywordRef> = child(remote, name, DRINK)

    /** The root keyword of the drinks ("Dryck", or the household's own "Drycker"/"Beverages"). */
    suspend fun drinkRoot(remote: ActiveSource.Remote): Outcome<KeywordRef> = root(remote, DRINK, useStored = true)

    /** A root keyword: the stored one (unless [useStored] is false, after the server did not know it), or created. */
    private suspend fun root(remote: ActiveSource.Remote, root: Root, useStored: Boolean): Outcome<KeywordRef> {
        val stored = databases.get(remote.kind).drinkDraftDao().getKeywords(remote.accountId)
            .filter { it.parentId == null && root.matches(it.name) && it.id >= 0 }
            .minByOrNull { it.id }
        if (useStored && stored != null) return Outcome.Success(KeywordRef(stored.id, stored.name))
        return when (val created = create(remote, stored?.name ?: root.name)) {
            is Outcome.Failure -> created
            is Outcome.Success -> Outcome.Success(created.value.ref())
        }
    }

    private suspend fun child(remote: ActiveSource.Remote, name: String, root: Root): Outcome<KeywordRef> {
        var rootId = when (val found = root(remote, root, useStored = true)) {
            is Outcome.Failure -> return found
            is Outcome.Success -> found.value.id
        }
        val keyword = when (val created = create(remote, name.trim())) {
            is Outcome.Failure -> return created
            is Outcome.Success -> created.value
        }
        val id = keyword.id!!
        if (keyword.parent != null) return Outcome.Success(keyword.ref())
        val body = buildJsonObject { put("name", keyword.name) }
        var moved = caller.write(remote) { drinks.moveKeyword(id, rootId, body) }
        if (moved is Outcome.Failure && moved.error == TandemError.NotFound) {
            // The stored root was deleted on the web: create it again (by name) and move once more.
            rootId = when (val again = root(remote, root, useStored = false)) {
                is Outcome.Failure -> return again
                is Outcome.Success -> again.value.id
            }
            moved = caller.write(remote) { drinks.moveKeyword(id, rootId, body) }
        }
        return when (moved) {
            is Outcome.Failure -> moved

            is Outcome.Success -> {
                store(remote, keyword.copy(parent = rootId))
                Outcome.Success(keyword.ref())
            }
        }
    }

    /** `POST /api/keyword/ {name}` (get-or-create), stored locally. */
    private suspend fun create(remote: ActiveSource.Remote, name: String): Outcome<KeywordDto> {
        val body: JsonObject = buildJsonObject {
            put("name", name)
            put("description", "")
        }
        return when (val sent = caller.write(remote) { drinks.createKeyword(body) }) {
            is Outcome.Failure -> sent

            is Outcome.Success -> {
                val keyword = sent.value
                if (keyword.id == null) return Outcome.Failure(TandemError.Decode("keyword-without-id"))
                store(remote, keyword)
                Outcome.Success(keyword)
            }
        }
    }

    private suspend fun store(remote: ActiveSource.Remote, keyword: KeywordDto) {
        val id = keyword.id ?: return
        databases.get(remote.kind).drinkDraftDao().upsertKeywords(
            listOf(
                KeywordEntity(
                    accountId = remote.accountId,
                    id = id,
                    name = keyword.name.ifBlank { keyword.label },
                    description = keyword.description,
                    parentId = keyword.parent,
                    fullName = keyword.fullName.takeIf { it.isNotBlank() },
                ),
            ),
        )
    }

    /** The keyword as the server answered it: its id and ITS name. */
    private fun KeywordDto.ref(): KeywordRef = KeywordRef(id!!, name.ifBlank { label })

    /** A root of the drinks' keywords: its name when Tandem creates it, and which stored names count as it. */
    private class Root(val name: String, val matches: (String) -> Boolean)

    private companion object {
        val DRINK = Root(DrinkKeywords.DRINK, DrinkKeywords::isDrinkRoot)
        val PAIRS_WITH = Root(DrinkKeywords.PAIRS_WITH, DrinkKeywords::isPairsWithRoot)
    }
}

/** Fas S review (fix-data): a keyword as the server has it (id and name), to nest in a recipe write unchanged. */
internal data class KeywordRef(val id: Long, val name: String)
