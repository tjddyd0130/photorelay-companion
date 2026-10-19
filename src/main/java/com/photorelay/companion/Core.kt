package com.photorelay.companion

import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 구글포토 dedup_key = base64url(SHA-1(파일)), 패딩 없음. S7 PhotoRelay와 같은 규칙. */
object DedupKey {
    private val encoder = Base64.getUrlEncoder().withoutPadding()

    fun of(input: InputStream): String {
        val d = MessageDigest.getInstance("SHA-1")
        val buf = ByteArray(1 shl 16)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            d.update(buf, 0, n)
        }
        return encoder.encodeToString(d.digest())
    }
}

class CompanionPrefs(context: Context) {
    private val p = context.getSharedPreferences("companion", Context.MODE_PRIVATE)

    var host: String get() = p.getString("host", "")!!; set(v) = p.edit().putString("host", v).apply()
    var port: Int get() = p.getInt("port", 47321); set(v) = p.edit().putInt("port", v).apply()
    var token: String get() = p.getString("token", "")!!; set(v) = p.edit().putString("token", v).apply()
    var serverName: String get() = p.getString("serverName", "")!!; set(v) = p.edit().putString("serverName", v).apply()
    val paired: Boolean get() = token.isNotBlank()
    /** S7이 지금까지 쓴 주소들 (집·회사처럼 Wi-Fi가 바뀌면 주소도 바뀐다) */
    var knownHosts: Set<String> get() = p.getStringSet("knownHosts", emptySet())!!.toSet(); set(v) = p.edit().putStringSet("knownHosts", v).apply()

    /** 최근 N일 사진은 메인폰에 남긴다 */
    var keepDays: Int get() = p.getInt("keepDays", 3); set(v) = p.edit().putInt("keepDays", v).apply()
    /** 꺼져 있으면 지울 대상만 계산 (첫 실행은 미리보기) */
    var realDelete: Boolean get() = p.getBoolean("realDelete", false); set(v) = p.edit().putBoolean("realDelete", v).apply()
    var autoEnabled: Boolean get() = p.getBoolean("autoEnabled", true); set(v) = p.edit().putBoolean("autoEnabled", v).apply()

    /** 정리할 앨범 (MediaStore RELATIVE_PATH, 예: "DCIM/Camera/"). 비어 있으면 아무것도 지우지 않는다. */
    var albums: Set<String> get() = p.getStringSet("albums", emptySet())!!.toSet(); set(v) = p.edit().putStringSet("albums", v).apply()
    /** OneDrive를 거쳐 S7이 직접 받은 사진만 지운다 (다른 경로로 구글포토에 있는 사진은 건드리지 않음) */
    var onlyRelayed: Boolean get() = p.getBoolean("onlyRelayed", true); set(v) = p.edit().putBoolean("onlyRelayed", v).apply()

    var lastResult: String get() = p.getString("lastResult", "")!!; set(v) = p.edit().putString("lastResult", v).apply()
    var lastRunMs: Long get() = p.getLong("lastRunMs", 0); set(v) = p.edit().putLong("lastRunMs", v).apply()
    var totalDeleted: Long get() = p.getLong("totalDeleted", 0); set(v) = p.edit().putLong("totalDeleted", v).apply()
    /** 마지막 업데이트 확인 결과 */
    var updateStatus: String get() = p.getString("updateStatus", "")!!; set(v) = p.edit().putString("updateStatus", v).apply()
    var totalFreed: Long get() = p.getLong("totalFreed", 0); set(v) = p.edit().putLong("totalFreed", v).apply()

    fun unpair() = p.edit().remove("token").remove("serverName").apply()
}

/** S7 PhotoRelay 짝짓기 서버와 통신 (집 Wi-Fi 안쪽 HTTP) */
class RelayClient(private val host: String, private val port: Int, private val token: String? = null) {
    class RelayException(val code: Int, message: String) : Exception(message)

    private val base get() = "http://$host:$port"

    fun info(): JSONObject = call("GET", "/api/info", null)

    fun pair(code: String, device: String): Pair<String, String> {
        val o = call("POST", "/api/pair", JSONObject().put("code", code).put("device", device))
        return o.getString("token") to o.optString("name")
    }

    /** inCloud: 구글포토에 원본 화질로 있는 지문, relayed: S7이 OneDrive에서 받은 기록이 있는 지문 */
    data class CheckResult(val inCloud: Set<String>, val relayed: Set<String>)

    /** onProgress(확인한 수, 전체, 다시 시도 중이면 몇 번째인지) */
    fun check(keys: List<String>, onProgress: (Int, Int, Int?) -> Unit = { _, _, _ -> }): CheckResult {
        val inCloud = mutableSetOf<String>()
        val relayed = mutableSetOf<String>()
        var done = 0
        keys.chunked(BATCH).forEach { batch ->
            val o = withRetry({ attempt -> onProgress(done, keys.size, attempt) }) {
                call("POST", "/api/check", JSONObject().put("keys", JSONArray(batch)))
            }
            done += batch.size
            onProgress(done, keys.size, null)
            o.getJSONArray("inCloud").let { a -> for (i in 0 until a.length()) inCloud += a.getString(i) }
            // 예전 S7 앱은 relayed를 주지 않는다 → 비어 있으면 "S7이 받은 사진만" 조건에서 아무것도 지우지 않게 됨 (안전한 쪽)
            o.optJSONArray("relayed")?.let { a -> for (i in 0 until a.length()) relayed += a.getString(i) }
        }
        return CheckResult(inCloud, relayed)
    }

    fun report(text: String) = runCatching { call("POST", "/api/report", JSONObject().put("text", text)) }

    /** 네트워크 오류(S7 Wi-Fi 끊김, 시간 초과)는 3번까지 다시 시도. 인증 실패 같은 응답 오류는 바로 실패. */
    private fun <T> withRetry(onRetry: (Int) -> Unit = {}, block: () -> T): T {
        var last: Exception? = null
        for (attempt in 0 until 3) {
            try {
                return block()
            } catch (e: java.io.IOException) {
                last = e
                onRetry(attempt + 1)
                Thread.sleep(listOf(3_000L, 10_000L, 20_000L)[attempt])
            }
        }
        throw last!!
    }

    private fun call(method: String, path: String, body: JSONObject?): JSONObject {
        val req = Request.Builder().url(base + path).apply {
            if (token != null) header("Authorization", "Bearer $token")
            if (body != null) method(method, body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
        }.build()
        http.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            val o = runCatching { JSONObject(text) }.getOrElse { JSONObject() }
            if (!r.isSuccessful) throw RelayException(r.code, o.optString("error").ifEmpty { "S7 응답 ${r.code}" })
            return o
        }
    }

    companion object {
        const val BATCH = 500
        // 첫 확인은 S7이 구글포토 DB를 복사하느라 수십 초 걸릴 수 있다 (S7이 다른 작업 중이면 더)
        private val http = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(300, TimeUnit.SECONDS).build()
    }
}

/** mDNS로 집 안의 S7(_photorelay._tcp) 찾기 */
object Discovery {
    data class Found(val name: String, val host: String, val port: Int)

    fun find(context: Context, timeoutMs: Long = 5000): List<Found> {
        // 화면이 꺼져 있으면 Wi-Fi가 멀티캐스트(mDNS 응답)를 걸러 버린다
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val mlock = wifi.createMulticastLock("PhotoRelayCompanion:nsd").apply { setReferenceCounted(false); acquire() }
        try {
            return findLocked(context, timeoutMs)
        } finally {
            runCatching { mlock.release() }
        }
    }

    private fun findLocked(context: Context, timeoutMs: Long): List<Found> {
        val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
        val found = java.util.concurrent.CopyOnWriteArrayList<Found>()
        val pending = java.util.concurrent.CopyOnWriteArrayList<NsdServiceInfo>()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) { pending += info }
            override fun onDiscoveryStarted(t: String) = Unit
            override fun onDiscoveryStopped(t: String) = Unit
            override fun onServiceLost(info: NsdServiceInfo) = Unit
            override fun onStartDiscoveryFailed(t: String, e: Int) = Unit
            override fun onStopDiscoveryFailed(t: String, e: Int) = Unit
        }
        nsd.discoverServices("_photorelay._tcp", NsdManager.PROTOCOL_DNS_SD, listener)
        Thread.sleep(timeoutMs)
        runCatching { nsd.stopServiceDiscovery(listener) }
        // resolve는 한 번에 하나씩
        for (info in pending) {
            val latch = CountDownLatch(1)
            @Suppress("DEPRECATION")
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(i: NsdServiceInfo, e: Int) { latch.countDown() }
                override fun onServiceResolved(i: NsdServiceInfo) {
                    i.host?.hostAddress?.let { found += Found(i.serviceName, it, i.port) }
                    latch.countDown()
                }
            })
            latch.await(3, TimeUnit.SECONDS)
        }
        return found.distinctBy { it.host to it.port }
    }

    /**
     * mDNS가 막힌 공유기용 마지막 수단: 지금 Wi-Fi 대역(/24)의 모든 주소에 짝짓기 포트로 접속해 본다.
     * 열린 곳만 /api/info로 PhotoRelay인지 확인한다. 254개를 병렬로 확인해 몇 초면 끝난다.
     */
    fun scanSubnet(context: Context, port: Int): List<Found> {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val link = cm.getLinkProperties(cm.activeNetwork) ?: return emptyList()
        val me = link.linkAddresses.map { it.address }.filterIsInstance<java.net.Inet4Address>().firstOrNull() ?: return emptyList()
        val b = me.address
        val prefix = "${b[0].toInt() and 0xff}.${b[1].toInt() and 0xff}.${b[2].toInt() and 0xff}."
        val pool = java.util.concurrent.Executors.newFixedThreadPool(64)
        try {
            val open = (1..254).map { "$prefix$it" }.filter { it != me.hostAddress }.map { ip ->
                pool.submit<String?> {
                    runCatching { java.net.Socket().use { s -> s.connect(java.net.InetSocketAddress(ip, port), 400) }; ip }.getOrNull()
                }
            }.mapNotNull { runCatching { it.get(5, TimeUnit.SECONDS) }.getOrNull() }
            return open.mapNotNull { ip ->
                runCatching { RelayClient(ip, port).info() }.getOrNull()
                    ?.takeIf { it.optString("app") == "PhotoRelay" }
                    ?.let { Found(it.optString("name"), ip, port) }
            }
        } finally {
            pool.shutdownNow()
        }
    }
}
