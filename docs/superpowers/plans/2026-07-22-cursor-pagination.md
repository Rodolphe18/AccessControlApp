# Cursor pagination (learning exercise) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax.
>
> **Git note:** the repo owner commits/pushes. Do NOT run `git commit`/`git push`. "Checkpoint" = tell the owner it's ready to commit.

**Goal:** A backend-only demo (Ktor + MongoDB) that contrasts naïve `OFFSET` (page-number) pagination with cursor (keyset) pagination, showing that cursor pagination avoids duplicates when rows are inserted between page requests.

**Architecture:** A synthetic `feed_items` collection (seeded with 1000 rows, compound index `{createdAtEpochMs:-1,_id:-1}`), three unauthenticated endpoints (`/feed/offset` by page, `/feed/cursor` by opaque base64 cursor, `/feed/simulate-inserts` to inject churn), and an isolated `CursorCodec` for the base64 encode/decode.

**Tech Stack:** Kotlin, Ktor 3.5.1, MongoDB Kotlin coroutine driver, kotlinx.serialization; tests JUnit + kotlin-test.

**Spec:** `docs/superpowers/specs/2026-07-22-cursor-pagination-design.md`

---

## File structure

**All in `AccessControllerServer`** (package `dev.rodolphe.accesscontrol`):
- Create `api/CursorCodec.kt` — `encodeCursor`/`decodeCursor` (opaque base64). Isolated → unit-testable.
- Create `src/test/.../api/CursorCodecTest.kt` — codec unit tests.
- Modify `db/Documents.kt` — add `FeedItemDoc`.
- Modify `db/Mongo.kt` — add the `feedItems` collection + `setupFeedItems()` (index + seed).
- Modify `api/Dto.kt` — `FeedItemDto`, `OffsetPageResponse`, `CursorPageResponse`.
- Modify `api/Routing.kt` — `feedRoutes(storage)` (3 routes) + `toDto` mapper, wired into `apiRoutes`.
- Modify `Application.kt` — call `storage.setupFeedItems()` at boot.

---

## Task 1: `CursorCodec` (opaque base64 cursor), TDD

**Files:** Create `src/main/kotlin/dev/rodolphe/accesscontrol/api/CursorCodec.kt`; Test `src/test/kotlin/dev/rodolphe/accesscontrol/api/CursorCodecTest.kt`.

- [ ] **Step 1: Write the failing test `CursorCodecTest.kt`.**

```kotlin
package dev.rodolphe.accesscontrol.api

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals

class CursorCodecTest {

    @Test fun `encode then decode round-trips`() {
        val cursor = encodeCursor(1784400000000L, "abc-123")
        assertEquals(1784400000000L to "abc-123", decodeCursor(cursor))
    }

    @Test fun `decode reads a known base64 token`() {
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString("1000:xyz".toByteArray())
        assertEquals(1000L to "xyz", decodeCursor(token))
    }

    @Test fun `uuid id (dashes, no colon) is preserved`() {
        val (ts, id) = decodeCursor(encodeCursor(42L, "9f8e-7d6c-5b4a"))
        assertEquals(42L, ts)
        assertEquals("9f8e-7d6c-5b4a", id)
    }
}
```

- [ ] **Step 2: Run it to verify it fails.** `./gradlew test --tests "*CursorCodecTest"` → FAIL (`encodeCursor`/`decodeCursor` unresolved).

- [ ] **Step 3: Create `CursorCodec.kt`.**

```kotlin
package dev.rodolphe.accesscontrol.api

import java.util.Base64

/**
 * The pagination cursor is the sort key of the last row returned — (createdAtEpochMs, _id) — encoded as
 * an opaque URL-safe base64 token "ts:id". The client passes it back unchanged; it must not parse it.
 */
fun encodeCursor(createdAtEpochMs: Long, id: String): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString("$createdAtEpochMs:$id".toByteArray())

fun decodeCursor(cursor: String): Pair<Long, String> {
    val raw = String(Base64.getUrlDecoder().decode(cursor)) // "ts:id"
    val (ts, id) = raw.split(":", limit = 2)                // UUIDs use '-', never ':' → safe
    return ts.toLong() to id
}
```

- [ ] **Step 4: Run the test to verify it passes.** `./gradlew test --tests "*CursorCodecTest"` → PASS (3 tests).

- [ ] **Step 5: Checkpoint.**

---

## Task 2: Data layer — `FeedItemDoc`, collection, index, seed

**Files:** Modify `db/Documents.kt`, `db/Mongo.kt`.

- [ ] **Step 1: Add `FeedItemDoc` to `db/Documents.kt`** (at the end of the file):

```kotlin
/**
 * A synthetic feed row for the cursor-pagination exercise. [seq] is a human-readable 1..N counter, only
 * to eyeball duplicates in responses; a real feed wouldn't need it. [createdAtEpochMs] is the sort key,
 * paired with `_id` as the tie-breaker.
 */
@Serializable
data class FeedItemDoc(
    @SerialName("_id") val id: String,
    val seq: Int,
    val label: String,
    val createdAtEpochMs: Long,
)
```

- [ ] **Step 2: Add the `feedItems` collection to `MongoStorage` in `db/Mongo.kt`** (next to the other `val`s, after `pinCodes`):

```kotlin
    val feedItems = db.getCollection<FeedItemDoc>("feed_items")
```

- [ ] **Step 3: Add `setupFeedItems()` to `MongoStorage`** (a new method after `seedIfEmpty()`). Add the imports `import com.mongodb.client.model.Indexes` and `import java.util.UUID` at the top of `Mongo.kt`.

```kotlin
    /**
     * Ensures the compound index the cursor query relies on (idempotent — createIndex is a no-op if it
     * already exists), then seeds 1000 feed rows if the collection is empty. The index MUST match the
     * feed's sort order (createdAtEpochMs DESC, _id DESC) so the cursor query is an index seek, not a scan.
     */
    suspend fun setupFeedItems() {
        feedItems.createIndex(Indexes.descending("createdAtEpochMs", "_id"))
        if (feedItems.find().firstOrNull() != null) return
        val base = 1_700_000_000_000L // arbitrary fixed epoch; createdAt grows with seq
        val docs = (1..1000).map { seq ->
            FeedItemDoc(
                id = UUID.randomUUID().toString(),
                seq = seq,
                label = "Item #$seq",
                createdAtEpochMs = base + seq * 1000L,
            )
        }
        feedItems.insertMany(docs)
    }
```

- [ ] **Step 4: Compile.** `./gradlew compileKotlin` → `BUILD SUCCESSFUL`.

- [ ] **Step 5: Checkpoint.**

---

## Task 3: DTOs + `feedRoutes` + wiring

**Files:** Modify `api/Dto.kt`, `api/Routing.kt`, `Application.kt`.

- [ ] **Step 1: Add the DTOs to `api/Dto.kt`** (at the end):

```kotlin
@Serializable
data class FeedItemDto(val id: String, val seq: Int, val label: String, val createdAtEpochMs: Long)

@Serializable
data class OffsetPageResponse(val items: List<FeedItemDto>, val page: Int, val nextPage: Int)

@Serializable
data class CursorPageResponse(val items: List<FeedItemDto>, val nextCursor: String?)
```

- [ ] **Step 2: Add imports to `api/Routing.kt`** if missing: `import com.mongodb.client.model.Sorts` and `import dev.rodolphe.accesscontrol.db.FeedItemDoc`. (`Filters`, `firstOrNull`, `toList`, `HttpStatusCode`, `respond`, `get`, `post`, `Route`, and `import java.util.UUID` — add `UUID` too.)

- [ ] **Step 3: Add `feedRoutes` + the mapper at the bottom of `api/Routing.kt`.**

```kotlin
private fun FeedItemDoc.toDto() = FeedItemDto(id, seq, label, createdAtEpochMs)

private fun Route.feedRoutes(storage: MongoStorage) {
    // Naïve OFFSET pagination by page number — reproduces the duplicate bug under churn.
    get("/feed/offset") {
        val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 100) ?: 5
        val page = call.request.queryParameters["page"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val offset = (page - 1) * limit
        val items = storage.feedItems.find()
            .sort(Sorts.descending("createdAtEpochMs", "_id"))
            .skip(offset).limit(limit).toList()
        call.respond(OffsetPageResponse(items.map { it.toDto() }, page = page, nextPage = page + 1))
    }

    // Cursor (keyset) pagination — anchored on the last row's (createdAtEpochMs, _id), immune to inserts.
    get("/feed/cursor") {
        val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 100) ?: 5
        val cursor = call.request.queryParameters["cursor"]
        val find = if (cursor == null) {
            storage.feedItems.find()
        } else {
            val (ts, id) = decodeCursor(cursor)
            storage.feedItems.find(
                Filters.or(
                    Filters.lt("createdAtEpochMs", ts),
                    Filters.and(Filters.eq("createdAtEpochMs", ts), Filters.lt("_id", id)),
                ),
            )
        }
        val items = find.sort(Sorts.descending("createdAtEpochMs", "_id")).limit(limit).toList()
        val next = if (items.size < limit) null
        else items.last().let { encodeCursor(it.createdAtEpochMs, it.id) }
        call.respond(CursorPageResponse(items.map { it.toDto() }, nextCursor = next))
    }

    // Churn: insert N fresh rows (createdAt = now) so they land on top of the feed.
    post("/feed/simulate-inserts") {
        val count = call.request.queryParameters["count"]?.toIntOrNull()?.coerceIn(1, 1000) ?: 5
        val now = System.currentTimeMillis()
        val maxSeq = storage.feedItems.find().sort(Sorts.descending("seq")).limit(1).firstOrNull()?.seq ?: 0
        val docs = (1..count).map { i ->
            FeedItemDoc(UUID.randomUUID().toString(), maxSeq + i, "Item #${maxSeq + i}", now + i)
        }
        storage.feedItems.insertMany(docs)
        call.respond(mapOf("inserted" to count))
    }
}
```

- [ ] **Step 4: Wire `feedRoutes` into `apiRoutes`.** In `fun Route.apiRoutes(...)`, add a line after `signalingRoute(...)`:

```kotlin
    feedRoutes(storage)
```

- [ ] **Step 5: Call `setupFeedItems()` at boot in `Application.kt`.** Find the `runBlocking { storage.seedIfEmpty() }` line and extend it:

```kotlin
    runBlocking {
        storage.seedIfEmpty()
        storage.setupFeedItems()
    }
```

(If `seedIfEmpty()` is already inside a `runBlocking { }`, just add the `storage.setupFeedItems()` line after it.)

- [ ] **Step 6: Compile.** `./gradlew compileKotlin` → `BUILD SUCCESSFUL`.

- [ ] **Step 7: Checkpoint.**

---

## Task 4: End-to-end demonstration (manual, owner restarts the server)

Owner restarts the Ktor server so the seed + routes load. `limit=5`. Uses `jq` (or read raw JSON).

- [ ] **Step 1: Confirm the seed & first page.**

```bash
B=http://localhost:8080
curl -s "$B/feed/offset?limit=5&page=1" | jq '[.items[].seq]'
```
Expected: `[1000, 999, 998, 997, 996]`.

- [ ] **Step 2: Reproduce the OFFSET bug.**

```bash
curl -s "$B/feed/offset?limit=5&page=1" | jq '[.items[].seq]'   # [1000,999,998,997,996]
curl -s -X POST "$B/feed/simulate-inserts?count=3"              # {"inserted":3}
curl -s "$B/feed/offset?limit=5&page=2" | jq '[.items[].seq]'   # [998,997,996,995,994]
```
Expected: page 2 contains `998,997,996` again → **duplicates** of page 1 (the 3 inserts shifted everything down).

- [ ] **Step 3: Verify the CURSOR has no duplicates under the same churn.**

```bash
RESP=$(curl -s "$B/feed/cursor?limit=5")
echo "$RESP" | jq '[.items[].seq]'                             # [1000,999,998,997,996]
CUR=$(echo "$RESP" | jq -r '.nextCursor')
echo "$CUR" | base64 -d; echo                                  # "<createdAt of #996>:<_id of #996>"
curl -s -X POST "$B/feed/simulate-inserts?count=3"
curl -s "$B/feed/cursor?limit=5&cursor=$CUR" | jq '[.items[].seq]'   # [995,994,993,992,991]
```
Expected: page 2 = `995,994,993,992,991` → **no overlap** with page 1, and the 3 new rows don't appear.

- [ ] **Step 4 (optional): Confirm the index is used.** In `mongosh`:

```js
db.feed_items.find({ createdAtEpochMs: { $lt: 1700001000000 } })
  .sort({ createdAtEpochMs: -1, _id: -1 }).limit(5).explain("executionStats")
```
Expected: `winningPlan.stage` (or a child stage) is `IXSCAN` (index used), not `COLLSCAN`.

- [ ] **Step 5: Checkpoint** — exercise done; ready to commit.

---

## Self-review notes

- **Spec coverage:** data model + collection + index + seed (T2), the cursor incl. `_id` tie-breaker + `$or` + base64 codec (T1 codec, T3 query), offset(page)/cursor/simulate-inserts endpoints (T3), curl demo incl. the OFFSET-duplicate vs cursor contrast + `IXSCAN` check (T4), codec unit tests (T1). All spec sections mapped.
- **Type consistency:** `FeedItemDoc(id, seq, label, createdAtEpochMs)` (with `@SerialName("_id")`) is used identically in the seed, `simulate-inserts`, and `toDto`. `FeedItemDto`/`OffsetPageResponse(items, page, nextPage)`/`CursorPageResponse(items, nextCursor)` match Task 3's responses. `encodeCursor(createdAtEpochMs, id)`/`decodeCursor(cursor): Pair<Long, String>` match between the codec, the cursor endpoint, and the tests. The `$or` filter uses `Filters.lt`/`eq`/`and`/`or` with the sort `Sorts.descending("createdAtEpochMs", "_id")` — same order as the index `Indexes.descending("createdAtEpochMs", "_id")`.
- **Deferred (out of scope, per spec):** `updatedAt`-mutable cursoring, background auto-insert job, auth on feed endpoints, Testcontainers integration harness, Android client.
```
