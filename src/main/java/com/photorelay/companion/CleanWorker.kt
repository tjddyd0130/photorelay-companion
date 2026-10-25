package com.photorelay.companion

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import androidx.work.Constraints
import androidx.work.ForegroundInfo
import androidx.work.OutOfQuotaPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

data class CleanStatus(val running: Boolean = false, val text: String = "")

object CleanState {
    private val _s = MutableStateFlow(CleanStatus())
    val status: StateFlow<CleanStatus> = _s
    private val running = java.util.concurrent.atomic.AtomicBoolean(false)
    fun set(s: CleanStatus) { _s.value = s }
    /** 두 워커(정기·지금)가 동시에 시작하지 않도록 한 번에 하나만 */
    fun tryStart(): Boolean = running.compareAndSet(false, true)
    fun finish(text: String) { _s.value = CleanStatus(false, text); running.set(false) }
}

/**
 * 1시간마다 (충전 중 + Wi-Fi). 집 Wi-Fi가 아니면 S7에 연결이 안 되므로 조용히 다음 회차로 넘긴다.
 * 화면이 꺼져도 끝까지 돌도록 실행 중에는 foreground 알림으로 올리고 Wi-Fi 절전을 막는다.
 */
class CleanWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(applicationContext, "사진 정리 중")

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (!CleanState.tryStart()) return@withContext Result.success()
        // 배터리 최적화 제외가 안 된 상태에서 백그라운드로 시작되면 Android가 거부할 수 있다. 그래도 정리는 계속한다.
        runCatching { setForeground(getForegroundInfo()) }.onFailure { Log.w(TAG, "foreground 불가: ${it.message}") }
        @Suppress("DEPRECATION")
        val wifiLock = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "PhotoRelayCompanion:clean").apply { setReferenceCounted(false); acquire() }
        CleanState.set(CleanStatus(true, "정리 시작"))
        var text = "정리 중단됨"
        try {
            val r = Cleaner(applicationContext, { CleanState.set(CleanStatus(true, it)) }, { isStopped }).run()
            text = r.text()
        } catch (e: Exception) {
            // 원문 예외는 S7 기록에만 남기고, 화면에는 무엇을 하면 되는지만 보여 준다
            val why = "${e.javaClass.simpleName}: ${e.message ?: ""}".take(200)
            val (kind, friendly) = friendlyError(e)
            text = friendly
            val prefs = CompanionPrefs(applicationContext)
            prefs.lastResult = text
            prefs.lastKind = kind
            prefs.lastRunMs = System.currentTimeMillis()
            // 연결은 됐다가 중간에 실패한 경우 S7 기록에도 남긴다 (원격으로 원인 확인용)
            if (prefs.paired && e !is RelayClient.BadSignature) {
                RelayClient(prefs.host, prefs.port, prefs.token).report("${Cleaner.deviceName(applicationContext)} 오류: $why")
            }
        } finally {
            // OutOfMemoryError 같은 Error가 나도 "정리 중"으로 영영 남지 않게
            CleanState.finish(text)
            runCatching { wifiLock.release() }
        }
        Result.success()
    }

    companion object {
        private const val TAG = "PhotoRelayCompanion"

        /** (종류, 화면 문구). 종류는 화면이 색과 버튼을 고르는 데 쓴다 */
        fun friendlyError(e: Throwable): Pair<String, String> = when {
            e is RelayClient.BadSignature -> "badsig" to "S7이 아닌 기기가 응답해 정리를 멈췄습니다. 안전을 위해 S7과 다시 연결해 주세요"
            e is RelayClient.RelayException && (e.code == 401 || e.code == 403) -> "auth" to "S7과의 연결이 끊겼습니다. 다시 연결해 주세요"
            e is RelayClient.RelayException && e.code == 503 -> "error" to "S7이 구글포토를 확인할 수 없는 상태라 이번엔 건너뛰었습니다"
            e is java.io.IOException -> "away" to "집 Wi-Fi가 아니거나 S7이 응답하지 않아 이번엔 건너뛰었습니다"
            e.message?.contains("위치") == true -> "perm" to "사진 위치 권한이 없어 확인하지 못했습니다"
            e.message?.contains("연결되지 않음") == true -> "auth" to "S7과 연결되어 있지 않습니다"
            else -> "error" to "정리하지 못했습니다. 다음에 다시 시도합니다"
        }
        private const val CHANNEL = "clean"
        private const val NOTIFICATION_ID = 1

        fun foregroundInfo(ctx: Context, text: String): ForegroundInfo {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "사진 정리", NotificationManager.IMPORTANCE_LOW))
            val n = Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_popup_sync)
                .setContentTitle("PhotoRelay")
                .setContentText(text)
                .setOngoing(true)
                .build()
            return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else ForegroundInfo(NOTIFICATION_ID, n)
        }
    }
}

object CleanScheduler {
    private const val WORK = "photorelay-companion"

    fun apply(ctx: Context) {
        val wm = WorkManager.getInstance(ctx)
        if (!CompanionPrefs(ctx).autoEnabled) {
            wm.cancelUniqueWork(WORK)
            return
        }
        val req = PeriodicWorkRequestBuilder<CleanWorker>(1, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).setRequiresCharging(true).build())
            .build()
        wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    fun runNow(ctx: Context) {
        WorkManager.getInstance(ctx).enqueueUniqueWork(
            "$WORK-now", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<CleanWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build(),
        )
    }
}
