package com.photorelay.companion

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * S7이 살아 있는지 메인폰에서 지켜본다.
 * S7은 자동 실행(30분마다)이 끝날 때마다 ntfy 별도 주제에 하트비트를 남긴다. 그 주제는 짝지은 S7이
 * 서명된 응답으로 알려 준다 (Cleaner가 prefs.heartbeatUrl에 저장). 메인폰은 집 밖에서도 인터넷으로 확인할 수 있다.
 * 마지막 하트비트가 [DOWN_AFTER_MS]보다 오래되면 알림, 다시 들어오면 "정상" 알림.
 */
object S7Watch {
    const val DOWN_AFTER_MS = 3 * 60 * 60 * 1000L
    private const val REALERT_MS = 12 * 60 * 60 * 1000L
    private const val CHANNEL = "s7watch"
    private const val NOTIFICATION_ID = 3
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()

    /** ntfy에 남은 마지막 하트비트 시각(ms). ntfy는 12시간치만 보관하므로 없으면 null */
    fun lastHeartbeat(url: String): Long? {
        val req = Request.Builder().url("$url/json?poll=1&since=12h").build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw java.io.IOException("ntfy ${r.code}")
            return r.body!!.string().lineSequence().filter { it.isNotBlank() }
                .mapNotNull { runCatching { JSONObject(it).optLong("time") * 1000 }.getOrNull() }
                .maxOrNull()
        }
    }

    /** 판정 (순수 함수: 테스트용). 돌려주는 값: 보낼 알림 문구(없으면 null)와 새 상태 */
    data class Decision(val notify: String?, val down: Boolean, val alertedAt: Long)

    fun decide(lastHb: Long?, now: Long, wasDown: Boolean, alertedAt: Long): Decision {
        val silentMs = if (lastHb == null) Long.MAX_VALUE else now - lastHb
        val down = silentMs > DOWN_AFTER_MS
        return when {
            down && (!wasDown || now - alertedAt > REALERT_MS) -> {
                val h = if (lastHb == null) "12시간 넘게" else "${silentMs / 3_600_000}시간째"
                Decision("S7이 $h 신호가 없습니다. 집 Wi-Fi나 전원을 확인해 주세요 (S7 화면에서 비행기 모드를 껐다 켜면 대부분 돌아옵니다).", true, now)
            }
            !down && wasDown -> Decision("S7이 다시 정상적으로 작동합니다.", false, 0)
            else -> Decision(null, down, alertedAt)
        }
    }

    fun check(ctx: Context) {
        val p = CompanionPrefs(ctx)
        val url = p.heartbeatUrl
        if (!p.paired || url.isBlank()) return
        val last = lastHeartbeat(url)
        val now = System.currentTimeMillis()
        if (last != null) p.lastHeartbeatMs = last
        val d = decide(last, now, p.s7Down, p.s7AlertedAt)
        p.s7Down = d.down
        p.s7AlertedAt = d.alertedAt
        d.notify?.let { notify(ctx, it) }
    }

    private fun notify(ctx: Context, text: String) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "S7 상태", NotificationManager.IMPORTANCE_DEFAULT))
        nm.notify(
            NOTIFICATION_ID,
            Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle("PhotoRelay · S7")
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .build(),
        )
    }

    fun schedule(ctx: Context) {
        val req = PeriodicWorkRequestBuilder<S7WatchWorker>(1, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("photorelay-s7watch", ExistingPeriodicWorkPolicy.KEEP, req)
    }
}

/** 1시간마다 S7 하트비트 확인 (인터넷만 있으면 됨, 충전·집 Wi-Fi 조건 없음) */
class S7WatchWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        runCatching { S7Watch.check(applicationContext) }
        Result.success()
    }
}
