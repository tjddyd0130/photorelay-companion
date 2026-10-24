package com.photorelay.companion

import android.Manifest
import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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

class CompanionViewModel(app: Application) : AndroidViewModel(app) {
    private val p = CompanionPrefs(app)
    var paired by mutableStateOf(p.paired); private set
    var serverName by mutableStateOf(p.serverName); private set
    var found by mutableStateOf<List<Discovery.Found>>(emptyList()); private set
    var host by mutableStateOf(p.host)
    var code by mutableStateOf("")
    var message by mutableStateOf<String?>(null); private set
    var searching by mutableStateOf(false); private set
    var keepDays by mutableStateOf(p.keepDays); private set
    var realDelete by mutableStateOf(p.realDelete); private set
    var autoEnabled by mutableStateOf(p.autoEnabled); private set
    var allFiles by mutableStateOf(Cleaner.canDeleteSilently()); private set
    var background by mutableStateOf(backgroundAllowed()); private set

    var canInstall by mutableStateOf(app.packageManager.canRequestPackageInstalls()); private set
    var updateStatus by mutableStateOf(p.updateStatus); private set
    var checkingUpdate by mutableStateOf(false); private set

    fun refresh() {
        allFiles = Cleaner.canDeleteSilently(); background = backgroundAllowed()
        canInstall = getApplication<Application>().packageManager.canRequestPackageInstalls()
        updateStatus = p.updateStatus
    }

    fun checkUpdate() {
        checkingUpdate = true
        viewModelScope.launch(Dispatchers.IO) {
            val text = runCatching { Updater.checkAndInstall(getApplication()) }.getOrElse { "업데이트 확인 실패: ${it.message}" }
            p.updateStatus = text; updateStatus = text
            checkingUpdate = false
        }
    }

    /** 배터리 최적화 제외(삼성: '제한 없음')여야 화면이 꺼진 동안에도 정해진 시간에 깨어나 정리할 수 있다 */
    private fun backgroundAllowed(): Boolean {
        val app = getApplication<Application>()
        return app.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(app.packageName)
    }

    fun search() {
        searching = true; message = null
        viewModelScope.launch(Dispatchers.IO) {
            found = Discovery.find(getApplication())
            found.firstOrNull()?.let { host = it.host }
            message = if (found.isEmpty()) "S7을 못 찾았습니다. 같은 Wi-Fi인지 확인하거나 S7 설정 화면의 주소를 직접 입력하세요." else null
            searching = false
        }
    }

    fun pair() {
        // "http://192.168.0.10:47321/" 처럼 붙여 넣어도 되게
        val raw = host.trim().substringAfter("://").trimEnd('/')
        val h = raw.substringBefore(':')
        val port = raw.substringAfter(':', "47321").toIntOrNull() ?: 47321
        message = "연결 중…"
        viewModelScope.launch(Dispatchers.IO) {
            message = try {
                val r = RelayClient(h, port).pair(code.filter(Char::isDigit), Cleaner.deviceName(getApplication()))
                p.host = h; p.port = port; p.token = r.token; p.secret = r.secret; p.serverName = r.name; p.knownHosts = setOf(h)
                paired = true; serverName = r.name; verified = r.secret.isNotEmpty()
                val name = r.name
                CleanScheduler.apply(getApplication())
                "$name 과 연결됐습니다"
            } catch (e: Exception) {
                "연결 실패: ${e.message}"
            }
        }
    }

    var selectedAlbums by mutableStateOf(p.albums); private set
    var available by mutableStateOf<List<Album>>(emptyList()); private set
    var onlyRelayed by mutableStateOf(p.onlyRelayed); private set

    fun loadAlbums() {
        viewModelScope.launch(Dispatchers.IO) { available = Cleaner(getApplication()).albums() }
    }
    fun toggleAlbum(path: String) {
        val next = if (path in selectedAlbums) selectedAlbums - path else selectedAlbums + path
        p.albums = next; selectedAlbums = next
    }
    fun changeOnlyRelayed(v: Boolean) { p.onlyRelayed = v; onlyRelayed = v }

    var verified by mutableStateOf(p.secret.isNotEmpty()); private set

    fun unpair() { p.unpair(); paired = false; realDelete = false; CleanScheduler.apply(getApplication()) }
    fun changeKeepDays(v: Int) { p.keepDays = v; keepDays = v }
    fun changeRealDelete(v: Boolean) { p.realDelete = v; realDelete = v }
    val mediaLocation get() = getApplication<Application>().checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED

    fun changeAuto(v: Boolean) { p.autoEnabled = v; autoEnabled = v; CleanScheduler.apply(getApplication()) }
    fun runNow() = CleanScheduler.runNow(getApplication())

    val lastResult get() = p.lastResult
    val lastHeartbeat get() = p.lastHeartbeatMs
    val lastRun get() = p.lastRunMs
    val totals get() = p.totalDeleted to p.totalFreed
}

class MainActivity : ComponentActivity() {
    private val perms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        perms.launch(arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.ACCESS_MEDIA_LOCATION,
            Manifest.permission.POST_NOTIFICATIONS, // 정리 중 알림 (화면이 꺼져도 계속 돌기 위한 foreground 알림)
        ))
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

@Composable
private fun Screen(vm: CompanionViewModel = viewModel()) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val st by CleanState.status.collectAsState()
    var confirm by remember { mutableStateOf(false) }
    var albumDialog by remember { mutableStateOf(false) }
    LaunchedEffect(st.running) { vm.refresh() }
    // 설정 화면에서 권한을 바꾸고 돌아오면 바로 반영
    val owner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) vm.refresh() }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("PhotoRelay 정리", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("구글포토에 원본으로 백업된 사진만 이 폰에서 지웁니다 (확인은 집의 S7이 합니다)", fontSize = 13.sp)

        if (!vm.paired) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("S7과 연결", style = MaterialTheme.typography.titleMedium)
                    Text("1. S7 PhotoRelay → 설정 → 메인폰 연결 → '연결 코드 만들기'\n2. 같은 Wi-Fi에서 아래에 주소와 코드를 입력", fontSize = 13.sp)
                    OutlinedButton(onClick = vm::search, enabled = !vm.searching) { Text(if (vm.searching) "찾는 중…" else "S7 자동으로 찾기") }
                    vm.found.forEach { Text("찾음: ${it.name} (${it.host}:${it.port})", fontSize = 12.sp) }
                    OutlinedTextField(vm.host, { vm.host = it }, Modifier.fillMaxWidth(), label = { Text("S7 주소 (예: 192.168.0.10)") }, singleLine = true)
                    OutlinedTextField(
                        vm.code, { vm.code = it.take(7) }, Modifier.fillMaxWidth(), label = { Text("연결 코드 6자리") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    Button(onClick = vm::pair, enabled = vm.host.isNotBlank() && vm.code.count(Char::isDigit) == 6) { Text("연결") }
                    vm.message?.let { Text(it, fontSize = 13.sp) }
                }
            }
            return@Column
        }

        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (st.running) "정리 중" else if (vm.realDelete) "자동 정리 켜짐" else "미리보기 모드", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text("연결: ${vm.serverName}", fontSize = 13.sp)
                if (st.running) LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(st.text.ifEmpty { vm.lastResult.ifEmpty { "아직 실행 기록 없음" } }, fontSize = 13.sp)
                if (vm.lastRun > 0) Text("마지막 실행 ${SimpleDateFormat("M/d HH:mm", Locale.KOREA).format(Date(vm.lastRun))}", fontSize = 12.sp)
                if (vm.lastHeartbeat > 0) {
                    val silent = System.currentTimeMillis() - vm.lastHeartbeat > S7Watch.DOWN_AFTER_MS
                    Text(
                        "S7 마지막 신호 ${SimpleDateFormat("M/d HH:mm", Locale.KOREA).format(Date(vm.lastHeartbeat))}" + if (silent) " — 응답 없음" else "",
                        fontSize = 12.sp, color = if (silent) MaterialTheme.colorScheme.error else Color.Unspecified,
                    )
                }
                val (n, b) = vm.totals
                Text("지금까지 ${n}개 삭제 · ${"%.1f".format(b / 1073741824.0)}GB 확보", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Button(onClick = vm::runNow, enabled = !st.running) { Text(if (vm.realDelete) "지금 정리" else "지금 미리보기") }
            }
        }

        if (!vm.verified) {
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("다시 연결을 권장합니다", fontWeight = FontWeight.SemiBold)
                    Text("새 버전은 S7의 답에 서명을 붙여 같은 Wi-Fi의 다른 기기가 S7인 척하지 못하게 합니다. 예전에 연결한 상태라 서명이 없으니, S7 설정에서 코드를 새로 만들어 한 번 더 연결해 주세요.", fontSize = 13.sp)
                    OutlinedButton(onClick = vm::unpair) { Text("연결 해제하고 다시 연결") }
                }
            }
        }

        if (!vm.mediaLocation) {
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("권한 필요: 사진 위치 정보", fontWeight = FontWeight.SemiBold)
                    Text("원본 그대로 지문을 내려면 필요합니다. 없으면 구글포토와 비교할 수 없어 아무것도 정리하지 않습니다.", fontSize = 13.sp)
                    OutlinedButton(onClick = {
                        ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")))
                    }) { Text("앱 권한 열기") }
                }
            }
        }

        if (!vm.background) {
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("권한 필요: 백그라운드 실행", fontWeight = FontWeight.SemiBold)
                    Text("화면이 꺼져 있어도 자동 정리가 돌려면 배터리 최적화에서 빼야 합니다. 정리할 때만 잠깐 깨어나므로 배터리 영향은 작습니다.", fontSize = 13.sp)
                    OutlinedButton(onClick = {
                        @android.annotation.SuppressLint("BatteryLife")
                        val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))
                        ctx.startActivity(i)
                    }) { Text("허용하기") }
                }
            }
        }

        if (!vm.allFiles) {
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("권한 필요: 모든 파일 접근", fontWeight = FontWeight.SemiBold)
                    Text("확인 창 없이 사진을 지우려면 필요합니다. 미리보기는 권한 없이도 됩니다.", fontSize = 13.sp)
                    OutlinedButton(onClick = {
                        ctx.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${ctx.packageName}")))
                    }) { Text("설정 열기") }
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("정리할 앨범", style = MaterialTheme.typography.titleMedium)
                Text("고른 앨범의 사진만 정리합니다. OneDrive로 올리는 앨범을 고르세요.", fontSize = 12.sp)
                if (vm.selectedAlbums.isEmpty()) {
                    Text("아직 고른 앨범이 없습니다 (고르기 전에는 아무것도 지우지 않음)", fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
                } else {
                    vm.selectedAlbums.sorted().forEach { Text("✔ ${Cleaner.albumLabel(it)}  ($it)", fontSize = 13.sp) }
                }
                OutlinedButton(onClick = { vm.loadAlbums(); albumDialog = true }) { Text("앨범 고르기") }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("설정", style = MaterialTheme.typography.titleMedium)
                HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("실제 삭제")
                        Text("끄면 지울 대상만 계산합니다", fontSize = 12.sp)
                    }
                    Switch(vm.realDelete, { on -> if (on) confirm = true else vm.changeRealDelete(false) }, enabled = vm.allFiles || vm.realDelete)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("S7이 받은 사진만")
                        Text("OneDrive를 거쳐 S7이 직접 받은 사진만 지웁니다. 다른 경로로 구글포토에 있는 사진은 남깁니다", fontSize = 12.sp)
                    }
                    Switch(vm.onlyRelayed, vm::changeOnlyRelayed)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("자동 정리")
                        Text("1시간마다, 충전 중 + Wi-Fi (집이 아니면 건너뜀)", fontSize = 12.sp)
                    }
                    Switch(vm.autoEnabled, vm::changeAuto)
                }
                Text("최근 사진은 남기기")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0, 3, 7, 30).forEach { d ->
                        FilterChip(selected = vm.keepDays == d, onClick = { vm.changeKeepDays(d) }, label = { Text(if (d == 0) "안 남김" else "${d}일") })
                    }
                }
                TextButton(onClick = vm::unpair) { Text("S7 연결 해제", color = MaterialTheme.colorScheme.error) }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("앱 업데이트", style = MaterialTheme.typography.titleMedium)
                Text("버전 ${BuildConfig.VERSION_NAME} · 하루 한 번 GitHub(${Updater.REPO})에서 새 버전을 확인해 자동 설치합니다", fontSize = 12.sp)
                if (vm.updateStatus.isNotEmpty()) Text(vm.updateStatus, fontSize = 13.sp)
                if (!vm.canInstall) {
                    Text("자동 설치하려면 이 앱에 '앱 설치 허용'이 필요합니다.", fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
                    OutlinedButton(onClick = {
                        ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")))
                    }) { Text("앱 설치 허용") }
                }
                OutlinedButton(onClick = vm::checkUpdate, enabled = !vm.checkingUpdate) { Text(if (vm.checkingUpdate) "확인 중…" else "지금 업데이트 확인") }
            }
        }
    }

    if (albumDialog) {
        AlertDialog(
            onDismissRequest = { albumDialog = false },
            title = { Text("정리할 앨범") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (vm.available.isEmpty()) Text("불러오는 중…")
                    vm.available.forEach { a ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = a.path in vm.selectedAlbums, onCheckedChange = { vm.toggleAlbum(a.path) })
                            Column {
                                Text(a.label, fontSize = 15.sp)
                                Text("${a.path} · ${a.count}개 · ${"%.1f".format(a.bytes / 1073741824.0)}GB", fontSize = 11.sp)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { albumDialog = false }) { Text("완료") } },
        )
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("실제 삭제를 켤까요?") },
            text = { Text("S7이 구글포토에 원본 화질로 있다고 확인한 사진만, 지우기 직전 지문을 다시 확인한 뒤 이 폰에서 완전 삭제합니다. 최근 ${vm.keepDays}일 사진은 남깁니다.") },
            confirmButton = { TextButton(onClick = { vm.changeRealDelete(true); confirm = false }) { Text("켜기") } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("취소") } },
        )
    }
}
