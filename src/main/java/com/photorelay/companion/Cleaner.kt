package com.photorelay.companion

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore

data class LocalPhoto(val uri: Uri, val id: Long, val name: String, val size: Long, val takenMs: Long, val modified: Long)

data class CleanResult(
    val scanned: Int = 0,
    val oldEnough: Int = 0,
    val hashed: Int = 0,
    val inGooglePhotos: Int = 0,
    val inGooglePhotosBytes: Long = 0,
    val deleted: Int = 0,
    val freedBytes: Long = 0,
    val notYetBackedUp: Int = 0,
    /** 구글포토에는 있지만 S7이 OneDrive에서 받은 사진이 아니라서 남긴 것 ("S7이 받은 사진만" 조건) */
    val notRelayed: Int = 0,
    val timedOut: Boolean = false,
    val preview: Boolean = true,
    val noAlbums: Boolean = false,
    /** 지문을 못 읽은 파일 수 (권한 부족, 읽기 오류). 0이 아니면 원인을 보여 줘야 한다 */
    val hashFailed: Int = 0,
    /** 작업 중 시스템이 멈춰 달라고 해서(충전 끊김 등) 중간에 그만둔 경우 */
    val stopped: Boolean = false,
) {
    fun text(): String {
        if (noAlbums) return "정리할 앨범을 먼저 고르세요"
        val gb = "%.2fGB".format((if (preview) inGooglePhotosBytes else freedBytes) / 1073741824.0)
        val more = (if (notRelayed > 0) " · S7 경유 아님 ${notRelayed}개 보존" else "") +
            (if (hashFailed > 0) " · 지문 못 읽음 ${hashFailed}개 (사진 위치 권한 확인)" else "") +
            (if (timedOut) " · 지문 계산은 다음 실행에서 이어서" else "") +
            (if (stopped) " · 중단됨, 다음 실행에서 이어서" else "")
        return if (preview) "미리보기: 지울 수 있음 ${inGooglePhotos}개($gb) · 아직 백업 안 됨 ${notYetBackedUp}개$more"
        else "정리: ${deleted}개 삭제($gb 확보) · 아직 백업 안 됨 ${notYetBackedUp}개$more"
    }
}

data class Album(val path: String, val label: String, val count: Int, val bytes: Long)

/** 같은 파일을 매번 다시 해시하지 않도록 (MediaStore id + 크기 + 수정시각) → 지문 저장 */
private class HashCache(context: Context) : SQLiteOpenHelper(context, "hash.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) =
        db.execSQL("CREATE TABLE h (id INTEGER PRIMARY KEY, size INTEGER, modified INTEGER, k TEXT)")

    override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) = Unit

    fun get(p: LocalPhoto): String? = readableDatabase.rawQuery(
        "SELECT k FROM h WHERE id=? AND size=? AND modified=?", arrayOf(p.id.toString(), p.size.toString(), p.modified.toString()),
    ).use { if (it.moveToFirst()) it.getString(0) else null }

    fun put(p: LocalPhoto, k: String) {
        writableDatabase.insertWithOnConflict("h", null, ContentValues().apply {
            put("id", p.id); put("size", p.size); put("modified", p.modified); put("k", k)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }
}

/**
 * 메인폰 정리: 갤러리 사진의 지문을 S7에 물어서, 구글포토에 원본 화질로 확인된 것만 지운다.
 * OneDrive 앱의 "저장용량 확보" 버튼을 대신하되, 기준은 "OneDrive 업로드"가 아니라 "구글포토 확인"이라 더 안전하다.
 */
class Cleaner(
    private val ctx: Context,
    private val progress: (String) -> Unit = {},
    /** WorkManager가 멈추라고 하면(충전 끊김, 시간 제한) true — 긴 반복 중에 확인한다 */
    private val shouldStop: () -> Boolean = { false },
) {
    private val resolver: ContentResolver = ctx.contentResolver
    private val prefs = CompanionPrefs(ctx)
    private val cache = HashCache(ctx)

    fun run(budgetMs: Long = 8 * 60 * 1000L): CleanResult {
        check(prefs.paired) { "S7과 연결되지 않음" }
        // 원본 바이트(GPS 포함)로 지문을 내야 구글포토와 맞는다. 이 권한이 없으면 전부 못 읽으므로 미리 알린다.
        check(ctx.checkSelfPermission(android.Manifest.permission.ACCESS_MEDIA_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            "사진 위치 정보 권한이 없어 지문을 낼 수 없음 (앱 권한에서 허용)"
        }
        val started = System.currentTimeMillis()
        progress("S7 연결 확인")
        val client = connect()

        val real = prefs.realDelete
        val albums = prefs.albums
        if (albums.isEmpty()) {
            return CleanResult(preview = !real, noAlbums = true).also { prefs.lastResult = it.text(); prefs.lastRunMs = System.currentTimeMillis() }
        }
        val photos = scan(albums)
        val now = System.currentTimeMillis()
        val old = photos.filter { isOldEnough(it.takenMs, now, prefs.keepDays) }
        var r = CleanResult(scanned = photos.size, oldEnough = old.size, preview = !real)

        // 지문 (캐시 우선, 시간 예산 안에서)
        val keys = LinkedHashMap<LocalPhoto, String>()
        for ((i, p) in old.withIndex()) {
            val cached = cache.get(p)
            if (cached != null) { keys[p] = cached; continue }
            if (System.currentTimeMillis() - started > budgetMs) { r = r.copy(timedOut = true); break }
            if (shouldStop()) { r = r.copy(stopped = true); break }
            progress("지문 계산 ${i + 1}/${old.size}: ${p.name}")
            val k = hash(p.uri)
            if (k == null) { r = r.copy(hashFailed = r.hashFailed + 1); continue }
            cache.put(p, k)
            keys[p] = k
            r = r.copy(hashed = r.hashed + 1)
        }

        progress("S7에 구글포토 확인 요청 (${keys.size}개)")
        val check = client.check(keys.values.distinct()) { done, total, retry ->
            progress("S7에 구글포토 확인 중 $done/$total" + (retry?.let { " — 연결 불안정, 다시 시도 중 ($it)" } ?: ""))
        }
        if (check.heartbeatUrl.isNotEmpty()) prefs.heartbeatUrl = check.heartbeatUrl
        val targets = selectTargets(keys, check.inCloud, check.relayed, prefs.onlyRelayed)
        val inCloudCount = keys.count { it.value in check.inCloud }
        r = r.copy(
            inGooglePhotos = targets.size,
            inGooglePhotosBytes = targets.keys.sumOf { it.size },
            notYetBackedUp = keys.size - inCloudCount,
            notRelayed = inCloudCount - targets.size,
        )

        if (real) {
            for ((i, e) in targets.entries.withIndex()) {
                val (p, k) = e
                if (shouldStop() || System.currentTimeMillis() - started > budgetMs + DELETE_BUDGET_MS) { r = r.copy(stopped = true); break }
                progress("삭제 ${i + 1}/${targets.size}: ${p.name}")
                // 지우기 직전 재확인: 파일 내용이 그대로인지
                if (hash(p.uri) != k) continue
                if (runCatching { resolver.delete(p.uri, null, null) }.getOrDefault(0) > 0) {
                    r = r.copy(deleted = r.deleted + 1, freedBytes = r.freedBytes + p.size)
                }
            }
            prefs.totalDeleted += r.deleted
            prefs.totalFreed += r.freedBytes
        }
        prefs.lastResult = r.text()
        prefs.lastRunMs = System.currentTimeMillis()
        client.report("${deviceName(ctx)}: ${r.text()}")
        return r
    }

    /**
     * 저장된 주소 → 예전에 쓴 주소들 → mDNS → 같은 Wi-Fi 대역 전체 순서로 S7을 찾는다
     * (S7은 장소마다 IP가 바뀐다: 회사 ↔ 집). 그래도 없으면 예외 → 다음 회차.
     */
    private fun connect(): RelayClient {
        fun alive(host: String, port: Int) = runCatching { RelayClient(host, port).info() }.getOrNull()?.optString("app") == "PhotoRelay"
        fun use(host: String, port: Int): RelayClient {
            prefs.host = host
            prefs.port = port
            // 최근 주소 몇 개만 (오래 쓰면 주소가 쌓여 매번 다 두드리게 된다)
            prefs.knownHosts = (listOf(host) + (prefs.knownHosts - host)).take(MAX_KNOWN_HOSTS).toSet()
            return RelayClient(host, port, prefs.token, prefs.secret)
        }
        if (alive(prefs.host, prefs.port)) return use(prefs.host, prefs.port)
        progress("S7 주소 다시 찾는 중")
        prefs.knownHosts.filter { it != prefs.host }.firstOrNull { alive(it, prefs.port) }?.let { return use(it, prefs.port) }
        Discovery.find(ctx).firstOrNull { alive(it.host, it.port) }?.let { return use(it.host, it.port) }
        progress("S7 주소 다시 찾는 중 (Wi-Fi 전체 확인)")
        Discovery.scanSubnet(ctx, prefs.port).firstOrNull()?.let { return use(it.host, it.port) }
        throw java.io.IOException("같은 Wi-Fi에서 S7을 찾지 못함 (저장된 주소 ${prefs.host})")
    }

    /** 사용자가 고른 앨범(폴더)의 사진·영상 */
    private fun scan(albums: Set<String>): List<LocalPhoto> {
        val out = mutableListOf<LocalPhoto>()
        for (col in COLLECTIONS) {
            resolver.query(
                col,
                arrayOf(
                    MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE,
                    MediaStore.MediaColumns.DATE_TAKEN, MediaStore.MediaColumns.DATE_ADDED, MediaStore.MediaColumns.DATE_MODIFIED,
                ),
                "(${albums.joinToString(" OR ") { "${MediaStore.MediaColumns.RELATIVE_PATH} = ?" }}) AND ${MediaStore.MediaColumns.IS_PENDING} = 0",
                albums.toTypedArray(), null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    // "최근 N일 보존"은 폰에 들어온 시각 기준도 본다: 촬영일이 오래된 사진(카톡으로 받은 것 등)이 오자마자 지워지지 않게
                    val taken = recentOf(if (c.isNull(3)) 0 else c.getLong(3), c.getLong(4) * 1000)
                    out += LocalPhoto(ContentUris.withAppendedId(col, id), id, c.getString(1) ?: "", c.getLong(2), taken, c.getLong(5))
                }
            }
        }
        return out
    }

    private fun hash(uri: Uri): String? =
        runCatching { resolver.openInputStream(MediaStore.setRequireOriginal(uri))?.use(DedupKey::of) }.getOrNull()

    /** 폰에 있는 사진·영상 폴더 목록 (앨범 고르기 화면용) */
    fun albums(): List<Album> {
        val agg = HashMap<String, Pair<Int, Long>>()
        for (col in COLLECTIONS) {
            resolver.query(
                col, arrayOf(MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.SIZE),
                "${MediaStore.MediaColumns.IS_PENDING} = 0", null, null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val path = c.getString(0) ?: continue
                    val (n, b) = agg[path] ?: (0 to 0L)
                    agg[path] = (n + 1) to (b + c.getLong(1))
                }
            }
        }
        return agg.map { (path, v) -> Album(path, albumLabel(path), v.first, v.second) }.sortedByDescending { it.count }
    }

    companion object {
        private const val MAX_KNOWN_HOSTS = 5
        private const val DELETE_BUDGET_MS = 90 * 1000L

        /** 촬영 시각과 추가 시각 중 늦은 쪽 (0이나 음수는 없는 값으로 본다) */
        fun recentOf(takenMs: Long, addedMs: Long) = maxOf(if (takenMs > 0) takenMs else 0L, addedMs)

        /** 같은 모델이 두 대라 S7 기록에서 구별되도록 사용자가 정한 기기 이름을 붙인다 */
        fun deviceName(ctx: Context): String {
            val custom = runCatching { android.provider.Settings.Global.getString(ctx.contentResolver, "device_name") }.getOrNull()
            return if (custom.isNullOrBlank() || custom == Build.MODEL) Build.MODEL else "$custom (${Build.MODEL})"
        }

        // 지연 초기화: 순수 함수(selectTargets 등)를 JVM 단위 테스트에서 쓸 때 MediaStore를 건드리지 않도록
        private val COLLECTIONS by lazy {
            listOf(
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            )
        }

        /** 삭제 대상: 구글포토에 원본으로 확인됐고, (조건이 켜져 있으면) S7이 OneDrive에서 받은 기록도 있는 것 */
        fun <P> selectTargets(keys: Map<P, String>, inCloud: Set<String>, relayed: Set<String>, onlyRelayed: Boolean): Map<P, String> =
            keys.filter { (_, k) -> k in inCloud && (!onlyRelayed || k in relayed) }

        /** 갤러리에서 보이는 이름처럼 */
        fun albumLabel(path: String): String {
            val p = path.trimEnd('/')
            return when (p) {
                "DCIM/Camera" -> "카메라"
                "DCIM/Screenshots", "Pictures/Screenshots" -> "스크린샷"
                "Download" -> "다운로드"
                "Movies" -> "동영상"
                else -> p.substringAfterLast('/')
            }
        }

        fun isOldEnough(takenMs: Long, nowMs: Long, keepDays: Int) = nowMs - takenMs >= keepDays * 86_400_000L

        /** 확인 창 없이 지우려면 '모든 파일 접근' 권한이 필요 */
        fun canDeleteSilently() = Environment.isExternalStorageManager()
    }
}
