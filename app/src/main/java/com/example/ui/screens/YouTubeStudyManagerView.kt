package com.example.ui.screens

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.domain.ChannelLookupState
import com.example.domain.VerifiedYouTubeChannel
import com.example.domain.YouTubeClientSupportTier
import com.example.ui.FreedomUiState
import com.example.ui.theme.FreedomGlass
import com.example.ui.theme.GlassCard
import com.example.ui.theme.GlassDivider
import com.example.ui.theme.GlassSegmentedControl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

private object ChannelAvatarCache {
    private val cache = LruCache<String, ImageBitmap>(60)
    fun get(url: String): ImageBitmap? = cache.get(url)
    fun put(url: String, bmp: ImageBitmap) {
        cache.put(url, bmp)
    }
}

@Composable
fun YouTubeStudyManagerView(
    uiState: FreedomUiState,
    channelLookupFlow: StateFlow<ChannelLookupState>,
    onToggleStudyMode: (Boolean) -> Unit,
    onToggleMaskFeedCards: (Boolean) -> Unit,
    onToggleAllowShorts: (Boolean) -> Unit,
    onToggleAllowLive: (Boolean) -> Unit,
    onSaveApiKey: (String) -> Unit,
    onLookupChannel: (String) -> Unit,
    onVerifyManualChannelId: (String) -> Unit,
    onSelectSearchCandidate: (VerifiedYouTubeChannel) -> Unit,
    onConfirmApproveChannel: (VerifiedYouTubeChannel) -> Unit,
    onRemoveApprovedChannel: (String) -> Unit,
    onResetLookup: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val g = FreedomGlass.colors
    val cfg = uiState.contentProtection
    val strictActive = uiState.isStrictModeActive
    val lookupState by channelLookupFlow.collectAsStateWithLifecycle()

    var inputMode by remember { mutableIntStateOf(0) } // 0 = URL / Video / Search, 1 = Manual UC... ID
    var queryInput by remember { mutableStateOf("") }
    var manualIdInput by remember { mutableStateOf("") }
    var showAdvancedApi by remember { mutableStateOf(false) }
    var apiKeyDraft by remember(cfg.youtubeDataApiKey) { mutableStateOf(cfg.youtubeDataApiKey) }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.testTag("yt_study_back_button")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "Back",
                    tint = g.primaryAccent
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "YouTube Study Mode",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = g.textPrimary
                )
                Text(
                    text = "Shared allowlist across YouTube, Morphe YT & ReVanced",
                    style = MaterialTheme.typography.bodySmall,
                    color = g.textSecondary
                )
            }
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. Study Mode Rules Card
            item(key = "yt_rules_card") {
                GlassCard(contentPadding = PaddingValues(0.dp)) {
                    ProtectionToggleRow(
                        title = "Enable YouTube Study Mode",
                        subtitle = "Keep normal Home, Search & Recommendations while allowing only approved channels.",
                        checked = cfg.youtubeStudyModeEnabled,
                        onCheckedChange = onToggleStudyMode,
                        testTag = "yt_study_master_switch"
                    )
                    GlassDivider()
                    ProtectionToggleRow(
                        title = "Mask unapproved cards in feeds",
                        subtitle = "Covers unapproved video cards with a lightweight single-canvas shield while keeping approved cards open.",
                        checked = cfg.youtubeMaskFeedCards,
                        onCheckedChange = onToggleMaskFeedCards,
                        testTag = "yt_mask_cards_switch"
                    )
                    GlassDivider()
                    ProtectionToggleRow(
                        title = "Allow Shorts from approved channels",
                        subtitle = "Blocked by default. When enabled, only Shorts from approved channels can play.",
                        checked = cfg.youtubeAllowShortsFromApproved,
                        onCheckedChange = onToggleAllowShorts,
                        testTag = "yt_allow_shorts_switch"
                    )
                    GlassDivider()
                    ProtectionToggleRow(
                        title = "Allow Live streams from approved channels",
                        subtitle = "Blocked by default. When enabled, only live streams from approved channels can play.",
                        checked = cfg.youtubeAllowLiveFromApproved,
                        onCheckedChange = onToggleAllowLive,
                        testTag = "yt_allow_live_switch"
                    )
                }
            }

            // 2. Hybrid Add Approved Channel Card
            item(key = "yt_add_channel_card") {
                GlassCard(contentPadding = PaddingValues(18.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Add Verified Channel",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = g.textPrimary
                        )
                        if (strictActive) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Rounded.Lock,
                                    contentDescription = null,
                                    tint = g.lockedAmber,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = "Locked",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = g.lockedAmber
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "Every channel's permanent UC... ID is verified before approval. Paste a channel URL, a video link, search by name, or verify a manual UC... ID.",
                        style = MaterialTheme.typography.bodySmall,
                        color = g.textSecondary
                    )
                    Spacer(Modifier.height(12.dp))

                    GlassSegmentedControl(
                        options = listOf("URL / Video / Search", "Manual UC... ID"),
                        selectedIndex = inputMode,
                        onSelect = {
                            inputMode = it
                            onResetLookup()
                        },
                        testTagPrefix = "yt_add_mode"
                    )

                    Spacer(Modifier.height(12.dp))

                    if (inputMode == 0) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(g.glassSurface)
                                .border(1.dp, g.glassBorder, RoundedCornerShape(14.dp))
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Search,
                                contentDescription = null,
                                tint = g.textSecondary,
                                modifier = Modifier.size(19.dp)
                            )
                            Spacer(Modifier.width(10.dp))
                            Box(modifier = Modifier.weight(1f)) {
                                if (queryInput.isEmpty()) {
                                    Text(
                                        text = "Channel URL, video link, @handle, or name…",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = g.textMuted,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                BasicTextField(
                                    value = queryInput,
                                    onValueChange = { queryInput = it },
                                    singleLine = true,
                                    enabled = !strictActive,
                                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = g.textPrimary),
                                    cursorBrush = SolidColor(g.primaryAccent),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("yt_channel_search_input")
                                )
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        Button(
                            onClick = { onLookupChannel(queryInput) },
                            enabled = !strictActive && lookupState !is ChannelLookupState.Loading,
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = g.primaryAccent,
                                contentColor = g.onPrimaryAccent
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("yt_verify_channel_button")
                        ) {
                            Text(
                                text = "Resolve & Verify Channel",
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
                            )
                        }
                    } else {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(g.glassSurface)
                                .border(1.dp, g.glassBorder, RoundedCornerShape(14.dp))
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(modifier = Modifier.weight(1f)) {
                                if (manualIdInput.isEmpty()) {
                                    Text(
                                        text = "UCxxxxxxxxxxxxxxxxxxxxxx (24 chars)",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = g.textMuted
                                    )
                                }
                                BasicTextField(
                                    value = manualIdInput,
                                    onValueChange = { manualIdInput = it },
                                    singleLine = true,
                                    enabled = !strictActive,
                                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = g.textPrimary),
                                    cursorBrush = SolidColor(g.primaryAccent),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("yt_manual_id_input")
                                )
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        Button(
                            onClick = { onVerifyManualChannelId(manualIdInput) },
                            enabled = !strictActive && lookupState !is ChannelLookupState.Loading,
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = g.primaryAccent,
                                contentColor = g.onPrimaryAccent
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("yt_verify_manual_id_button")
                        ) {
                            Text(
                                text = "Verify Permanent Channel ID",
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
                            )
                        }
                    }

                    // Lookup Feedback / Candidate Verification Card
                    when (val state = lookupState) {
                        ChannelLookupState.Idle -> Unit

                        is ChannelLookupState.Loading -> {
                            Spacer(Modifier.height(14.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = g.primaryAccent
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    text = state.statusText,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = g.textSecondary
                                )
                            }
                        }

                        is ChannelLookupState.VerifiedCandidate -> {
                            Spacer(Modifier.height(14.dp))
                            VerifiedChannelConfirmationCard(
                                channel = state.channel,
                                onConfirm = {
                                    onConfirmApproveChannel(state.channel)
                                    queryInput = ""
                                    manualIdInput = ""
                                },
                                onCancel = onResetLookup
                            )
                        }

                        is ChannelLookupState.SearchResults -> {
                            Spacer(Modifier.height(14.dp))
                            Text(
                                text = "Select a verified channel to confirm (${state.source.label}):",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = g.textSecondary
                            )
                            Spacer(Modifier.height(8.dp))
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                state.candidates.forEach { candidate ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(14.dp))
                                            .background(g.glassSurface)
                                            .border(1.dp, g.glassBorder, RoundedCornerShape(14.dp))
                                            .clickable { onSelectSearchCandidate(candidate) }
                                            .padding(horizontal = 12.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        ChannelAvatar(
                                            avatarUrl = candidate.avatarUrl,
                                            title = candidate.title,
                                            sizeDp = 40
                                        )
                                        Spacer(Modifier.width(12.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = candidate.title,
                                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                                color = g.textPrimary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = buildString {
                                                    if (candidate.handle.isNotEmpty()) append("${candidate.handle} · ")
                                                    append(candidate.channelId)
                                                },
                                                style = MaterialTheme.typography.labelSmall,
                                                color = g.textSecondary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        Icon(
                                            imageVector = Icons.Rounded.CheckCircle,
                                            contentDescription = "Review channel",
                                            tint = g.primaryAccent,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                            }
                        }

                        is ChannelLookupState.Error -> {
                            Spacer(Modifier.height(14.dp))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(g.dangerCoral.copy(alpha = 0.12f))
                                    .border(1.dp, g.dangerCoral.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
                                    .padding(12.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.ErrorOutline,
                                    contentDescription = null,
                                    tint = g.dangerCoral,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(Modifier.width(10.dp))
                                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text(
                                        text = state.reason,
                                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                                        color = g.textPrimary
                                    )
                                    Text(
                                        text = state.suggestedFallback,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = g.textSecondary
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 3. Approved Channels Database List
            item(key = "yt_approved_header") {
                Text(
                    text = "Approved Channels (${uiState.approvedYouTubeChannels.size})",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = g.textPrimary
                )
            }

            if (uiState.approvedYouTubeChannels.isEmpty()) {
                item(key = "yt_approved_empty") {
                    GlassCard(contentPadding = PaddingValues(18.dp)) {
                        Text(
                            text = "No approved channels yet",
                            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                            color = g.textPrimary
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Add study channels above. Once Study Mode is active, only videos from your approved channels can play across YouTube, Morphe YT, ReVanced, and supported clients.",
                            style = MaterialTheme.typography.bodySmall,
                            color = g.textSecondary
                        )
                    }
                }
            } else {
                items(uiState.approvedYouTubeChannels, key = { it.channelId }) { ch ->
                    GlassCard(
                        cornerRadius = 16.dp,
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ChannelAvatar(
                                avatarUrl = ch.avatarUrl,
                                title = ch.title,
                                sizeDp = 42
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = ch.title,
                                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                                        color = g.textPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(Modifier.width(5.dp))
                                    Icon(
                                        imageVector = Icons.Rounded.Verified,
                                        contentDescription = "Verified permanent channel ID",
                                        tint = g.shieldActive,
                                        modifier = Modifier.size(15.dp)
                                    )
                                }
                                Text(
                                    text = buildString {
                                        if (ch.handle.isNotEmpty()) append("${ch.handle} · ")
                                        append(ch.channelId)
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = g.textSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            IconButton(
                                onClick = { onRemoveApprovedChannel(ch.channelId) },
                                enabled = !strictActive,
                                modifier = Modifier.testTag("remove_yt_channel_${ch.channelId}")
                            ) {
                                Icon(
                                    imageVector = if (strictActive) Icons.Rounded.Lock else Icons.Rounded.DeleteOutline,
                                    contentDescription = "Remove approved channel",
                                    tint = if (strictActive) g.textMuted else g.dangerCoral,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }

            // 4. Detected YouTube Clients (Official YT, Morphe YT, ReVanced, Third-Party)
            item(key = "yt_clients_section") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Detected YouTube Clients (${uiState.detectedYouTubeClients.size})",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = g.textPrimary
                    )
                    GlassCard(contentPadding = PaddingValues(vertical = 4.dp)) {
                        if (uiState.detectedYouTubeClients.isEmpty()) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = "No YouTube client apps detected yet",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = g.textPrimary
                                )
                                Text(
                                    text = "Official YouTube, Morphe YT, YouTube ReVanced/RVX, NewPipe, Tubular, PipePipe, and LibreTube are automatically recognized once installed. Direct browser YouTube links remain protected.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = g.textSecondary
                                )
                            }
                        } else {
                            uiState.detectedYouTubeClients.forEachIndexed { idx, client ->
                                val badgeColor = when (client.supportTier) {
                                    YouTubeClientSupportTier.FULL_STUDY_MODE -> g.shieldActive
                                    YouTubeClientSupportTier.WATCH_VERIFICATION_ONLY -> g.primaryAccent
                                    YouTubeClientSupportTier.UNVERIFIABLE -> g.lockedAmber
                                }
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.PlayCircle,
                                        contentDescription = null,
                                        tint = badgeColor,
                                        modifier = Modifier.size(22.dp)
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "${client.displayName} (${client.family.label})",
                                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                            color = g.textPrimary
                                        )
                                        Text(
                                            text = "${client.supportTier.label} · ${client.packageName}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = badgeColor
                                        )
                                        if (client.notes.isNotEmpty()) {
                                            Text(
                                                text = client.notes,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = g.textSecondary
                                            )
                                        }
                                    }
                                }
                                if (idx < uiState.detectedYouTubeClients.lastIndex) {
                                    GlassDivider()
                                }
                            }
                        }
                    }
                }
            }

            // 5. Optional YouTube Data API v3 Secondary Fallback Key
            item(key = "yt_advanced_api_card") {
                GlassCard(contentPadding = PaddingValues(16.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showAdvancedApi = !showAdvancedApi },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Key,
                            contentDescription = null,
                            tint = g.primaryAccent,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Optional YouTube Data API v3 Fallback",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = g.textPrimary
                            )
                            Text(
                                text = if (cfg.youtubeDataApiKey.isNotEmpty()) "Fallback key configured locally" else "Not required · Used only if public lookup fails",
                                style = MaterialTheme.typography.bodySmall,
                                color = g.textSecondary
                            )
                        }
                        Icon(
                            imageVector = Icons.Rounded.KeyboardArrowDown,
                            contentDescription = "Toggle API key settings",
                            tint = g.textSecondary
                        )
                    }

                    AnimatedVisibility(visible = showAdvancedApi) {
                        Column(
                            modifier = Modifier.padding(top = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = "Primary channel verification works with zero API key. Optionally add your own YouTube Data API v3 key as a fallback if public lookups are ever rate-limited. Stored strictly on this device.",
                                style = MaterialTheme.typography.bodySmall,
                                color = g.textSecondary
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(g.glassSurface)
                                    .border(1.dp, g.glassBorder, RoundedCornerShape(12.dp))
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(modifier = Modifier.weight(1f)) {
                                    if (apiKeyDraft.isEmpty()) {
                                        Text(
                                            text = "AIza… (optional)",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = g.textMuted
                                        )
                                    }
                                    BasicTextField(
                                        value = apiKeyDraft,
                                        onValueChange = { apiKeyDraft = it },
                                        singleLine = true,
                                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = g.textPrimary),
                                        cursorBrush = SolidColor(g.primaryAccent),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("yt_api_key_input")
                                    )
                                }
                            }
                            OutlinedButton(
                                onClick = { onSaveApiKey(apiKeyDraft) },
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("yt_save_api_key_button")
                            ) {
                                Text("Save Fallback Key", color = g.primaryAccent, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VerifiedChannelConfirmationCard(
    channel: VerifiedYouTubeChannel,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    val g = FreedomGlass.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(g.primarySoft)
            .border(1.dp, g.primaryAccent.copy(alpha = 0.55f), RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ChannelAvatar(
                avatarUrl = channel.avatarUrl,
                title = channel.title,
                sizeDp = 52
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = channel.title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = g.textPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (channel.handle.isNotEmpty()) {
                    Text(
                        text = channel.handle,
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                        color = g.primaryAccent
                    )
                }
                Text(
                    text = "ID: ${channel.channelId}",
                    style = MaterialTheme.typography.labelSmall,
                    color = g.textSecondary
                )
                Text(
                    text = channel.verifiedSource.label,
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = g.shieldActive
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedButton(
                onClick = onCancel,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp)
            ) {
                Text("Cancel", color = g.textSecondary)
            }
            Button(
                onClick = onConfirm,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = g.primaryAccent,
                    contentColor = g.onPrimaryAccent
                ),
                modifier = Modifier
                    .weight(1.4f)
                    .height(44.dp)
                    .testTag("confirm_approve_yt_channel_button")
            ) {
                Text("Approve Channel", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun ChannelAvatar(
    avatarUrl: String,
    title: String,
    sizeDp: Int
) {
    val g = FreedomGlass.colors
    val avatarBitmap by produceState(
        initialValue = if (avatarUrl.isNotBlank()) ChannelAvatarCache.get(avatarUrl) else null,
        key1 = avatarUrl
    ) {
        if (value == null && avatarUrl.startsWith("http", ignoreCase = true)) {
            val loaded = withContext(Dispatchers.IO) {
                runCatching {
                    val conn = (URL(avatarUrl).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 5_000
                        readTimeout = 6_000
                    }
                    try {
                        if (conn.responseCode in 200..299) {
                            BitmapFactory.decodeStream(conn.inputStream)?.asImageBitmap()
                        } else null
                    } finally {
                        conn.disconnect()
                    }
                }.getOrNull()
            }
            if (loaded != null) {
                ChannelAvatarCache.put(avatarUrl, loaded)
                value = loaded
            }
        }
    }

    val bmp = avatarBitmap
    if (bmp != null) {
        Image(
            bitmap = bmp,
            contentDescription = "$title profile picture",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(sizeDp.dp)
                .clip(CircleShape)
                .border(1.dp, g.glassBorder, CircleShape)
        )
    } else {
        Box(
            modifier = Modifier
                .size(sizeDp.dp)
                .clip(CircleShape)
                .background(g.primarySoft)
                .border(1.dp, g.glassBorder, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = title.trim().take(1).uppercase().ifEmpty { "Y" },
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = g.primaryAccent
            )
        }
    }
}
