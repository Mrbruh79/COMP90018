package com.example.blap.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.blap.chat.ChatConnectionState
import com.example.blap.chat.ChatMessage
import com.example.blap.chat.ChatUiState
import com.example.blap.chat.MessageAuthor
import com.example.blap.chat.NearbyDevice
import kotlinx.coroutines.delay

@Composable
fun NearbyChatApp(
    uiState: ChatUiState,
    deniedPermissions: List<String>,
    onNameChanged: (String) -> Unit,
    onStartChat: () -> Unit,
    onConnect: (String) -> Unit,
    onSendMessage: (String) -> Unit,
    onDisconnect: () -> Unit,
    onDismissError: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.error) {
        val error = uiState.error ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(error)
        onDismissError()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(Color(0xFFFFD8C8), Cream, Color(0xFFDDEBE5)),
                    center = Offset(120f, 80f),
                    radius = 1_400f,
                ),
            ),
    ) {
        Scaffold(
            containerColor = Color.Transparent,
            contentWindowInsets = WindowInsets.safeDrawing,
            snackbarHost = { SnackbarHost(snackbarHostState) },
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .imePadding()
                    .padding(horizontal = 20.dp)
                    .widthIn(max = 720.dp)
                    .align(Alignment.TopCenter),
            ) {
                AppHeader(uiState.connectionState)
                AnimatedContent(
                    targetState = uiState.connectionState,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "chat-screen",
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                ) { connectionState ->
                    when (connectionState) {
                        ChatConnectionState.IDLE -> WelcomeScreen(
                            name = uiState.displayName,
                            deniedPermissions = deniedPermissions,
                            onNameChanged = onNameChanged,
                            onStartChat = onStartChat,
                            onOpenSettings = onOpenSettings,
                        )

                        ChatConnectionState.DISCOVERING -> DiscoveryScreen(
                            devices = uiState.discoveredDevices,
                            onConnect = onConnect,
                        )

                        ChatConnectionState.CONNECTING -> ConnectingScreen(
                            device = uiState.connectedDevice,
                            authenticationDigits = uiState.authenticationDigits,
                            onDisconnect = onDisconnect,
                        )

                        ChatConnectionState.CONNECTED -> ChatScreen(
                            peer = requireNotNull(uiState.connectedDevice),
                            messages = uiState.messages,
                            onSendMessage = onSendMessage,
                            onDisconnect = onDisconnect,
                        )

                        ChatConnectionState.DISCONNECTED -> RecoveryScreen(
                            title = "Connection closed",
                            description = "You can start scanning again whenever you are ready.",
                            buttonLabel = "Find nearby devices",
                            onAction = onStartChat,
                        )

                        ChatConnectionState.ERROR -> RecoveryScreen(
                            title = "Nearby chat paused",
                            description = uiState.error
                                ?: "Check that Bluetooth and Wi-Fi are switched on, then try again.",
                            buttonLabel = "Try again",
                            onAction = onStartChat,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AppHeader(state: ChatConnectionState) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 18.dp, bottom = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(
                text = "NEARBY / OFFLINE",
                style = MaterialTheme.typography.labelLarge,
                color = Signal,
            )
            Text(
                text = "Common Ground",
                style = MaterialTheme.typography.titleLarge,
                color = ForestDark,
            )
        }
        StatusPill(state)
    }
}

@Composable
private fun StatusPill(state: ChatConnectionState) {
    val (label, color) = when (state) {
        ChatConnectionState.IDLE -> "READY" to MutedInk
        ChatConnectionState.DISCOVERING -> "SEARCHING" to Signal
        ChatConnectionState.CONNECTING -> "LINKING" to Signal
        ChatConnectionState.CONNECTED -> "CONNECTED" to Forest
        ChatConnectionState.DISCONNECTED -> "OFFLINE" to MutedInk
        ChatConnectionState.ERROR -> "CHECK SETUP" to MaterialTheme.colorScheme.error
    }
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 11.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(color),
        )
        Spacer(Modifier.width(7.dp))
        Text(text = label, style = MaterialTheme.typography.labelLarge, color = color)
    }
}

@Composable
private fun WelcomeScreen(
    name: String,
    deniedPermissions: List<String>,
    onNameChanged: (String) -> Unit,
    onStartChat: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val focusManager = LocalFocusManager.current
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 28.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        item {
            Text(
                text = "Talk when the\nnetwork cannot.",
                style = MaterialTheme.typography.displaySmall,
                color = Ink,
            )
            Spacer(Modifier.height(14.dp))
            Text(
                text = "Connect directly to one nearby Android phone. No mobile data, Wi-Fi network, account, or server required.",
                style = MaterialTheme.typography.bodyLarge,
                color = MutedInk,
            )
            Spacer(Modifier.height(26.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(26.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            ) {
                Column(Modifier.padding(20.dp)) {
                    Text("Your temporary name", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = name,
                        onValueChange = onNameChanged,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text("e.g. Pranjal") },
                        shape = RoundedCornerShape(16.dp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(
                            onDone = {
                                focusManager.clearFocus()
                                onStartChat()
                            },
                        ),
                    )
                    Spacer(Modifier.height(14.dp))
                    Button(
                        onClick = {
                            focusManager.clearFocus()
                            onStartChat()
                        },
                        enabled = name.isNotBlank(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(54.dp),
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        Text("Start nearby chat  →")
                    }
                    Text(
                        text = "Both phones will advertise and discover at the same time.",
                        modifier = Modifier.padding(top = 12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MutedInk,
                    )
                }
            }
            if (deniedPermissions.isNotEmpty()) {
                PermissionCard(deniedPermissions, onOpenSettings)
            }
        }
    }
}

@Composable
private fun PermissionCard(
    deniedPermissions: List<String>,
    onOpenSettings: () -> Unit,
) {
    Card(
        modifier = Modifier.padding(top = 16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFE4DB)),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(Modifier.padding(18.dp)) {
            Text("Permission needed", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "Nearby Connections cannot start until these are allowed:",
                modifier = Modifier.padding(top = 5.dp, bottom = 6.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
            deniedPermissions.forEach { permission ->
                Text("• $permission", style = MaterialTheme.typography.bodyMedium)
            }
            TextButton(
                onClick = onOpenSettings,
                modifier = Modifier.align(Alignment.End),
                colors = ButtonDefaults.textButtonColors(contentColor = ForestDark),
            ) {
                Text("Open app settings")
            }
        }
    }
}

@Composable
private fun DiscoveryScreen(
    devices: List<NearbyDevice>,
    onConnect: (String) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        RadarBanner()
        Spacer(Modifier.height(22.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Text("Nearby devices", style = MaterialTheme.typography.headlineMedium)
            Text("${devices.size} found", style = MaterialTheme.typography.bodyMedium, color = MutedInk)
        }
        Spacer(Modifier.height(12.dp))
        if (devices.isEmpty()) {
            EmptyDevicesCard()
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                items(devices, key = NearbyDevice::endpointId) { device ->
                    DeviceCard(device = device, onConnect = onConnect)
                }
            }
        }
    }
}

@Composable
private fun RadarBanner() {
    var ring by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        while (true) {
            repeat(60) { frame ->
                ring = frame / 59f
                delay(25)
            }
        }
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(154.dp),
        colors = CardDefaults.cardColors(containerColor = ForestDark),
        shape = RoundedCornerShape(28.dp),
    ) {
        Box(Modifier.fillMaxSize()) {
            Canvas(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(180.dp)
                    .align(Alignment.CenterEnd),
            ) {
                val center = Offset(size.width * 0.52f, size.height * 0.5f)
                drawCircle(
                    color = Color.White.copy(alpha = 0.10f * (1f - ring)),
                    radius = 28.dp.toPx() + ring * 60.dp.toPx(),
                    center = center,
                    style = Stroke(width = 2.dp.toPx()),
                )
                drawCircle(Color.White.copy(alpha = 0.18f), 48.dp.toPx(), center, style = Stroke(2.dp.toPx()))
                drawCircle(Color.White, 8.dp.toPx(), center)
                drawLine(
                    color = Signal,
                    start = center,
                    end = center + Offset(45.dp.toPx(), -36.dp.toPx()),
                    strokeWidth = 4.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }
            Column(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(22.dp)
                    .widthIn(max = 210.dp),
            ) {
                Text("Scanning the crowd", style = MaterialTheme.typography.titleLarge, color = Color.White)
                Text(
                    "Keep this screen open on both phones.",
                    modifier = Modifier.padding(top = 7.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.75f),
                )
            }
        }
    }
}

@Composable
private fun EmptyDevicesCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f)),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text("Still looking…", style = MaterialTheme.typography.titleMedium)
            Text(
                "On the second phone, open this app, enter a name, and tap Start nearby chat.",
                modifier = Modifier.padding(top = 5.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MutedInk,
            )
        }
    }
}

@Composable
private fun DeviceCard(device: NearbyDevice, onConnect: (String) -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(20.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(Mist),
                contentAlignment = Alignment.Center,
            ) {
                Text(device.name.take(1).uppercase(), fontWeight = FontWeight.Bold, color = ForestDark)
            }
            Text(
                text = device.name,
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .weight(1f),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Button(
                onClick = { onConnect(device.endpointId) },
                shape = RoundedCornerShape(13.dp),
                contentPadding = PaddingValues(horizontal = 15.dp, vertical = 10.dp),
            ) {
                Text("Connect")
            }
        }
    }
}

@Composable
private fun ConnectingScreen(
    device: NearbyDevice?,
    authenticationDigits: String?,
    onDisconnect: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(94.dp)
                .clip(CircleShape)
                .background(Mist),
            contentAlignment = Alignment.Center,
        ) {
            Text("↔", style = MaterialTheme.typography.displaySmall, color = Forest)
        }
        Spacer(Modifier.height(22.dp))
        Text("Connecting to ${device?.name ?: "nearby device"}", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Both phones must accept the connection.",
            modifier = Modifier.padding(top = 8.dp),
            style = MaterialTheme.typography.bodyLarge,
            color = MutedInk,
        )
        if (authenticationDigits != null) {
            Card(
                modifier = Modifier.padding(top = 22.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(20.dp),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 28.dp, vertical = 18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("VERIFY ON BOTH PHONES", style = MaterialTheme.typography.labelLarge, color = Signal)
                    Text(
                        authenticationDigits,
                        modifier = Modifier.padding(top = 5.dp),
                        style = MaterialTheme.typography.headlineMedium,
                        color = ForestDark,
                    )
                }
            }
        }
        TextButton(onClick = onDisconnect, modifier = Modifier.padding(top = 14.dp)) {
            Text("Cancel")
        }
    }
}

@Composable
private fun ChatScreen(
    peer: NearbyDevice,
    messages: List<ChatMessage>,
    onSendMessage: (String) -> Unit,
    onDisconnect: () -> Unit,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(Forest),
                contentAlignment = Alignment.Center,
            ) {
                Text(peer.name.take(1).uppercase(), color = Color.White, fontWeight = FontWeight.Bold)
            }
            Column(
                modifier = Modifier
                    .padding(start = 12.dp)
                    .weight(1f),
            ) {
                Text(peer.name, style = MaterialTheme.typography.titleLarge)
                Text("Direct device-to-device link", style = MaterialTheme.typography.bodyMedium, color = Forest)
            }
            TextButton(onClick = onDisconnect) { Text("Disconnect") }
        }
        HorizontalDivider(color = Ink.copy(alpha = 0.10f))
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(vertical = 16.dp),
            verticalArrangement = if (messages.isEmpty()) Arrangement.Center else Arrangement.spacedBy(10.dp),
        ) {
            if (messages.isEmpty()) {
                item {
                    Text(
                        "The link is live. Send the first message.",
                        modifier = Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MutedInk,
                    )
                }
            } else {
                items(messages, key = ChatMessage::id) { message -> MessageBubble(message, peer.name) }
            }
        }
        MessageComposer(onSendMessage)
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun MessageBubble(message: ChatMessage, peerName: String) {
    val isMine = message.author == MessageAuthor.ME
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isMine) Alignment.End else Alignment.Start,
    ) {
        Text(
            text = if (isMine) "You" else peerName,
            modifier = Modifier.padding(start = 5.dp, end = 5.dp, bottom = 3.dp),
            style = MaterialTheme.typography.labelLarge,
            color = if (isMine) Forest else MutedInk,
        )
        Text(
            text = message.text,
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(
                    RoundedCornerShape(
                        topStart = 18.dp,
                        topEnd = 18.dp,
                        bottomStart = if (isMine) 18.dp else 4.dp,
                        bottomEnd = if (isMine) 4.dp else 18.dp,
                    ),
                )
                .background(if (isMine) Forest else MaterialTheme.colorScheme.surface)
                .padding(horizontal = 15.dp, vertical = 11.dp),
            style = MaterialTheme.typography.bodyLarge,
            color = if (isMine) Color.White else Ink,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MessageComposer(onSendMessage: (String) -> Unit) {
    var message by rememberSaveable { mutableStateOf("") }
    val send = {
        if (message.isNotBlank()) {
            onSendMessage(message)
            message = ""
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = message,
            onValueChange = { message = it.take(1_000) },
            modifier = Modifier.weight(1f),
            placeholder = { Text("Type a message…") },
            maxLines = 4,
            shape = RoundedCornerShape(18.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { send() }),
        )
        Spacer(Modifier.width(10.dp))
        Button(
            onClick = send,
            enabled = message.isNotBlank(),
            modifier = Modifier.height(54.dp),
            shape = RoundedCornerShape(16.dp),
            contentPadding = PaddingValues(horizontal = 18.dp),
        ) {
            Text("Send")
        }
    }
}

@Composable
private fun RecoveryScreen(
    title: String,
    description: String,
    buttonLabel: String,
    onAction: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
        Text(
            description,
            modifier = Modifier.padding(top = 9.dp),
            style = MaterialTheme.typography.bodyLarge,
            color = MutedInk,
        )
        Button(
            onClick = onAction,
            modifier = Modifier.padding(top = 22.dp),
            shape = RoundedCornerShape(15.dp),
        ) {
            Text(buttonLabel)
        }
    }
}
