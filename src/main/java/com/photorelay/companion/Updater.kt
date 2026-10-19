package com.photorelay.companion

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
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
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * GitHub Releases를 업데이트 서버로 쓴다.
 * 릴리스의 APK 이름은 `photorelay-companion-vc<versionCode>.apk` — 이름의 versionCode가 지금보다 크면 받아서 설치한다.
 * 같은 서명 키로 만든 APK만 Android가 설치를 허락하므로, 다른 사람이 만든 APK로 바뀔 수는 없다.
 */
object Updater {
    const val REPO = "tjddyd0130/photorelay-companion"
    private const val TAG = "PhotoRelayCompanion"
    private val ASSET = Regex("""photorelay-companion-vc(\d+)\.apk""")
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).build()

    data class Release(val versionName: String, val versionCode: Int, val url: String, val notes: String)

    /** 최신 릴리스 (없거나 APK가 없으면 null) */
    fun latest(): Release? {
        val req = Request.Builder().url("https://api.github.com/repos/$REPO/releases/latest")
            .header("Accept", "application/vnd.github+json").build()
        http.newCall(req).execute().use { r ->
            if (r.code == 404) return null
            if (!r.isSuccessful) throw java.io.IOException("GitHub 응답 ${r.code}")
            val o = JSONObject(r.body!!.string())
            val assets = o.getJSONArray("assets")
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                val code = assetVersionCode(a.getString("name")) ?: continue
                return Release(
                    versionName = o.getString("tag_name").removePrefix("v"),
                    versionCode = code,
                    url = a.getString("browser_download_url"),
                    notes = o.optString("body"),
                )
            }
            return null
        }
    }

    /** 릴리스 APK 이름에서 versionCode (규칙에 안 맞으면 null) */
    fun assetVersionCode(name: String): Int? = ASSET.matchEntire(name)?.groupValues?.get(1)?.toIntOrNull()

    fun isNewer(r: Release) = r.versionCode > BuildConfig.VERSION_CODE

    /** 받아서 설치를 시작한다. 처음 한 번은 Android가 사용자 확인을 요구할 수 있다 (UpdateReceiver가 처리). */
    fun downloadAndInstall(ctx: Context, r: Release) {
        val apk = File(ctx.cacheDir, "update.apk")
        http.newCall(Request.Builder().url(r.url).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw java.io.IOException("APK 받기 실패 ${resp.code}")
            apk.outputStream().use { out -> resp.body!!.byteStream().copyTo(out) }
        }
        // 받은 파일이 정말 이 앱의 더 높은 버전인지 확인 (서명은 설치할 때 Android가 확인)
        val info = ctx.packageManager.getPackageArchiveInfo(apk.path, 0)
            ?: throw java.io.IOException("APK를 읽을 수 없음")
        check(info.packageName == ctx.packageName) { "다른 앱의 APK: ${info.packageName}" }
        check(info.longVersionCode > BuildConfig.VERSION_CODE) { "더 높은 버전이 아님 (${info.longVersionCode})" }

        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(ctx.packageName)
            // Android 12+: 이 앱이 직접 설치한 앱이면 확인 창 없이 업데이트
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { s ->
            s.openWrite("update.apk", 0, apk.length()).use { out -> apk.inputStream().use { it.copyTo(out) }; s.fsync(out) }
            val pi = PendingIntent.getBroadcast(
                ctx, id, Intent(ctx, UpdateReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            s.commit(pi.intentSender)
        }
        Log.i(TAG, "update ${r.versionName} committed")
    }

    /** 정리 중이 아니면 확인하고, 새 버전이면 설치. 결과 문구를 돌려준다. */
    fun checkAndInstall(ctx: Context): String {
        if (CleanState.status.value.running) return "정리 중이라 다음에 확인"
        val r = latest() ?: return "릴리스 없음"
        if (!isNewer(r)) return "최신 버전 (${BuildConfig.VERSION_NAME})"
        if (!ctx.packageManager.canRequestPackageInstalls()) return "새 버전 ${r.versionName} 있음 — '앱 설치 허용'이 필요합니다"
        downloadAndInstall(ctx, r)
        return "새 버전 ${r.versionName} 설치 중"
    }

    fun schedule(ctx: Context) {
        val req = PeriodicWorkRequestBuilder<UpdateWorker>(1, TimeUnit.DAYS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build())
            .build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("photorelay-companion-update", ExistingPeriodicWorkPolicy.KEEP, req)
    }
}

/** 하루 한 번 GitHub에서 새 버전 확인 */
class UpdateWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val text = runCatching { Updater.checkAndInstall(applicationContext) }.getOrElse { "업데이트 확인 실패: ${it.message}" }
        CompanionPrefs(applicationContext).updateStatus = text
        Result.success()
    }
}

/** 설치 결과. 사용자 확인이 필요하면 알림으로 띄운다 (백그라운드에서는 화면을 바로 열 수 없다). */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = CompanionPrefs(context)
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                prefs.updateStatus = "업데이트 설치 확인 필요 (알림을 누르세요)"
                val nm = context.getSystemService(NotificationManager::class.java)
                nm.createNotificationChannel(NotificationChannel("update", "앱 업데이트", NotificationManager.IMPORTANCE_DEFAULT))
                val pi = PendingIntent.getActivity(context, 0, confirm, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                nm.notify(
                    2,
                    Notification.Builder(context, "update")
                        .setSmallIcon(android.R.drawable.stat_sys_download_done)
                        .setContentTitle("PhotoRelay 정리 업데이트")
                        .setContentText("눌러서 새 버전을 설치하세요")
                        .setContentIntent(pi)
                        .setAutoCancel(true)
                        .build(),
                )
                // 앱을 보고 있으면 바로 확인 창을 연다
                runCatching { context.startActivity(confirm) }
            }
            PackageInstaller.STATUS_SUCCESS -> prefs.updateStatus = "업데이트 완료"
            else -> prefs.updateStatus = "업데이트 실패 ($status): ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""}"
        }
    }
}
