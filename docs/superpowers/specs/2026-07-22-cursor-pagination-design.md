# Cursor pagination (learning exercise) — Design

**Date:** 2026-07-22
**Status:** Approved (brainstorm)
**Scope:** A backend-only learning exercise (`AccessControllerServer`, Ktor + MongoDB) to understand
**cursor (keyset) pagination** and *why* it avoids the duplicate/skip problem that `OFFSET` pagination
suffers when rows are inserted between page requests. Android side is out of scope.

## Goal

Build a synthetic, high-row-count collection plus **two** pagination endpoints — one naïve `OFFSET`
(page-number) endpoint and one **cursor** endpoint — and a manual "churn" endpoint that inserts fresh
rows on demand. Paginating with `OFFSET` while injecting inserts reproduces duplicates; paginating with
the cursor does not. The contrast is the lesson.

## Core idea (the concept being learned)

- `OFFSET` pagination (`skip(n).limit(k)`) anchors on a **position**. When rows are inserted above the
  current page, every position shifts down, so the next page re-includes rows already seen → **duplicates**
  (and skips). It also gets **slower with depth**: `skip` must walk and discard the first `n` entries.
- **Cursor pagination** anchors on a **value** — the sort key of the last row returned, here
  `(createdAtEpochMs, _id)`. The next page asks for rows *strictly after that value* in the sort order.
  Inserts above the anchor don't move the anchor → **no duplicates/skips**, and it's **O(log n + k)**
  regardless of depth (a direct seek into the index, not a scan-and-discard).

## Section 1 — Data model & collection

New MongoDB document in `db/Documents.kt`:

```kotlin
@Serializable
data class FeedItemDoc(
    @SerialName("_id") val id: String,   // UUID
    val seq: Int,                        // human-readable 1..N — lets you eyeball duplicates
    val label: String,                   // e.g. "Item #42"
    val createdAtEpochMs: Long,
)
```

New collection `feedItems` in `MongoStorage`.

**Seed** (at startup, like `seedIfEmpty`): ~**1000** documents, `seq` 1..1000, `createdAtEpochMs`
**increasing with `seq`** (e.g. `base + seq*1000`). Feed sort = `createdAtEpochMs` **DESC** → newest
(highest `seq`) first. `seq` is purely a debugging aid; a real app wouldn't need it.

**Index** `{ createdAtEpochMs: -1, _id: -1 }` on the collection. Cursor pagination relies on an **indexed
sort**: the index keeps entries pre-sorted, so MongoDB can *seek* to the cursor position and read the next
`k` entries in order without an in-memory sort (which Mongo caps at 100 MB anyway). The index must match
the sort order exactly. An index is persisted metadata MongoDB maintains automatically on every write —
not a collection you fill.

## Section 2 — The cursor

**What it encodes:** the sort key of the last row returned — `(createdAtEpochMs, _id)`. It is the anchor:
"give me rows strictly after this one, in sort order".

**The `_id` tie-breaker.** The primary sort is `createdAtEpochMs` DESC, but several rows can share the same
`createdAtEpochMs`. Sorting/filtering on the timestamp alone would either skip same-timestamp rows
(`< ts`) or re-return them (`<= ts`). Adding `_id` as a secondary sort makes the ordering **total and
stable**, so the "after the cursor" predicate is exact. `_id` order is arbitrary (random UUIDs) but
**immutable** — all the tie-breaker needs is a deterministic, stable order, *not* a time-meaningful one.

**The predicate (DESC).** "Strictly after the cursor" =

```
createdAtEpochMs < cursorTs
   OR (createdAtEpochMs == cursorTs AND _id < cursorId)
```

In MongoDB:

```kotlin
Filters.or(
    Filters.lt("createdAtEpochMs", cursorTs),
    Filters.and(
        Filters.eq("createdAtEpochMs", cursorTs),
        Filters.lt("_id", cursorId),
    ),
)
```

**Why `<` (not `!=`) on `_id`:** a cursor marks a *position* in the total order, not "one item to exclude".
Because we **sort by the same key we filter by** (`_id` DESC), within a same-timestamp group the already
returned rows are exactly those with `_id > cursorId`, and the not-yet-seen ones are exactly those with
`_id < cursorId`. `!=` would re-include the already-seen rows above the cursor → duplicates. The comparison
operator must match the sort direction (DESC → `<`; ASC → `>`).

**Opaque base64 encoding** (in a new `api/CursorCodec.kt`, isolated so it's unit-testable):

```kotlin
fun encodeCursor(ts: Long, id: String): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString("$ts:$id".toByteArray())

fun decodeCursor(cursor: String): Pair<Long, String> {
    val raw = String(Base64.getUrlDecoder().decode(cursor))   // "ts:id"
    val (ts, id) = raw.split(":", limit = 2)                  // UUIDs have no ':' → safe
    return ts.toLong() to id
}
```

The client treats `nextCursor` as an opaque token and passes it back unchanged.

**Flow:** page 1 has no cursor (just `sort DESC, limit`). The response returns `nextCursor =
encodeCursor(lastItem.createdAtEpochMs, lastItem.id)`, or `null` when fewer than `limit` rows come back
(last page). The next page passes that cursor.

## Section 3 — Endpoints

Unauthenticated (learning endpoints, easy `curl`). Grouped in a `feedRoutes` wired into `apiRoutes`.

**DTOs** (`api/Dto.kt`):

```kotlin
@Serializable data class FeedItemDto(val id: String, val seq: Int, val label: String, val createdAtEpochMs: Long)
@Serializable data class OffsetPageResponse(val items: List<FeedItemDto>, val page: Int, val nextPage: Int)
@Serializable data class CursorPageResponse(val items: List<FeedItemDto>, val nextCursor: String?)
```

**1. `GET /feed/offset?limit=&page=`** — naïve page-number pagination (reproduces the bug):

```kotlin
val limit = params["limit"]?.toIntOrNull()?.coerceIn(1, 100) ?: 5
val page  = params["page"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1   // 1-based
val offset = (page - 1) * limit                                     // page 1 → skip 0, page 2 → skip limit
val items = feedItems.find()
    .sort(Sorts.descending("createdAtEpochMs", "_id"))
    .skip(offset).limit(limit).toList()
call.respond(OffsetPageResponse(items.map { it.toDto() }, page = page, nextPage = page + 1))
```

**2. `GET /feed/cursor?limit=&cursor=`** — cursor pagination (the fix):

```kotlin
val limit = params["limit"]?.toIntOrNull()?.coerceIn(1, 100) ?: 5
val cursor = params["cursor"]                                       // null on page 1
val base = feedItems.find()
val filtered = if (cursor == null) base else {
    val (ts, id) = decodeCursor(cursor)
    base.filter(Filters.or(
        Filters.lt("createdAtEpochMs", ts),
        Filters.and(Filters.eq("createdAtEpochMs", ts), Filters.lt("_id", id)),
    ))
}
val items = filtered.sort(Sorts.descending("createdAtEpochMs", "_id")).limit(limit).toList()
val next = if (items.size < limit) null
           else items.last().let { encodeCursor(it.createdAtEpochMs, it.id) }
call.respond(CursorPageResponse(items.map { it.toDto() }, nextCursor = next))
```

**3. `POST /feed/simulate-inserts?count=N`** — the churn: inserts `N` fresh rows with `createdAt = now`
(so they land on top of the feed):

```kotlin
val count = params["count"]?.toIntOrNull()?.coerceIn(1, 1000) ?: 5
val now = System.currentTimeMillis()
val maxSeq = feedItems.find().sort(Sorts.descending("seq")).limit(1).firstOrNull()?.seq ?: 0
val newDocs = (1..count).map { i ->
    FeedItemDoc(UUID.randomUUID().toString(), maxSeq + i, "Item #${maxSeq + i}", now + i)
}
feedItems.insertMany(newDocs)
call.respond(mapOf("inserted" to count))
```

**Startup:** `setupFeedItems(storage)` — creates the compound index (idempotent) and seeds 1000 rows if the
collection is empty. Called from `Application.module()`.

**Files touched:** `db/Documents.kt` (`FeedItemDoc`), `db/MongoStorage.kt` (`feedItems` + `setupFeedItems`
/`seedFeedIfEmpty`), `api/Dto.kt` (3 DTOs), new `api/CursorCodec.kt`, `api/Routing.kt` (`feedRoutes`),
`Application.kt` (call `setupFeedItems`).

## Section 4 — curl demonstration

Server up (seed 1000, so `#1000` on top). `limit=5`.

**A — reproduce the OFFSET bug:**

```bash
B=http://localhost:8080
curl -s "$B/feed/offset?limit=5&page=1" | jq '[.items[].seq]'   # → [1000,999,998,997,996]
curl -s -X POST "$B/feed/simulate-inserts?count=3"              # inserts #1001,#1002,#1003 on top
curl -s "$B/feed/offset?limit=5&page=2" | jq '[.items[].seq]'   # → [998,997,996,995,994]
#                                                                     998,997,996 = DUPLICATES of page 1
```

The 3 inserts shifted everything down; page 2 (`skip 5`) now overlaps page 1.

**B — the CURSOR fix:**

```bash
RESP=$(curl -s "$B/feed/cursor?limit=5")
echo "$RESP" | jq '[.items[].seq]'                             # → [1000,999,998,997,996]
CUR=$(echo "$RESP" | jq -r '.nextCursor')
echo "$CUR" | base64 -d                                        # → "<createdAt of #996>:<_id of #996>"
curl -s -X POST "$B/feed/simulate-inserts?count=3"             # same churn
curl -s "$B/feed/cursor?limit=5&cursor=$CUR" | jq '[.items[].seq]'  # → [995,994,993,992,991]  no overlap
```

The cursor anchors on `#996`'s `(createdAt, _id)`; the 3 new rows (`createdAt = now`, above the anchor)
don't match the page. No duplicates, no matter how much churn is injected between pages.

`explain("executionStats")` on the cursor query should show an **`IXSCAN`** (index used), not a `COLLSCAN`.

## Section 5 — Testing

The backend has no Mongo test harness (live Atlas), so we test the pure logic and verify behavior via the
reproducible curl demo.

- **Unit test `CursorCodec`** (`api/CursorCodecTest.kt`, JUnit + kotlin-test): `encode`→`decode`
  round-trips; `decode` of a hand-built base64 token; an id with no colon (UUID) is preserved. This is the
  brick whose bug would break everything (bad decode → wrong filter → duplicates).
- **Behavioral verification = the Section 4 curl demo** — deterministic (manual churn), reproducible:
  OFFSET shows overlapping `seq`s after churn, cursor does not. Optionally confirm `IXSCAN` via `explain`.
- **Full integration test — deferred (out of scope):** would need a test Mongo (Testcontainers or embedded
  flapdoodle); the curl demo covers behavior for this learning exercise.

## Out of scope

Android/client changes; `updatedAt`-mutable-key cursoring (bumping existing rows); a background auto-insert
job; TURN/auth on the feed endpoints; a Testcontainers integration harness.
