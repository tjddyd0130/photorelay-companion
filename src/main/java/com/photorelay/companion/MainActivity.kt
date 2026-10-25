package com.photorelay.companion

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val timeFmt = SimpleDateFormat("M/d HH:mm", Locale.KOREA)

private fun granted(ctx: Context, p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

class CompanionViewModel(app: Application) : AndroidViewModel(app) {
    private val p = CompanionPrefs(app)
    var paired by mutableStateOf(p.paired); private set
    var serverName by mutableStateOf(p.serverName); private set
    var found by mutableStateOf<List<Discovery.Found>>(emptyList()); private set
    var host by mutableStateOf(p.host)
    var code by mutableStateOf("")
    var message by mutableStateOf<String?>(null); private set
    var searching by mutableStateOf(false); private set
    var pairing by mutableStateOf(false); private set
    var keepDays by mutableStateOf(p.keepDays); private set
    var realDelete by mutableStateOf(p.realDelete); private set
    var autoEnabled by mutableStateOf(p.autoEnabled); private set
    var onlyRelayed by mutableStateOf(p.onlyRelayed); private set
    var verified by mutableStateOf(p.secret.isNotEmpty()); private set

    // 권한
    var allFiles by mutableStateOf(false); private set
    var background by mutableStateOf(false); private set
    var mediaLocation by mutableStateOf(false); private set
    /** 사진 접근이 "모두 허용"인지 ("선택한 사진만"이면 일부만 보인다) */
    var photosFull by mutableStateOf(false); private set
    var canInstall by mutableStateOf(false); private set

    // 실행 결과·상태 (설정 화면에서 돌아오거나 워커가 끝나면 refresh로 갱신)
    var lastResult by mutableStateOf(""); private set
    var lastKind by mutableStateOf(""); private set
    var lastRun by mutableStateOf(0L); private set
    var lastGood by mutableStateOf(""); private set
    var lastGoodMs by mutableStateOf(0L); private set
    var totals by mutableStateOf(0L to 0L); private set
    var previewCount by mutableStateOf(-1); private set
    var previewBytes by mutableStateOf(0L); private set
    var lastHeartbeat by mutableStateOf(0L); private set
    var s7Down by mutableStateOf(false); private set
    /** 버튼을 누른 직후 워커가 시작되기 전까지 */
    var starting by mutableStateOf(false); private set

    var updateStatus by mutableStateOf(""); private set
    var updatePending by mutableStateOf(""); private set
    var updateChecked by mutableStateOf(0L); private set
    var checkingUpdate by mutableStateOf(false); private set

    var selectedAlbums by mutableStateOf(p.albums); private set
    var available by mutableStateOf<List<Album>>(emptyList()); private set
    var albumsLoading by mutableStateOf(false); private set

    init { refresh() }

    fun refresh() {
        val app = getApplication<Application>()
        allFiles = Cleaner.canDeleteSilently()
        background = app.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(app.packageName)
        mediaLocation = granted(app, Manifest.permission.ACCESS_MEDIA_LOCATION)
        photosFull = granted(app, Manifest.permission.READ_MEDIA_IMAGES) || allFiles
        canInstall = app.packageManager.canRequestPackageInstalls()
        lastResult = p.lastResult; lastKind = p.lastKind; lastRun = p.lastRunMs
        lastGood = p.lastGood; lastGoodMs = p.lastGoodMs
        totals = p.totalDeleted to p.totalFreed
        previewCount = p.previewCount; previewBytes = p.previewBytes
        lastHeartbeat = p.lastHeartbeatMs; s7Down = p.s7Down
        updateStatus = p.updateStatus; updatePending = p.updatePending; updateChecked = p.updateCheckedMs
        verified = p.secret.isNotEmpty(); paired = p.paired; serverName = p.serverName
    }

    fun checkUpdate() {
        checkingUpdate = true
        viewModelScope.launch(Dispatchers.IO) {
            val text = runCatching { Updater.checkAndInstall(getApplication()) }.getOrElse { "업데이트를 확인하지 못했습니다. 잠시 뒤 다시 눌러 주세요" }
            p.updateStatus = text
            checkingUpdate = false
            refresh()
        }
    }

    fun search() {
        searching = true; message = null
        viewModelScope.launch(Dispatchers.IO) {
            found = Discovery.find(getApplication())
            found.firstOrNull()?.let { host = it.host }
            message = if (found.isEmpty()) "S7을 찾지 못했습니다. 이 폰과 S7이 같은 Wi-Fi인지 확인하거나, 아래 '직접 입력'에 S7 주소를 넣어 주세요." else null
            searching = false
        }
    }

    fun pick(f: Discovery.Found) { host = f.host }

    fun pair() {
        // "http://192.168.0.10:47321/" 처럼 붙여 넣어도 되게
        val raw = host.trim().substringAfter("://").trimEnd('/')
        val h = raw.substringBefore(':')
        val port = raw.substringAfter(':', "47321").toIntOrNull() ?: 47321
        message = "연결하는 중…"; pairing = true
        viewModelScope.launch(Dispatchers.IO) {
            message = try {
                val r = RelayClient(h, port).pair(code.filter(Char::isDigit), Cleaner.deviceName(getApplication()))
                p.host = h; p.port = port; p.token = r.token; p.secret = r.secret; p.serverName = r.name; p.knownHosts = setOf(h)
                CleanScheduler.apply(getApplication())
                code = ""
                refresh()
                "S7(${r.name})과 연결됐습니다"
            } catch (e: RelayClient.RelayException) {
                if (e.code == 403) "코드가 맞지 않거나 시간이 지났습니다. S7에서 새 코드를 만들어 주세요" else "S7이 연결을 거절했습니다. S7에서 새 코드를 만들어 다시 시도해 주세요"
            } catch (e: Exception) {
                "S7에 연결할 수 없습니다. 같은 Wi-Fi인지, S7 앱이 켜져 있는지 확인해 주세요"
            }
            pairing = false
        }
    }

    fun loadAlbums() {
        albumsLoading = true
        viewModelScope.launch(Dispatchers.IO) {
            available = runCatching { Cleaner(getApplication()).albums() }.getOrDefault(emptyList())
            albumsLoading = false
        }
    }
    fun toggleAlbum(path: String) {
        val next = if (path in selectedAlbums) selectedAlbums - path else selectedAlbums + path
        p.albums = next; selectedAlbums = next
    }

    fun changeOnlyRelayed(v: Boolean) { p.onlyRelayed = v; onlyRelayed = v }
    fun changeKeepDays(v: Int) { p.keepDays = v; keepDays = v }
    fun changeRealDelete(v: Boolean) { p.realDelete = v; realDelete = v }
    fun changeAuto(v: Boolean) { p.autoEnabled = v; autoEnabled = v; CleanScheduler.apply(getApplication()) }

    fun unpair() {
        p.unpair(); realDelete = false; message = null; code = ""; found = emptyList()
        CleanScheduler.apply(getApplication())
        refresh()
    }

    fun runNow() {
        starting = true
        CleanScheduler.runNow(getApplication())
        // 워커가 시작되면 CleanState가 running이 되며 starting 표시를 대신한다
        viewModelScope.launch { kotlinx.coroutines.delay(15_000); starting = false }
    }
    fun started() { starting = false }
}

class MainActivity : ComponentActivity() {
    private val perms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 처음 한 번, 아직 없는 알림 권한만 묻는다 (사진 권한은 연결 후 체크리스트에서 이유와 함께 묻는다)
        if (savedInstanceState == null && !granted(this, Manifest.permission.POST_NOTIFICATIONS)) {
            perms.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
        }
        CleanScheduler.apply(this)
        Updater.schedule(this)
        S7Watch.schedule(this)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme(primary = Color(0xFF8AB4F8)) else lightColorScheme(primary = Color(0xFF1A73E8))) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { Screen() }
            }
        }
    }
}

/** 해결할 일 하나 (첫 카드 안에 심각한 순서대로) */
private data class Todo(val text: String, val action: String, val blocking: Boolean, val onClick: () -> Unit)

@Composable
private fun Screen(vm: CompanionViewModel = viewModel()) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val cs = MaterialTheme.colorScheme
    val st by CleanState.status.collectAsState()
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmUnpair by remember { mutableStateOf(false) }
    var albumDialog by remember { mutableStateOf(false) }
    var manualHost by remember { mutableStateOf(false) }

    val askPerms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { vm.refresh() }
    fun openAppSettings() = ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")))

    LaunchedEffect(st.running) { if (st.running) vm.started(); vm.refresh() }
    // 설정 화면에서 권한을 바꾸고 돌아오면 바로 반영
    val owner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) vm.refresh() }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("PhotoRelay 정리", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

        if (!vm.paired) {
            PairingCard(vm, manualHost) { manualHost = true }
            return@Column
        }

        // ---------- 해결할 일 (심각한 순서) ----------
        val todos = buildList {
            if (vm.s7Down) add(Todo("S7이 3시간 넘게 응답이 없습니다. S7의 전원과 집 Wi-Fi를 확인해 주세요 (S7에서 비행기 모드를 껐다 켜면 대부분 돌아옵니다)", "", true) {})
            if (vm.lastKind == "auth" || vm.lastKind == "badsig") add(Todo(vm.lastResult, "다시 연결", true) { vm.unpair() })
            if (!vm.photosFull) add(Todo("사진 접근을 '모두 허용'으로 해 주세요. 일부만 허용하면 고른 사진만 정리합니다", "허용", true) {
                if (Build.VERSION.SDK_INT >= 34 && granted(ctx, "android.permission.READ_MEDIA_VISUAL_USER_SELECTED")) openAppSettings()
                else askPerms.launch(arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO))
            })
            if (!vm.mediaLocation) add(Todo("사진 위치 권한이 필요합니다. 사진이 구글포토 백업과 똑같은지 확인하는 데 씁니다", "허용", true) {
                askPerms.launch(arrayOf(Manifest.permission.ACCESS_MEDIA_LOCATION))
            })
            if (vm.realDelete && !vm.allFiles) add(Todo("사진을 지우려면 '모든 파일 접근'이 필요합니다", "허용", true) {
                ctx.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${ctx.packageName}")))
            })
            if (vm.autoEnabled && !vm.background) add(Todo("화면이 꺼져 있어도 자동 정리가 돌도록 배터리 최적화에서 빼 주세요", "허용", false) {
                @android.annotation.SuppressLint("BatteryLife")
                val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))
                ctx.startActivity(i)
            })
            if (!vm.verified && vm.lastKind != "auth" && vm.lastKind != "badsig") add(Todo("보안 업데이트: S7과 한 번만 다시 연결해 주세요 (S7 → 설정 → 메인폰 연결 → 새 코드)", "다시 연결", false) { vm.unpair() })
        }
        val blockers = todos.count { it.blocking }
        val running = st.running || vm.starting

        // ---------- 첫 카드: 지금 괜찮은가 ----------
        val (bg, fg) = when {
            running -> cs.primaryContainer to cs.onPrimaryContainer
            vm.s7Down || blockers > 0 -> cs.errorContainer to cs.onErrorContainer
            todos.isNotEmpty() -> cs.tertiaryContainer to cs.onTertiaryContainer
            else -> cs.secondaryContainer to cs.onSecondaryContainer
        }
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = bg, contentColor = fg)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    when {
                        running -> if (vm.realDelete) "정리하는 중…" else "확인하는 중…"
                        vm.s7Down -> "S7 응답 없음"
                        todos.isNotEmpty() -> "확인이 필요합니다 (${todos.size})"
                        else -> "모두 정상"
                    },
                    style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                )

                if (running) {
                    val frac = Regex("""\((\d+)/(\d+)\)""").find(st.text)?.destructured?.let { (a, b) -> a.toFloat() / b.toFloat().coerceAtLeast(1f) }
                    if (frac != null) LinearProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(st.text.ifEmpty { "시작하는 중…" }, fontSize = 14.sp)
                } else {
                    // 큰 숫자
                    val (n, b) = vm.totals
                    Text(
                        when {
                            vm.realDelete && n > 0 -> "지금까지 ${n}장 정리 · ${formatSize(b)} 확보"
                            vm.realDelete -> "아직 정리한 사진 없음"
                            vm.previewCount >= 0 -> "지울 수 있는 사진 ${vm.previewCount}장 · ${formatSize(vm.previewBytes)} (아직 지우지 않음)"
                            else -> "'지금 미리보기'를 눌러 지울 수 있는 사진을 확인하세요"
                        },
                        fontSize = 17.sp, fontWeight = FontWeight.Medium,
                    )
                    // 마지막 실행 한 줄 (집 밖에서 건너뛴 건 오류가 아니다)
                    if (vm.lastRun > 0 && vm.lastKind == "away") {
                        Text("마지막 시도 ${timeFmt.format(Date(vm.lastRun))} · 집 Wi-Fi가 아니어서 건너뜀", fontSize = 14.sp)
                    }
                    if (vm.lastGoodMs > 0) {
                        Text("마지막 확인 ${timeFmt.format(Date(vm.lastGoodMs))} · ${vm.lastGood}", fontSize = 14.sp)
                    } else if (vm.lastRun == 0L) {
                        Text("아직 실행 기록이 없습니다", fontSize = 14.sp)
                    }
                    if (vm.lastKind == "error" || vm.lastKind == "perm") Text(vm.lastResult, fontSize = 14.sp, color = cs.error)
                }

                // 해결할 일
                todos.forEach { t ->
                    HorizontalDivider(color = fg.copy(alpha = 0.2f))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text((if (t.blocking) "⚠ " else "• ") + t.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
                        if (t.action.isNotEmpty()) OutlinedButton(onClick = t.onClick) { Text(t.action) }
                    }
                }

                HorizontalDivider(color = fg.copy(alpha = 0.2f))
                Text(
                    (if (vm.realDelete) "실제 삭제 켜짐" else "미리보기 모드 · 사진을 지우지 않음") +
                        (if (vm.autoEnabled) " · 1시간마다 자동 (충전 중 + Wi-Fi)" else " · 자동 정리 꺼짐"),
                    fontSize = 13.sp,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = vm::runNow, enabled = !running) { Text(if (vm.realDelete) "지금 정리" else "지금 미리보기") }
                    if (!vm.realDelete && vm.previewCount > 0) OutlinedButton(onClick = { confirmDelete = true }) { Text("실제 삭제 켜기") }
                }
                Text(
                    when {
                        vm.lastHeartbeat == 0L -> "S7 상태는 첫 정리 후 표시됩니다"
                        vm.s7Down -> "S7(${vm.serverName}) 마지막 신호 ${timeFmt.format(Date(vm.lastHeartbeat))}"
                        else -> "S7(${vm.serverName}) 정상 작동 · 마지막 확인 ${timeFmt.format(Date(vm.lastHeartbeat))}"
                    },
                    fontSize = 12.sp,
                )
            }
        }

        // ---------- 정리할 앨범 ----------
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("정리할 앨범", style = MaterialTheme.typography.titleMedium)
                if (vm.selectedAlbums.isEmpty()) {
                    Text("아직 고른 앨범이 없습니다. 고르기 전에는 아무것도 지우지 않습니다. OneDrive로 올리는 앨범을 고르세요.", fontSize = 14.sp, color = cs.error)
                } else {
                    Text(vm.selectedAlbums.sorted().joinToString(" · ") { Cleaner.albumLabel(it) }, fontSize = 14.sp)
                }
                OutlinedButton(onClick = { vm.loadAlbums(); albumDialog = true }) { Text("앨범 고르기") }
            }
        }

        // ---------- 설정 ----------
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("설정", style = MaterialTheme.typography.titleMedium)
                SettingRow(
                    "실제 삭제",
                    when {
                        vm.realDelete -> "구글포토 백업이 확인된 사진을 이 폰에서 지웁니다"
                        !vm.allFiles -> "먼저 '모든 파일 접근'을 허용해야 켤 수 있습니다 (눌러서 허용)"
                        else -> "꺼져 있으면 지울 사진만 세고 지우지 않습니다"
                    },
                    vm.realDelete,
                ) { on ->
                    when {
                        !on -> vm.changeRealDelete(false)
                        !vm.allFiles -> ctx.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${ctx.packageName}")))
                        else -> confirmDelete = true
                    }
                }
                SettingRow(
                    "OneDrive로 보낸 사진만 정리",
                    "켜 두면 OneDrive를 거쳐 S7이 받은 사진만 지웁니다 (권장). 끄면 구글포토에 원본이 있는 사진은 모두 지웁니다",
                    vm.onlyRelayed, vm::changeOnlyRelayed,
                )
                SettingRow("자동 정리", "1시간마다, 충전 중이고 Wi-Fi일 때 (집이 아니면 건너뜀)", vm.autoEnabled, vm::changeAuto)
                Text("최근 사진은 폰에 남겨 두기", fontSize = 16.sp, modifier = Modifier.padding(top = 8.dp))
                Text(if (vm.keepDays == 0) "최근 사진도 바로 정리합니다" else "찍거나 받은 지 ${vm.keepDays}일이 안 된 사진은 지우지 않습니다", fontSize = 13.sp, color = cs.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0, 3, 7, 30).forEach { d ->
                        FilterChip(selected = vm.keepDays == d, onClick = { vm.changeKeepDays(d) }, label = { Text(if (d == 0) "바로 정리" else "${d}일") })
                    }
                }
            }
        }

        // ---------- 연결 정보 ----------
        Card(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("연결된 S7", style = MaterialTheme.typography.titleMedium)
                    Text(vm.serverName.ifEmpty { "S7" }, fontSize = 14.sp)
                }
                TextButton(onClick = { confirmUnpair = true }) { Text("연결 해제", color = cs.error) }
            }
        }

        // ---------- 앱 정보 ----------
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "버전 ${BuildConfig.VERSION_NAME}" + when {
                            vm.updatePending.isNotEmpty() -> " · 새 버전 ${vm.updatePending} 있음"
                            vm.updateChecked > 0 -> " · 최신 (${timeFmt.format(Date(vm.updateChecked))} 확인)"
                            else -> ""
                        },
                        fontSize = 14.sp, modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = vm::checkUpdate, enabled = !vm.checkingUpdate) { Text(if (vm.checkingUpdate) "확인 중…" else "업데이트 확인") }
                }
                if (vm.updatePending.isNotEmpty() && !vm.canInstall) {
                    Text("설치하려면 이 앱에 '앱 설치 허용'을 켜 주세요", fontSize = 14.sp, color = cs.error)
                    OutlinedButton(onClick = {
                        ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")))
                    }) { Text("앱 설치 허용") }
                } else if (vm.updateStatus.isNotEmpty() && vm.updateStatus != "최신 버전입니다") {
                    Text(vm.updateStatus, fontSize = 13.sp, color = cs.onSurfaceVariant)
                }
                Text("하루 한 번 새 버전을 확인해 자동으로 설치합니다", fontSize = 12.sp, color = cs.onSurfaceVariant)
            }
        }
    }

    if (albumDialog) {
        AlertDialog(
            onDismissRequest = { albumDialog = false },
            title = { Text("정리할 앨범") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    when {
                        vm.albumsLoading -> Text("불러오는 중…")
                        vm.available.isEmpty() -> {
                            Text("사진을 찾지 못했습니다. 사진 접근을 '모두 허용'으로 바꿔 주세요.")
                            OutlinedButton(onClick = { openAppSettings() }, modifier = Modifier.padding(top = 8.dp)) { Text("앱 권한 열기") }
                        }
                    }
                    vm.available.forEach { a ->
                        val checked = a.path in vm.selectedAlbums
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                .toggleable(value = checked, role = Role.Checkbox, onValueChange = { vm.toggleAlbum(a.path) }),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = checked, onCheckedChange = null)
                            Column(Modifier.padding(start = 8.dp)) {
                                Text(a.label, fontSize = 16.sp)
                                Text("${"%,d".format(a.count)}장 · ${formatSize(a.bytes)}", fontSize = 13.sp, color = cs.onSurfaceVariant)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { albumDialog = false }) { Text("완료") } },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("사진을 실제로 지울까요?") },
            text = {
                Text(
                    "구글포토에 원본 화질로 백업된 것이 확인된 사진만 이 폰에서 지웁니다. 휴지통에 남지 않고 바로 삭제되며 되돌릴 수 없습니다." +
                        (if (vm.previewCount > 0) " 지난 미리보기 기준 약 ${vm.previewCount}장(${formatSize(vm.previewBytes)})이 지워집니다." else "") +
                        (if (vm.keepDays > 0) " 최근 ${vm.keepDays}일 안에 찍거나 받은 사진은 남깁니다." else " 최근 사진도 바로 지웁니다."),
                )
            },
            confirmButton = {
                Button(
                    onClick = { vm.changeRealDelete(true); confirmDelete = false },
                    colors = ButtonDefaults.buttonColors(containerColor = cs.error, contentColor = cs.onError),
                ) { Text("지우기 시작") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("취소") } },
        )
    }

    if (confirmUnpair) {
        AlertDialog(
            onDismissRequest = { confirmUnpair = false },
            title = { Text("S7과 연결을 끊을까요?") },
            text = { Text("다시 연결하려면 S7에서 새 코드를 만들어야 하고, 실제 삭제도 꺼집니다.") },
            confirmButton = { TextButton(onClick = { vm.unpair(); confirmUnpair = false }) { Text("연결 끊기", color = cs.error) } },
            dismissButton = { TextButton(onClick = { confirmUnpair = false }) { Text("취소") } },
        )
    }
}

/** 줄 전체를 눌러도 켜고 끌 수 있는 설정 */
@Composable
private fun SettingRow(title: String, sub: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .toggleable(value = value, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, fontSize = 16.sp)
            Text(sub, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = value, onCheckedChange = null)
    }
}

@Composable
private fun PairingCard(vm: CompanionViewModel, manualHost: Boolean, showManual: () -> Unit) {
    Text("구글포토에 원본으로 백업된 사진만 이 폰에서 지웁니다. 확인은 집의 S7이 합니다.", fontSize = 14.sp)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("S7과 연결", style = MaterialTheme.typography.titleMedium)
            Text(
                "1. S7에서 PhotoRelay → 설정 → 메인폰 연결 → '연결 코드 만들기'\n" +
                    "2. 이 폰도 같은 Wi-Fi에 연결한 뒤 'S7 자동으로 찾기'\n" +
                    "3. S7 화면의 6자리 코드 입력",
                fontSize = 14.sp,
            )
            Button(onClick = vm::search, enabled = !vm.searching) { Text(if (vm.searching) "찾는 중…" else "S7 자동으로 찾기") }
            vm.found.forEach { f ->
                val picked = vm.host.substringBefore(':') == f.host
                OutlinedButton(onClick = { vm.pick(f) }, modifier = Modifier.fillMaxWidth()) {
                    Text((if (picked) "✓ " else "") + "${f.name} (찾음)")
                }
            }
            if (manualHost || vm.found.isEmpty() && vm.host.isNotBlank()) {
                OutlinedTextField(vm.host, { vm.host = it }, Modifier.fillMaxWidth(), label = { Text("S7 주소 (S7 설정 화면에 표시)") }, singleLine = true)
            } else {
                TextButton(onClick = showManual) { Text("주소 직접 입력") }
            }
            OutlinedTextField(
                vm.code, { vm.code = it.filter(Char::isDigit).take(6) }, Modifier.fillMaxWidth(), label = { Text("연결 코드 6자리") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            Button(onClick = vm::pair, enabled = !vm.pairing && vm.host.isNotBlank() && vm.code.length == 6) { Text(if (vm.pairing) "연결하는 중…" else "연결") }
            vm.message?.let { Text(it, fontSize = 14.sp) }
        }
    }
}
