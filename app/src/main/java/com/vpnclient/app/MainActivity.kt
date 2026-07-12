package com.vpnclient.app

import android.content.ClipboardManager
import android.content.Context
import android.net.VpnService
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.vpnclient.app.core.ParsedServer
import com.vpnclient.app.core.SubscriptionParser
import com.vpnclient.app.ui.theme.*
import com.vpnclient.app.vpn.CoreVpnService
import com.vpnclient.app.vpn.VpnState
import com.vpnclient.app.vpn.VpnStatus
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            VpnClientTheme {
                AppRoot()
            }
        }
    }
}

@Composable
fun AppRoot() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Реальное состояние подключения — обновляется из CoreVpnService.
    val vpnState by VpnStatus.state.collectAsState()
    val vpnError by VpnStatus.lastError.collectAsState()
    val isConnected = vpnState == VpnState.CONNECTED
    val isBusy = vpnState == VpnState.CONNECTING || vpnState == VpnState.STOPPING

    // Список серверов из подписки. Пока живёт только в памяти —
    // на шаге "Хранение списка серверов" сохраним его на диск (DataStore/Room).
    var servers by remember { mutableStateOf<List<ParsedServer>>(emptyList()) }
    var selectedServer by remember { mutableStateOf<ParsedServer?>(null) }

    // Системный диалог "разрешить VpnClient создавать VPN-соединения".
    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            selectedServer?.let { CoreVpnService.start(context, it) }
        } else {
            Toast.makeText(context, "Без разрешения VPN подключиться нельзя", Toast.LENGTH_SHORT).show()
        }
    }

    fun toggleVpn() {
        if (isConnected || isBusy) {
            CoreVpnService.stop(context)
            return
        }
        val server = selectedServer
        if (server == null) {
            Toast.makeText(context, "Сначала выбери сервер из списка", Toast.LENGTH_SHORT).show()
            return
        }
        VpnStatus.clearError()
        val prepareIntent = VpnService.prepare(context)
        if (prepareIntent != null) {
            vpnPermissionLauncher.launch(prepareIntent)
        } else {
            CoreVpnService.start(context, server)
        }
    }

    LaunchedEffect(vpnError) {
        vpnError?.let {
            Toast.makeText(context, "Ошибка VPN: $it", Toast.LENGTH_LONG).show()
        }
    }

    // Если подписка была добавлена по URL — храним его, чтобы "Обновить подписку" работала.
    var subscriptionUrl by remember { mutableStateOf<String?>(null) }
    var isRefreshing by remember { mutableStateOf(false) }

    fun addFromClipboard() {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
        if (text.isNullOrBlank()) {
            Toast.makeText(context, "Буфер обмена пуст", Toast.LENGTH_SHORT).show()
            return
        }

        if (SubscriptionParser.isSubscriptionUrl(text)) {
            subscriptionUrl = text.trim()
            isRefreshing = true
            scope.launch {
                val result = runCatching { SubscriptionParser.fetchAndParseUrl(text.trim()) }
                isRefreshing = false
                result.onSuccess { parsed ->
                    if (parsed.isEmpty()) {
                        Toast.makeText(context, "Подписка пуста или не распознана", Toast.LENGTH_SHORT).show()
                    } else {
                        servers = parsed
                        selectedServer = parsed.firstOrNull()
                        Toast.makeText(context, "Добавлено серверов: ${parsed.size}", Toast.LENGTH_SHORT).show()
                    }
                }.onFailure {
                    Toast.makeText(context, "Не удалось скачать подписку: ${it.message}", Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            val parsed = SubscriptionParser.parseLinksBlob(text)
            if (parsed.isEmpty()) {
                Toast.makeText(context, "Не распознал ссылку в буфере обмена", Toast.LENGTH_SHORT).show()
            } else {
                servers = servers + parsed
                if (selectedServer == null) selectedServer = parsed.firstOrNull()
                Toast.makeText(context, "Добавлено серверов: ${parsed.size}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun refreshSubscription() {
        val url = subscriptionUrl
        if (url == null) {
            Toast.makeText(context, "Сначала добавь подписку по ссылке (кнопка +)", Toast.LENGTH_SHORT).show()
            return
        }
        isRefreshing = true
        scope.launch {
            val result = runCatching { SubscriptionParser.fetchAndParseUrl(url) }
            isRefreshing = false
            result.onSuccess { parsed ->
                servers = parsed
                if (selectedServer !in parsed) selectedServer = parsed.firstOrNull()
                Toast.makeText(context, "Подписка обновлена: ${parsed.size} серверов", Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(context, "Ошибка обновления: ${it.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Surface(color = BgDeep, modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {

            TopBar(onAddClick = { addFromClipboard() })

            PowerButton(
                connected = isConnected,
                onToggle = { toggleVpn() }
            )

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.End
            ) {
                Text(
                    if (isRefreshing) "Обновление..." else "Обновить подписку",
                    color = AccentPurple,
                    modifier = Modifier.clickable(enabled = !isRefreshing) { refreshSubscription() }
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (servers.isEmpty()) {
                EmptyState()
            } else {
                ServerList(
                    servers = servers,
                    selected = selectedServer,
                    onSelect = { selectedServer = it }
                )
            }
        }
    }
}

@Composable
fun EmptyState() {
    Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(
            "Серверов пока нет.\nСкопируй ссылку подписки или сервера и нажми «+» вверху.",
            color = TextSecondary,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}

@Composable
fun TopBar(onAddClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(20.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("⚙", color = TextPrimary)
        Text(
            "+",
            color = TextPrimary,
            modifier = Modifier.clickable { onAddClick() }
        )
    }
}

@Composable
fun PowerButton(connected: Boolean, onToggle: () -> Unit) {
    val ringColor = if (connected) Connected else AccentPurple
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(200.dp)
                .clip(CircleShape)
                .background(ringColor.copy(alpha = 0.15f))
                .clickable { onToggle() },
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(140.dp)
                    .clip(CircleShape)
                    .background(ringColor.copy(alpha = 0.25f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.PowerSettingsNew,
                    contentDescription = "Toggle VPN",
                    tint = TextPrimary,
                    modifier = Modifier.size(56.dp)
                )
            }
        }
    }
}

@Composable
fun ServerList(
    servers: List<ParsedServer>,
    selected: ParsedServer?,
    onSelect: (ParsedServer) -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        items(servers) { server ->
            ServerRow(
                server = server,
                isSelected = server == selected,
                onClick = { onSelect(server) }
            )
            Spacer(modifier = Modifier.height(10.dp))
        }
    }
}

@Composable
fun ServerRow(server: ParsedServer, isSelected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (isSelected) CardBg else CardBg.copy(alpha = 0.6f))
            .clickable { onClick() }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Флаг страны появится, когда добавим геолокацию по IP — пока нейтральная иконка.
            Text("🌐", modifier = Modifier.padding(end = 12.dp))
            Column {
                Text(server.name, color = TextPrimary, style = MaterialTheme.typography.titleMedium)
                Text(server.uiProtocolLine(), color = TextSecondary, style = MaterialTheme.typography.bodySmall)
            }
        }
        Text(">", color = TextSecondary)
    }
}
