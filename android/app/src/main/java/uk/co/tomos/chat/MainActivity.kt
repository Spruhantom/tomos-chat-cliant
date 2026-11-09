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
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Checkbox
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.security.KeyPair
import java.time.LocalDate
import java.time.format.DateTimeParseException

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
                var profile by remember { mutableStateOf<AccountProfile?>(null) }
                var contactCard by remember { mutableStateOf<String?>(null) }
                var loading by remember { mutableStateOf(true) }
                var storageError by remember { mutableStateOf<String?>(null) }
                val scope = rememberCoroutineScope()
                val store = remember { AccountProfileStore(applicationContext.filesDir) }
                val identityStore = remember { DeviceIdentityStore() }

                LaunchedEffect(store) {
                    try {
                        val loaded = withContext(Dispatchers.IO) {
                            val saved = store.load()
                            val identity = identityStore.getOrCreate()
                            val card = saved?.let { ContactCardCodec.encode(ContactCardCodec.create(it.displayName, identity)) }
                            saved to card
                        }
                        profile = loaded.first
                        contactCard = loaded.second
                    } catch (error: IOException) {
                        storageError = error.message ?: "The saved account could not be opened."
                    } finally {
                        loading = false
                    }
                }

                when {
                    loading -> ProfileStatusScreen("Opening your encrypted account…")
                    storageError != null -> ProfileErrorScreen(storageError!!)
                    profile != null -> TomChatApp(profile!!, contactCard)
                    else -> AccountSetupScreen { newProfile ->
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) { store.save(newProfile) }
                                profile = newProfile
                                val identity = withContext(Dispatchers.IO) { identityStore.getOrCreate() }
                                contactCard = ContactCardCodec.encode(ContactCardCodec.create(newProfile.displayName, identity))
                                storageError = null
                            } catch (error: IOException) {
                                storageError = error.message ?: "The encrypted account could not be saved."
                            }
                        }
                    }
                }
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
private fun ProfileStatusScreen(message: String) {
    Box(Modifier.fillMaxSize().background(Void), contentAlignment = Alignment.Center) {
        Text(message, color = Muted)
    }
}

@Composable
private fun ProfileErrorScreen(message: String) {
    Column(
        Modifier.fillMaxSize().background(Void).padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text("Account storage unavailable", color = Pink, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Text(message, color = Muted, fontSize = 13.sp)
        Spacer(Modifier.height(12.dp))
        Text("TomChat has not replaced or reset your saved profile. Resolve the storage/key problem before continuing.", color = Color(0xFFFFE14D), fontSize = 12.sp)
    }
}

@Composable
private fun AccountSetupScreen(onAssigned: (AccountProfile) -> Unit) {
    var displayName by rememberSaveable { mutableStateOf("") }
    var dateOfBirth by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var assessedBand by rememberSaveable { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier.fillMaxSize().background(Void).padding(horizontal = 24.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text("TOMCHAT", color = Pink, fontSize = 13.sp, fontWeight = FontWeight.Black, letterSpacing = 3.sp)
        Spacer(Modifier.height(10.dp))
        Text("Let's set up your account", color = TextPrimary, fontSize = 27.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text("Your date of birth determines which age-appropriate TomChat experience and protections are assigned.", color = Muted, fontSize = 13.sp, lineHeight = 20.sp)
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            value = displayName,
            onValueChange = { displayName = it.take(64); error = null; assessedBand = null },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Display name") },
            singleLine = true
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = dateOfBirth,
            onValueChange = { dateOfBirth = it; error = null; assessedBand = null },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Date of birth (YYYY-MM-DD)") },
            placeholder = { Text("2012-04-23") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii)
        )
        Spacer(Modifier.height(10.dp))
        Text("Your full date of birth should not be retained in the finished account. Age assurance and guardian consent will be separate steps.", color = Muted, fontSize = 11.sp, lineHeight = 17.sp)
        if (error != null) {
            Spacer(Modifier.height(10.dp))
            Text(error!!, color = Color(0xFFFF6B83), fontSize = 12.sp)
        }
        if (assessedBand != null) {
            val band = AgeBand.valueOf(assessedBand!!)
            Spacer(Modifier.height(14.dp))
            Card(colors = CardDefaults.cardColors(containerColor = Panel), border = BorderStroke(1.dp, Cyan.copy(alpha = 0.4f))) {
                Column(Modifier.padding(14.dp)) {
                    Text("ASSIGNED EXPERIENCE", color = Cyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Text(band.title, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text(band.description, color = Muted, fontSize = 12.sp)
                    Spacer(Modifier.height(8.dp))
                    Text("Prototype only: this does not verify age or guardian consent. Restricted features must remain locked until proper checks are implemented.", color = Color(0xFFFFE14D), fontSize = 11.sp, lineHeight = 16.sp)
                }
            }
        }
        Spacer(Modifier.height(18.dp))
        Button(
            onClick = {
                val name = displayName.trim()
                if (name.isEmpty()) {
                    error = "Please enter a display name."
                } else {
                    try {
                        val dob = LocalDate.parse(dateOfBirth.trim())
                        when (val assessment = AgeBandPolicy.assess(dob, LocalDate.now())) {
                            AgeAssessment.InvalidFutureDate -> error = "Date of birth cannot be in the future."
                            is AgeAssessment.Assigned -> {
                                if (assessment.band == AgeBand.UNDER_FIVE) {
                                    error = "A guardian-led setup flow is required for this age group. Independent setup is not available."
                                    assessedBand = null
                                } else {
                                    assessedBand = assessment.band.name
                                    error = null
                                }
                            }
                        }
                    } catch (_: DateTimeParseException) {
                        error = "Enter a valid date in YYYY-MM-DD format."
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Determine account experience") }
        if (assessedBand != null) {
            Spacer(Modifier.height(8.dp))
            Button(onClick = {
                val band = AgeBand.valueOf(assessedBand!!)
                onAssigned(
                    AccountProfile(
                        displayName = displayName.trim(),
                        ageBand = band,
                        ageAssurance = AgeAssuranceStatus.NOT_VERIFIED,
                        guardianConsent = if (band == AgeBand.ADULT_18_PLUS) GuardianConsentStatus.NOT_REQUIRED else GuardianConsentStatus.PENDING
                    )
                )
            }, modifier = Modifier.fillMaxWidth()) {
                Text("Continue to TomChat")
            }
        }
        Spacer(Modifier.height(16.dp))
        Text("DIRECT PEER-TO-PEER • NO APPLICATION SERVERS", color = Cyan, fontSize = 9.sp, letterSpacing = 1.sp)
    }
}

@Composable
private fun TomChatApp(profile: AccountProfile, contactCard: String?) {
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
                    Destination.Contacts -> ContactsScreen(profile, contactCard)
                    Destination.Calls -> CallsScreen()
                    Destination.Settings -> SettingsScreen(profile)
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
private fun ContactsScreen(profile: AccountProfile, contactCard: String?) {
    val context = LocalContext.current
    val store = remember { TrustedContactStore(context.filesDir) }
    val scope = rememberCoroutineScope()
    var trusted by remember { mutableStateOf<List<TrustedContact>>(emptyList()) }
    var scanOpen by remember { mutableStateOf(false) }
    var scanned by remember { mutableStateOf<ContactCard?>(null) }
    var scanError by remember { mutableStateOf<String?>(null) }
    var fingerprintCompared by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }

    LaunchedEffect(store) {
        try { trusted = withContext(Dispatchers.IO) { store.load() } }
        catch (error: IOException) { scanError = error.message ?: "Could not open trusted contacts." }
    }

    if (scanOpen) {
        ContactQrScanner(
            onScanned = { payload ->
                scanOpen = false
                try { scanned = ContactCardCodec.decode(payload); fingerprintCompared = false; scanError = null }
                catch (error: Exception) { scanError = error.message ?: "This is not a valid TomChat contact card." }
            },
            onCancel = { scanOpen = false }
        )
        return
    }

    Column(Modifier.fillMaxSize()) {
        ScreenTitle("Contacts", "People you connect with directly.")
        val safety = profile.safetyState()
        val mayExchange = AccountSafetyPolicy.allows(safety, TomChatCapability.EXCHANGE_CONTACT_CARD)
        if (contactCard == null) {
            EmptyState("◎", "Device identity unavailable", "TomChat could not prepare this device's signed contact card.")
        } else if (!mayExchange) {
            EmptyState("◎", "Contact exchange is locked", AccountSafetyPolicy.restrictionReason(safety))
        } else {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                val bitmap = remember(contactCard) { ContactQrRenderer.render(contactCard).asImageBitmap() }
                Card(colors = CardDefaults.cardColors(containerColor = Color.White), border = BorderStroke(1.dp, Cyan)) {
                    Image(bitmap = bitmap, contentDescription = "Your signed TomChat contact QR code", modifier = Modifier.size(220.dp).padding(10.dp))
                }
                Text("YOUR SIGNED CONTACT CARD", color = Cyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Text("Compare fingerprints in person before trusting each other.", color = Muted, fontSize = 12.sp)
                Spacer(Modifier.height(12.dp))
                Button(onClick = { scanOpen = true; scanned = null; scanError = null }, modifier = Modifier.fillMaxWidth()) {
                    Text("Scan another person's QR")
                }
            }
        }
        if (scanError != null) Text(scanError!!, color = Color(0xFFFF6B83), modifier = Modifier.padding(20.dp))
        scanned?.let { card ->
            val alreadyTrusted = trusted.any { it.fingerprint == card.fingerprint }
            Card(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), colors = CardDefaults.cardColors(containerColor = Panel), border = BorderStroke(1.dp, Cyan)) {
                Column(Modifier.padding(16.dp)) {
                    Text("VERIFY CONTACT", color = Cyan, fontWeight = FontWeight.Bold)
                    Text(card.displayName, color = TextPrimary, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                    Text("Fingerprint", color = Muted, fontSize = 11.sp)
                    Text(card.fingerprint, color = TextPrimary, fontSize = 12.sp)
                    Text("Compare every group with the other person's device through a separate trusted channel. A valid signature does not prove the person's real-world identity.", color = Muted, fontSize = 12.sp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = fingerprintCompared, onCheckedChange = { fingerprintCompared = it })
                        Text("I independently compared the full fingerprint", color = TextPrimary, fontSize = 12.sp)
                    }
                    if (alreadyTrusted) {
                        Text("Already trusted on this device.", color = Green, fontSize = 12.sp)
                    } else {
                        Button(enabled = fingerprintCompared && !saving, onClick = {
                            saving = true
                            scope.launch {
                                try {
                                    val updated = trusted + TrustedContact(card.displayName, card.encodedPublicKey, card.fingerprint)
                                    withContext(Dispatchers.IO) { store.save(updated) }
                                    trusted = updated; scanned = null; scanError = null
                                } catch (error: Exception) { scanError = error.message ?: "Could not save this contact." }
                                finally { saving = false }
                            }
                        }, modifier = Modifier.fillMaxWidth()) { Text(if (saving) "Saving…" else "Trust and save contact") }
                    }
                    TextButton(onClick = { scanned = null; fingerprintCompared = false }) { Text("Reject contact") }
                }
            }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Text("TRUSTED CONTACTS (${trusted.size})", color = Cyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            if (trusted.isEmpty()) Text("No trusted contacts saved on this device.", color = Muted, fontSize = 12.sp)
            trusted.forEach { contact ->
                Card(colors = CardDefaults.cardColors(containerColor = Panel), modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text(contact.displayName, color = TextPrimary, fontWeight = FontWeight.SemiBold)
                        Text(contact.fingerprint, color = Muted, fontSize = 10.sp)
                        TextButton(onClick = {
                            scope.launch {
                                val updated = trusted.filterNot { it.fingerprint == contact.fingerprint }
                                try { withContext(Dispatchers.IO) { store.save(updated) }; trusted = updated }
                                catch (error: Exception) { scanError = error.message ?: "Could not remove this contact." }
                            }
                        }) { Text("Remove trust") }
                    }
                }
            }
        }
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
private fun SettingsScreen(profile: AccountProfile) {
    Column(Modifier.fillMaxSize()) {
        ScreenTitle("Settings", "Your device. Your identity. Your connections.")
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                val safetyState = AccountSafetyState(
                    ageBand = profile.ageBand,
                    ageAssurance = profile.ageAssurance,
                    guardianConsent = profile.guardianConsent
                )
                SettingCard(
                    "Account experience",
                    "${profile.displayName} • ${profile.ageBand.title}: ${profile.ageBand.description} ${AccountSafetyPolicy.restrictionReason(safetyState)}",
                    "LOCKED"
                )
                SettingCard(
                    "Safety permissions",
                    "Messaging, calls, file sharing and social capabilities are checked by the central account policy. They remain disabled in this prototype until age assurance (and guardian approval for minors) is completed.",
                    "RESTRICTED"
                )
            }
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
