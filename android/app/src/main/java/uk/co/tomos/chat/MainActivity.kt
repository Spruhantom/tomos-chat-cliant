package uk.co.tomos.chat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Void = Color(0xFF0A0A12)
private val Panel = Color(0xFF14141F)
private val Raised = Color(0xFF1D1D2B)
private val TextPrimary = Color(0xFFE8E8F0)
private val Muted = Color(0xFF8A8AA0)
private val Pink = Color(0xFFFF2D96)
private val Purple = Color(0xFFC74CFF)
private val Cyan = Color(0xFF00F0FF)
private val Green = Color(0xFF00FF9C)

private val TomChatColors = darkColorScheme(
    primary = Pink,
    secondary = Cyan,
    tertiary = Purple,
    background = Void,
    surface = Panel,
    surfaceVariant = Raised,
    onPrimary = Color.White,
    onSecondary = Void,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
    onSurfaceVariant = Muted
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = TomChatColors) {
                TomChatApp()
            }
        }
    }
}

private enum class Destination(val label: String, val glyph: String) {
    Chats("Chats", "▤"),
    Contacts("Contacts", "◎"),
    Calls("Calls", "⌕"),
    Settings("Settings", "⚙")
}

@Composable
private fun TomChatApp() {
    var destination by rememberSaveable { mutableStateOf(Destination.Chats) }
    Surface(modifier = Modifier.fillMaxSize(), color = Void) {
        Scaffold(
            containerColor = Void,
            topBar = { AppHeader() },
            bottomBar = {
                NavigationBar(containerColor = Panel, contentColor = TextPrimary) {
                    Destination.entries.forEach { item ->
                        NavigationBarItem(
                            selected = destination == item,
                            onClick = { destination = item },
                            icon = { Text(item.glyph, fontSize = 20.sp) },
                            label = { Text(item.label) }
                        )
                    }
                }
            }
        ) { padding ->
            Box(Modifier.padding(padding)) {
                when (destination) {
                    Destination.Chats -> ChatsScreen()
                    Destination.Contacts -> ContactsScreen()
                    Destination.Calls -> CallsScreen()
                    Destination.Settings -> SettingsScreen()
                }
            }
        }
    }
}

@Composable
private fun AppHeader() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Brush.horizontalGradient(listOf(Panel, Void)))
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .background(Brush.linearGradient(listOf(Pink, Purple)), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text("T", color = Color.White, fontWeight = FontWeight.Black, fontSize = 25.sp)
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text("TOMCHAT", color = TextPrimary, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
            Text("PEER TO PEER • ZERO SERVERS", color = Cyan, fontSize = 9.sp, letterSpacing = 1.sp)
        }
        Spacer(Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).background(Green, CircleShape))
            Spacer(Modifier.width(6.dp))
            Text("LOCAL", color = Muted, fontSize = 10.sp, letterSpacing = 1.sp)
        }
    }
}

@Composable
private fun ScreenTitle(title: String, subtitle: String) {
    Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 18.dp)) {
        Text(title, fontSize = 27.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
        Spacer(Modifier.height(4.dp))
        Text(subtitle, fontSize = 12.sp, color = Muted)
    }
}

@Composable
private fun ChatsScreen() {
    Column(Modifier.fillMaxSize()) {
        ScreenTitle("Your chats", "Private conversations stay between your devices.")
        StatusCard()
        Column(Modifier.padding(horizontal = 20.dp, vertical = 22.dp)) {
            Text("MESSAGES", color = Cyan, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
            Spacer(Modifier.height(12.dp))
            EmptyState(
                glyph = "✉",
                title = "Your conversations start here",
                body = "Connect directly with someone nearby or exchange a TomChat contact card. No account or server required."
            )
        }
    }
}

@Composable
private fun StatusCard() {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        colors = CardDefaults.cardColors(containerColor = Panel),
        border = BorderStroke(1.dp, Pink.copy(alpha = 0.45f)),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(38.dp).background(Raised, CircleShape), contentAlignment = Alignment.Center) {
                Text("⌁", color = Cyan, fontSize = 22.sp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Direct connection", color = TextPrimary, fontWeight = FontWeight.SemiBold)
                Text("No peer connected yet", color = Muted, fontSize = 11.sp)
            }
            Text("OFFLINE", color = Pink, fontSize = 9.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun ContactsScreen() {
    Column(Modifier.fillMaxSize()) {
        ScreenTitle("Contacts", "People you connect with directly.")
        EmptyState(
            glyph = "◎",
            title = "No contacts yet",
            body = "Your identity and contact exchange will be stored on this device. Nearby discovery and QR exchange are coming in the networking milestone."
        )
    }
}

@Composable
private fun CallsScreen() {
    Column(Modifier.fillMaxSize()) {
        ScreenTitle("Calls", "Private voice calls over direct peer connections.")
        EmptyState(
            glyph = "⌕",
            title = "No calls yet",
            body = "Voice calling will use authenticated peer connections. Bluetooth headset audio routing is planned; direct Bluetooth calling will be tested separately."
        )
    }
}

@Composable
private fun SettingsScreen() {
    Column(Modifier.fillMaxSize()) {
        ScreenTitle("Settings", "Your device. Your identity. Your connections.")
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                SettingCard("Network policy", "Strict peer-to-peer. No application servers, relays, push gateways or hosted discovery.", "ZERO SERVER")
            }
            item {
                SettingCard("Connection preference", "Automatic fastest direct route. Bluetooth and Wi-Fi options will appear when transport support is implemented.", "AUTOMATIC")
            }
            item {
                SettingCard("Identity and security", "Local identity, encrypted storage and contact verification are planned for the next phase.", "ON DEVICE")
            }
            item {
                SettingCard("Backups", "Encrypted local export and encrypted replication to trusted personal devices.", "PLANNED")
            }
            item {
                SettingCard("TomSocial", "A future decentralised social space integrated into TomChat after core messaging is stable.", "FUTURE")
            }
            item {
                Text("TomChat 0.1.0 • Android foundation", color = Muted, fontSize = 10.sp, modifier = Modifier.padding(vertical = 12.dp))
            }
        }
    }
}

@Composable
private fun SettingCard(title: String, description: String, tag: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Panel),
        border = BorderStroke(1.dp, Raised),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = TextPrimary, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(tag, color = Cyan, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(7.dp))
            Text(description, color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
        }
    }
}

@Composable
private fun EmptyState(glyph: String, title: String, body: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 30.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier.size(76.dp).background(Panel, RoundedCornerShape(24.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(glyph, color = Pink, fontSize = 34.sp)
        }
        Spacer(Modifier.height(18.dp))
        Text(title, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 17.sp)
        Spacer(Modifier.height(8.dp))
        Text(body, color = Muted, fontSize = 12.sp, lineHeight = 19.sp)
    }
}
