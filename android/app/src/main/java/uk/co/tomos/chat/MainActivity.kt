package uk.co.tomos.chat

import android.os.Bundle
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Checkbox
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import java.io.IOException
import java.security.KeyPair
import java.time.LocalDate
import java.time.format.DateTimeParseException
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker

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
                var foldingFeature by remember { mutableStateOf<FoldingFeature?>(null) }
                val windowInfoTracker = remember { WindowInfoTracker.getOrCreate(this@MainActivity) }

                LaunchedEffect(windowInfoTracker) {
                    windowInfoTracker.windowLayoutInfo(this@MainActivity).collect { layoutInfo ->
                        foldingFeature = layoutInfo.displayFeatures
                            .filterIsInstance<FoldingFeature>()
                            .firstOrNull { it.isSeparating }
                            ?: layoutInfo.displayFeatures.filterIsInstance<FoldingFeature>().firstOrNull()
                    }
                }
                val scope = rememberCoroutineScope()
                val store = remember { AccountProfileStore(applicationContext.filesDir) }
                val identityStore = remember { DeviceIdentityStore() }
                val noiseIdentityStore = remember { NoiseIdentityStore(applicationContext.filesDir) }

                LaunchedEffect(store) {
                    try {
                        val loaded = withContext(Dispatchers.IO) {
                            val saved = store.load()
                            val identity = identityStore.getOrCreate()
                            val noiseIdentity = noiseIdentityStore.getOrCreate()
                            val card = saved?.let { ContactCardCodec.encode(ContactCardCodec.create(it.displayName, identity, noiseIdentity.publicKey)) }
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
                    profile != null -> TomChatApp(profile!!, contactCard, foldingFeature)
                    else -> AccountSetupScreen { newProfile ->
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) { store.save(newProfile) }
                                profile = newProfile
                                val identities = withContext(Dispatchers.IO) {
                                    identityStore.getOrCreate() to noiseIdentityStore.getOrCreate()
                                }
                                contactCard = ContactCardCodec.encode(ContactCardCodec.create(newProfile.displayName, identities.first, identities.second.publicKey))
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
        modifier = Modifier.fillMaxSize().background(Void).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 28.dp),
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
private fun TomChatApp(
    profile: AccountProfile,
    contactCard: String?,
    foldingFeature: FoldingFeature?
) {
    var destination by rememberSaveable { mutableStateOf(Destination.Chats) }
    val foldPosture = remember(foldingFeature) { FoldPosture.from(foldingFeature) }
    val density = LocalDensity.current
    val hingeGutter = remember(foldingFeature, density) {
        with(density) {
            val reportedPx = foldingFeature?.let {
                if (it.orientation == FoldingFeature.Orientation.VERTICAL) it.bounds.width()
                else it.bounds.height()
            } ?: 0
            reportedPx.toDp().coerceIn(8.dp, 48.dp)
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = Void) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val expanded = maxWidth >= 840.dp
            val medium = maxWidth >= 600.dp
            Row(Modifier.fillMaxSize()) {
                if (medium && foldPosture != FoldPosture.BOOK) {
                    NavigationRail(containerColor = Panel, contentColor = TextPrimary) {
                        Destination.entries.forEach { item ->
                            NavigationRailItem(
                                selected = destination == item,
                                onClick = { destination = item },
                                icon = { Text(item.glyph, fontSize = 20.sp) },
                                label = { Text(item.label) },
                                alwaysShowLabel = true
                            )
                        }
                    }
                }
                Scaffold(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    containerColor = Void,
                    topBar = {
                        Column {
                            AppHeader()
                            if (foldPosture == FoldPosture.BOOK || foldPosture == FoldPosture.TABLETOP) {
                                FoldPostureBanner(foldPosture)
                            }
                        }
                    },
                    bottomBar = {
                        if (!medium && foldPosture != FoldPosture.BOOK && foldPosture != FoldPosture.TABLETOP) {
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
                    }
                ) { padding ->
                    Box(
                        Modifier.fillMaxSize().padding(padding),
                        contentAlignment = Alignment.TopCenter
                    ) {
                        Box(Modifier.fillMaxSize().widthIn(max = if (expanded) 1200.dp else 840.dp)) {
                            when (foldPosture) {
                                FoldPosture.BOOK -> Row(Modifier.fillMaxSize()) {
                                    Box(Modifier.weight(1.15f).fillMaxHeight()) {
                                        DestinationScreen(destination, profile, contactCard)
                                    }
                                    Spacer(Modifier.width(hingeGutter))
                                    FoldCompanionPane(
                                        modifier = Modifier.weight(0.85f).fillMaxHeight(),
                                        title = "BOOK MODE",
                                        description = "TomChat keeps the main workspace on one side of the fold and quick navigation on the other."
                                    ) { destination = it }
                                }
                                FoldPosture.TABLETOP -> Column(Modifier.fillMaxSize()) {
                                    Box(Modifier.weight(1.15f).fillMaxWidth()) {
                                        DestinationScreen(destination, profile, contactCard)
                                    }
                                    Spacer(Modifier.height(hingeGutter))
                                    FoldCompanionPane(
                                        modifier = Modifier.weight(0.85f).fillMaxWidth(),
                                        title = "TABLETOP MODE",
                                        description = "Use this lower panel to switch between chats, contacts, calls and settings while the main view stays above the hinge."
                                    ) { destination = it }
                                }
                                else -> DestinationScreen(destination, profile, contactCard)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DestinationScreen(destination: Destination, profile: AccountProfile, contactCard: String?) {
    when (destination) {
        Destination.Chats -> ChatsScreen(profile)
        Destination.Contacts -> ContactsScreen(profile, contactCard)
        Destination.Calls -> CallsScreen()
        Destination.Settings -> SettingsScreen(profile)
    }
}

@Composable
private fun FoldPostureBanner(posture: FoldPosture) {
    val label = if (posture == FoldPosture.BOOK) "BOOK POSTURE DETECTED" else "TABLETOP POSTURE DETECTED"
    Text(
        label,
        color = Cyan,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
        modifier = Modifier.fillMaxWidth().background(Panel).padding(horizontal = 18.dp, vertical = 8.dp)
    )
}

@Composable
private fun FoldCompanionPane(
    modifier: Modifier,
    title: String,
    description: String,
    onDestinationSelected: (Destination) -> Unit
) {
    Column(
        modifier.background(Panel, RoundedCornerShape(18.dp)).padding(16.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(title, color = Cyan, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Spacer(Modifier.height(8.dp))
        Text(description, color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
        Spacer(Modifier.height(12.dp))
        Destination.entries.forEach { item ->
            TextButton(onClick = { onDestinationSelected(item) }, modifier = Modifier.fillMaxWidth()) {
                Text("${item.glyph}  ${item.label}", color = if (item == Destination.Chats) Pink else TextPrimary)
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
private fun ChatsScreen(profile: AccountProfile) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val contactStore = remember { TrustedContactStore(context.filesDir) }
    var coordinator by remember { mutableStateOf<PeerMessagingCoordinator?>(null) }
    var trusted by remember { mutableStateOf<List<TrustedContact>>(emptyList()) }
    var selectedContact by remember { mutableStateOf<TrustedContact?>(null) }
    var address by rememberSaveable { mutableStateOf("") }
    var port by rememberSaveable { mutableStateOf("47821") }
    var bindAddress by rememberSaveable { mutableStateOf("0.0.0.0") }
    var listenPort by rememberSaveable { mutableStateOf("47821") }
    var listening by remember { mutableStateOf(false) }
    var session by remember { mutableStateOf<PeerConversationSession?>(null) }
    var messages by remember { mutableStateOf<List<StoredChatMessage>>(emptyList()) }
    var draft by rememberSaveable { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("Preparing device encryption…") }
    var errorText by remember { mutableStateOf<String?>(null) }
    var callSnapshot by remember { mutableStateOf<PeerCallSnapshot?>(null) }
    var pendingCallAction by remember { mutableStateOf("call") }
    var callMuted by remember { mutableStateOf(false) }
    var callSpeaker by remember { mutableStateOf(false) }

    val callController = remember(session, selectedContact) {
        val activeSession = session
        val peer = selectedContact
        if (activeSession != null && peer != null) {
            PeerVoiceCallController(context, activeSession, peer.fingerprint, onError = { error ->
                scope.launch { errorText = error.message ?: "Voice call audio failed." }
            })
        } else null
    }
    val microphonePermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            try {
                if (pendingCallAction == "accept") callController?.acceptIncoming()
                else callController?.startOutgoing()
                callSnapshot = callController?.snapshot
                callMuted = false
                callSpeaker = false
                errorText = null
            } catch (error: Exception) {
                errorText = error.message ?: "Could not start the voice call."
            }
        } else {
            errorText = "Microphone permission is required to make or answer a voice call."
        }
    }

    LaunchedEffect(context.filesDir) {
        try {
            val loaded = withContext(Dispatchers.IO) {
                val signing = DeviceIdentityStore().getOrCreate()
                val noise = NoiseIdentityStore(context.filesDir).getOrCreate()
                PeerMessagingCoordinator(context.filesDir, signing, noise) to contactStore.load()
            }
            coordinator = loaded.first
            trusted = loaded.second
            statusText = "Ready for a direct encrypted connection"
        } catch (error: Exception) {
            errorText = error.message ?: "Could not initialise encrypted messaging."
            statusText = "Encryption unavailable"
        }
    }

    DisposableEffect(coordinator) { onDispose { coordinator?.close() } }
    DisposableEffect(session) { onDispose { session?.close() } }
    DisposableEffect(callController) {
        callSnapshot = callController?.snapshot
        onDispose { callController?.close() }
    }

    LaunchedEffect(session) {
        val active = session ?: return@LaunchedEffect
        val peer = selectedContact ?: return@LaunchedEffect
        while (true) {
            try {
                val event = withContext(Dispatchers.IO) { active.receiveEvent() }
                withContext(Dispatchers.IO) { callController?.handle(event) }
                callSnapshot = callController?.snapshot
                when (event) {
                    is PeerConversationEvent.MessageReceived,
                    is PeerConversationEvent.DeliveryConfirmed,
                    is PeerConversationEvent.UnmatchedDeliveryAcknowledgement,
                    PeerConversationEvent.DuplicateMessage,
                    is PeerConversationEvent.VoiceSignalReceived,
                    is PeerConversationEvent.VoiceFrameReceived -> Unit
                }
                messages = withContext(Dispatchers.IO) { coordinator?.loadHistory(peer.fingerprint).orEmpty() }
            } catch (error: Exception) {
                statusText = "Connection ended"
                errorText = error.message ?: "The encrypted connection was interrupted."
                session = null
                break
            }
        }
    }

    val mayMessage = AccountSafetyPolicy.allows(profile.safetyState(), TomChatCapability.MESSAGE_APPROVED_CONTACT)
    val mayCall = AccountSafetyPolicy.allows(profile.safetyState(), TomChatCapability.VOICE_CALL_APPROVED_CONTACT)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 20.dp)) {
        ScreenTitle("Your chats", "Encrypted, direct conversations between trusted devices.")
        StatusCard(isConnected = session?.status == PeerConnectionStatus.CONNECTED, connectionText = statusText)
        if (!mayMessage) {
            EmptyState("⌑", "Messaging is locked", AccountSafetyPolicy.restrictionReason(profile.safetyState()))
            return@Column
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Text("TRUSTED CONTACTS", color = Cyan, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
            if (trusted.isEmpty()) Text("Add a contact by scanning and independently verifying their signed QR card first.", color = Muted, fontSize = 12.sp)
            trusted.forEach { contact ->
                Card(Modifier.fillMaxWidth().padding(top = 8.dp), colors = CardDefaults.cardColors(containerColor = Panel), border = BorderStroke(1.dp, if (selectedContact?.fingerprint == contact.fingerprint) Cyan else Raised)) {
                    Column(Modifier.padding(12.dp)) {
                        Text(contact.displayName, color = TextPrimary, fontWeight = FontWeight.SemiBold)
                        Text(contact.fingerprint, color = Muted, fontSize = 9.sp)
                        Text(if (contact.noiseStaticPublicKey != null) "Signed Noise key available" else "Legacy card — encrypted connection unavailable", color = if (contact.noiseStaticPublicKey != null) Green else Color(0xFFFFE14D), fontSize = 10.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = {
                                session?.close(); session = null
                                selectedContact = contact
                                messages = emptyList(); errorText = null
                                scope.launch {
                                    try { messages = withContext(Dispatchers.IO) { coordinator?.loadHistory(contact.fingerprint).orEmpty() } }
                                    catch (error: Exception) { errorText = error.message ?: "Could not load encrypted history." }
                                }
                            }) { Text("Open") }
                            TextButton(enabled = coordinator != null && contact.noiseStaticPublicKey != null && !working, onClick = {
                                session?.close(); session = null
                                selectedContact = contact; working = true; errorText = null
                                scope.launch {
                                    try {
                                        val active = withContext(Dispatchers.IO) { coordinator!!.connect(address.trim(), port.toInt(), contact) }
                                        session?.close(); session = active
                                        messages = withContext(Dispatchers.IO) { coordinator!!.loadHistory(contact.fingerprint) }
                                        statusText = "Encrypted connection established"
                                    } catch (error: Exception) { errorText = error.message ?: "Could not connect securely."; statusText = "Connection failed" }
                                    finally { working = false }
                                }
                            }) { Text("Connect") }
                            TextButton(enabled = coordinator != null && contact.noiseStaticPublicKey != null && !working && !listening, onClick = {
                                session?.close(); session = null
                                selectedContact = contact; working = true; errorText = null
                                scope.launch {
                                    try {
                                        val actualPort = withContext(Dispatchers.IO) { coordinator!!.startListening(bindAddress.trim(), listenPort.toInt()) }
                                        listening = true
                                        statusText = "Listening on port $actualPort — waiting for ${contact.displayName}"
                                        val active = withContext(Dispatchers.IO) { coordinator!!.accept(contact) }
                                        session?.close(); session = active
                                        messages = withContext(Dispatchers.IO) { coordinator!!.loadHistory(contact.fingerprint) }
                                        listening = false; statusText = "Encrypted connection established"
                                    } catch (error: Exception) { listening = false; errorText = error.message ?: "Could not accept a secure connection."; statusText = "Listener stopped" }
                                    finally { working = false }
                                }
                            }) { Text(if (listening) "Waiting…" else "Listen") }
                        }
                    }
                }
            }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp)) {
            Text("DIRECT CONNECTION", color = Cyan, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
            OutlinedTextField(address, { address = it.take(253) }, Modifier.fillMaxWidth(), label = { Text("Peer IP address") }, singleLine = true)
            OutlinedTextField(port, { port = it.filter(Char::isDigit).take(5) }, Modifier.fillMaxWidth(), label = { Text("Peer port") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
            Text("On the receiving device, select the same trusted contact and use Listen.", color = Muted, fontSize = 11.sp)
            OutlinedTextField(bindAddress, { bindAddress = it.take(253) }, Modifier.fillMaxWidth(), label = { Text("Local interface to listen on") }, singleLine = true)
            OutlinedTextField(listenPort, { listenPort = it.filter(Char::isDigit).take(5) }, Modifier.fillMaxWidth(), label = { Text("Local listening port") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
            Text(statusText, color = if (session != null) Green else Muted, fontSize = 11.sp)
            if (errorText != null) Text(errorText!!, color = Color(0xFFFF6B83), fontSize = 11.sp)
        }
        selectedContact?.let { contact ->
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                Text("CONVERSATION WITH ${contact.displayName.uppercase()}", color = Cyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                if (session == null) Text("Messages and calls require mutual identity verification and an established Noise XX channel.", color = Muted, fontSize = 11.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        enabled = mayCall && session?.status == PeerConnectionStatus.CONNECTED &&
                            (callSnapshot == null || callSnapshot?.state == PeerCallState.ENDED || callSnapshot?.state == PeerCallState.FAILED),
                        onClick = {
                            pendingCallAction = "call"
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                try {
                                    callController?.startOutgoing()
                                    callSnapshot = callController?.snapshot
                                    errorText = null
                                } catch (error: Exception) { errorText = error.message ?: "Could not start the call." }
                            } else microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    ) { Text("Voice call") }
                    callSnapshot?.let { call ->
                        Text(
                            when (call.state) {
                                PeerCallState.OUTGOING -> "Calling…"
                                PeerCallState.INCOMING -> "Incoming call"
                                PeerCallState.CONNECTING -> "Connecting audio…"
                                PeerCallState.ACTIVE -> "Call active"
                                PeerCallState.RECONNECTING -> "Reconnecting…"
                                PeerCallState.ENDED -> "Call ended"
                                PeerCallState.FAILED -> "Call failed"
                                PeerCallState.IDLE -> "Idle"
                            },
                            color = if (call.state == PeerCallState.ACTIVE) Green else Muted,
                            fontSize = 11.sp
                        )
                    }
                }
                if (callSnapshot?.state == PeerCallState.INCOMING) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = mayCall, onClick = {
                            pendingCallAction = "accept"
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                try {
                                    callController?.acceptIncoming()
                                    callSnapshot = callController?.snapshot
                                    errorText = null
                                } catch (error: Exception) { errorText = error.message ?: "Could not answer the call." }
                            } else microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
                        }) { Text("Answer") }
                        TextButton(onClick = {
                            runCatching { callController?.rejectIncoming() }
                            callSnapshot = callController?.snapshot
                        }) { Text("Reject") }
                    }
                }
                if (callSnapshot?.state == PeerCallState.ACTIVE) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = {
                            callMuted = !callMuted
                            runCatching { callController?.setMuted(callMuted) }
                        }) { Text(if (callMuted) "Unmute" else "Mute") }
                        TextButton(onClick = {
                            callSpeaker = !callSpeaker
                            val routed = runCatching { callController?.setSpeakerphoneEnabled(callSpeaker) == true }.getOrDefault(false)
                            if (!routed) { callSpeaker = false; errorText = "That audio route is not available on this device." }
                        }) { Text(if (callSpeaker) "Earpiece" else "Speaker") }
                        Button(onClick = {
                            callController?.hangUp()
                            callSnapshot = callController?.snapshot
                        }) { Text("Hang up") }
                    }
                }
                messages.forEach { message ->
                    Card(Modifier.fillMaxWidth().padding(vertical = 3.dp), colors = CardDefaults.cardColors(containerColor = if (message.direction == StoredMessageDirection.OUTGOING) Raised else Panel)) {
                        Column(Modifier.padding(10.dp)) {
                            Text(message.body, color = TextPrimary, fontSize = 13.sp)
                            Text("${if (message.direction == StoredMessageDirection.OUTGOING) "YOU" else contact.displayName.uppercase()} • ${message.status.name}", color = Muted, fontSize = 9.sp)
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(draft, { draft = it.take(16_384) }, Modifier.weight(1f), label = { Text("Encrypted message") }, enabled = session?.status == PeerConnectionStatus.CONNECTED, maxLines = 3)
                    TextButton(enabled = session?.status == PeerConnectionStatus.CONNECTED && draft.isNotBlank() && !working, onClick = {
                        val text = draft.trim(); val active = session ?: return@TextButton; draft = ""; working = true
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) { active.sendText(text) }
                                messages = withContext(Dispatchers.IO) { coordinator?.loadHistory(contact.fingerprint).orEmpty() }
                            } catch (error: Exception) { errorText = error.message ?: "Message could not be sent."; statusText = "Message send failed" }
                            finally { working = false }
                        }
                    }) { Text("Send") }
                }
            }
        }
    }
}
@Composable
private fun StatusCard(isConnected: Boolean, connectionText: String) {
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
                Text(connectionText, color = Muted, fontSize = 11.sp)
            }
            Text(if (isConnected) "ONLINE" else "OFFLINE", color = if (isConnected) Green else Pink, fontSize = 9.sp, fontWeight = FontWeight.Bold)
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

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
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
            val trustedMatch = trusted.firstOrNull { it.fingerprint == card.fingerprint }
            val sameNoiseKey = trustedMatch?.let { existing ->
                val savedKey = existing.noiseStaticPublicKey
                val presentedKey = card.noiseStaticPublicKey
                if (savedKey == null || presentedKey == null) savedKey == null && presentedKey == null
                else savedKey.contentEquals(presentedKey)
            } == true
            val alreadyTrusted = trustedMatch != null && sameNoiseKey
            val noiseKeyChanged = trustedMatch?.noiseStaticPublicKey != null &&
                card.noiseStaticPublicKey != null && !sameNoiseKey
            val noiseKeyDowngrade = trustedMatch?.noiseStaticPublicKey != null && card.noiseStaticPublicKey == null
            val hasNoiseBinding = card.noiseStaticPublicKey != null
            Card(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), colors = CardDefaults.cardColors(containerColor = Panel), border = BorderStroke(1.dp, Cyan)) {
                Column(Modifier.padding(16.dp)) {
                    Text("VERIFY CONTACT", color = Cyan, fontWeight = FontWeight.Bold)
                    Text(card.displayName, color = TextPrimary, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                    Text("Fingerprint", color = Muted, fontSize = 11.sp)
                    Text(card.fingerprint, color = TextPrimary, fontSize = 12.sp)
                    Text("The P-256 identity signature covers the Noise X25519 key. Compare the identity fingerprint with the other person through a separate trusted channel. A valid signature does not prove the person's real-world identity.", color = Muted, fontSize = 12.sp)
                    if (!hasNoiseBinding) {
                        Text("This is a legacy contact card without a signed Noise key. It cannot be trusted for encrypted sessions; ask the contact to update TomChat and share a new QR code.", color = Color(0xFFFFE14D), fontSize = 12.sp)
                    } else {
                        Text("Noise static key is cryptographically bound to this identity.", color = Green, fontSize = 10.sp)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = fingerprintCompared, onCheckedChange = { fingerprintCompared = it })
                        Text("I independently compared the full fingerprint", color = TextPrimary, fontSize = 12.sp)
                    }
                    if (alreadyTrusted) {
                        Text("Already trusted on this device.", color = Green, fontSize = 12.sp)
                    } else {
                        if (noiseKeyChanged) {
                            Text("WARNING: this contact has presented a different Noise key. Only approve it if you independently re-verified the unchanged identity fingerprint with the contact.", color = Color(0xFFFFE14D), fontSize = 12.sp)
                        } else if (noiseKeyDowngrade) {
                            Text("WARNING: this card omits the Noise key previously bound to this identity. It cannot replace the trusted card.", color = Color(0xFFFFE14D), fontSize = 12.sp)
                        }
                        Button(enabled = fingerprintCompared && hasNoiseBinding && !saving, onClick = {
                            saving = true
                            scope.launch {
                                try {
                                    val updated = trusted.filterNot { it.fingerprint == card.fingerprint } + TrustedContact(card.displayName, card.encodedPublicKey, card.fingerprint, card.noiseStaticPublicKey)
                                    withContext(Dispatchers.IO) { store.save(updated) }
                                    trusted = updated; scanned = null; scanError = null
                                } catch (error: Exception) { scanError = error.message ?: "Could not save this contact." }
                                finally { saving = false }
                            }
                        }, modifier = Modifier.fillMaxWidth()) { Text(if (saving) "Saving…" else if (noiseKeyChanged) "Approve changed Noise key" else "Trust and save contact") }
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
                        Text(if (contact.noiseStaticPublicKey != null) "Noise identity bound" else "Legacy identity — no Noise binding", color = if (contact.noiseStaticPublicKey != null) Green else Color(0xFFFFE14D), fontSize = 10.sp)
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
            title = "Start a private voice call",
            body = "Open Chats, connect to a trusted contact using the direct encrypted connection, then tap Voice call. Keep the chat open during this prototype call. Microphone permission is requested only when you call or answer. Bluetooth and wired headsets use Android's normal audio routing."
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
                SettingCard("Identity and security", "P-256 signing identity is bound to a persistent X25519 Noise key. Both private identities are protected by Android Keystore-backed storage; contact cards sign the binding.", "PROTOTYPE")
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
