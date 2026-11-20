package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.PrivacyTip
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.domain.ChannelLookupState
import com.example.domain.VerifiedYouTubeChannel
import com.example.ui.FreedomUiState
import com.example.ui.theme.FreedomGlass
import com.example.ui.theme.FreedomSwitch
import com.example.ui.theme.GlassCard
import com.example.ui.theme.GlassDivider
import com.example.ui.theme.GlassGroupedRow
import kotlinx.coroutines.flow.StateFlow

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ContentProtectionScreen(
    uiState: FreedomUiState,
    channelLookupFlow: StateFlow<ChannelLookupState>,
    onToggleYouTubeStudyMode: (Boolean) -> Unit,
    onToggleYouTubeMaskFeedCards: (Boolean) -> Unit,
    onToggleYouTubeAllowShorts: (Boolean) -> Unit,
    onToggleYouTubeAllowLive: (Boolean) -> Unit,
    onSaveYouTubeApiKey: (String) -> Unit,
    onLookupYouTubeChannel: (String) -> Unit,
    onVerifyManualChannelId: (String) -> Unit,
    onSelectSearchCandidate: (VerifiedYouTubeChannel) -> Unit,
    onConfirmApproveChannel: (VerifiedYouTubeChannel) -> Unit,
    onRemoveApprovedChannel: (String) -> Unit,
    onResetChannelLookup: () -> Unit,
    onToggleSafeSearch: (Boolean) -> Unit,
    onToggleBlockUnverifiedSearch: (Boolean) -> Unit,
    onRefreshSafeSearchRules: () -> Unit,
    onToggleInAppAdultFilter: (Boolean) -> Unit,
    onToggleBlockImageSearchStrict: (Boolean) -> Unit,
    onToggleBlockUnfilterableAppsStrict: (Boolean) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val g = FreedomGlass.colors
    var showYouTubeManager by remember { mutableStateOf(false) }

    if (showYouTubeManager) {
        BackHandler {
            onResetChannelLookup()
            showYouTubeManager = false
        }
        YouTubeStudyManagerView(
            uiState = uiState,
            channelLookupFlow = channelLookupFlow,
            onToggleStudyMode = onToggleYouTubeStudyMode,
            onToggleMaskFeedCards = onToggleYouTubeMaskFeedCards,
            onToggleAllowShorts = onToggleYouTubeAllowShorts,
            onToggleAllowLive = onToggleYouTubeAllowLive,
            onSaveApiKey = onSaveYouTubeApiKey,
            onLookupChannel = onLookupYouTubeChannel,
            onVerifyManualChannelId = onVerifyManualChannelId,
            onSelectSearchCandidate = onSelectSearchCandidate,
            onConfirmApproveChannel = onConfirmApproveChannel,
            onRemoveApprovedChannel = onRemoveApprovedChannel,
            onResetLookup = onResetChannelLookup,
            onBack = {
                onResetChannelLookup()
                showYouTubeManager = false
            },
            modifier = modifier
        )
        return
    }

    BackHandler { onBack() }

    val cfg = uiState.contentProtection
    val strictActive = uiState.isStrictModeActive

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.testTag("content_protection_back_button")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "Back",
                    tint = g.primaryAccent
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Content Protection",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = g.textPrimary
                )
                Text(
                    text = "Always-on protection · Auto-locks in Strict Mode",
                    style = MaterialTheme.typography.bodySmall,
                    color = g.textSecondary
                )
            }
            if (strictActive) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(g.lockedAmber.copy(alpha = 0.16f))
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Lock,
                        contentDescription = "Strict Mode Locked",
                        tint = g.lockedAmber,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "Locked",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = g.lockedAmber
                    )
                }
            }
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. Regain-Style YouTube Study Mode (Multi-Client)
            item(key = "cp_youtube_card") {
                GlassCard(
                    accentBorderColor = if (cfg.youtubeStudyModeEnabled) g.primaryAccent else null,
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.PlayCircle,
                            contentDescription = null,
                            tint = g.primaryAccent,
                            modifier = Modifier.size(26.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "YouTube Study Mode",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = g.textPrimary
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = "Allow only verified approved channels across YouTube, Morphe YT, ReVanced & supported clients.",
                                style = MaterialTheme.typography.bodySmall,
                                color = g.textSecondary
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        FreedomSwitch(
                            checked = cfg.youtubeStudyModeEnabled,
                            onCheckedChange = onToggleYouTubeStudyMode,
                            modifier = Modifier.testTag("cp_youtube_study_switch")
                        )
                    }
                    GlassDivider()
                    GlassGroupedRow(
                        title = "Manage Approved Channels & Clients",
                        subtitle = "${uiState.approvedYouTubeChannels.size} verified channels · ${uiState.detectedYouTubeClients.size} detected YouTube clients",
                        trailingText = "Manage",
                        onClick = { showYouTubeManager = true },
                        modifier = Modifier.testTag("open_youtube_study_manager_row")
                    )
                }
            }

            // 2. Dedicated Universal SafeSearch Engine
            item(key = "cp_safesearch_card") {
                GlassCard(
                    accentBorderColor = if (cfg.safeSearchEnabled) g.shieldActive else null,
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Search,
                            contentDescription = null,
                            tint = g.shieldActive,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Universal SafeSearch Engine",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = g.textPrimary
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = "Enforces strictest SafeSearch across web, image, and video searches and blocks settings tampering in Strict Mode.",
                                style = MaterialTheme.typography.bodySmall,
                                color = g.textSecondary
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        FreedomSwitch(
                            checked = cfg.safeSearchEnabled,
                            onCheckedChange = onToggleSafeSearch,
                            modifier = Modifier.testTag("cp_safesearch_switch")
                        )
                    }

                    GlassDivider()

                    Column(
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        val providers = uiState.safeSearchProviderRules
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Protected Engines (${providers.size})",
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                                color = g.textPrimary
                            )
                            TextButton(
                                onClick = onRefreshSafeSearchRules,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                modifier = Modifier.testTag("refresh_safesearch_rules_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Refresh,
                                    contentDescription = null,
                                    tint = g.primaryAccent,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = "Refresh Rules",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = g.primaryAccent
                                )
                            }
                        }

                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            providers.forEach { rule ->
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(g.primarySoft)
                                        .border(1.dp, g.glassBorder, RoundedCornerShape(10.dp))
                                        .padding(horizontal = 10.dp, vertical = 5.dp)
                                ) {
                                    Text(
                                        text = rule.displayName,
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                                        color = g.textPrimary
                                    )
                                }
                            }
                        }
                    }

                    GlassDivider()

                    ProtectionToggleRow(
                        title = "Block unverified search engines in Strict Mode",
                        subtitle = "Automatically blocks unknown or non-enforceable search engines during locked sessions.",
                        checked = cfg.blockUnverifiedSearchEnginesInStrictMode,
                        onCheckedChange = onToggleBlockUnverifiedSearch,
                        testTag = "cp_block_unverified_search_switch"
                    )
                }
            }

            // 3. Advanced In-App Adult-Content Filtering
            item(key = "cp_adult_filter_card") {
                GlassCard(
                    accentBorderColor = if (cfg.inAppAdultFilterEnabled) g.primaryAccent else null,
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Shield,
                            contentDescription = null,
                            tint = g.primaryAccent,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "In-App Adult Content Filter",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = g.textPrimary
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = "Filters explicit queries, titles, descriptions, and accessible image labels across YouTube, browsers, and supported apps.",
                                style = MaterialTheme.typography.bodySmall,
                                color = g.textSecondary
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        FreedomSwitch(
                            checked = cfg.inAppAdultFilterEnabled,
                            onCheckedChange = onToggleInAppAdultFilter,
                            modifier = Modifier.testTag("cp_adult_filter_switch")
                        )
                    }

                    GlassDivider()

                    ProtectionToggleRow(
                        title = "Block Google Images in Strict Mode",
                        subtitle = "Optional visual safeguard: blocks image-search tabs during locked sessions because Android cannot inspect raw image pixels without root or VPN.",
                        checked = cfg.blockImageSearchInStrictMode,
                        onCheckedChange = onToggleBlockImageSearchStrict,
                        testTag = "cp_block_image_search_switch"
                    )

                    GlassDivider()

                    ProtectionToggleRow(
                        title = "Block unfilterable apps in Strict Mode",
                        subtitle = "Blocks apps whose native image or feed screens cannot be inspected reliably (such as the Google App) during locked sessions.",
                        checked = cfg.blockUnfilterableAppsInStrictMode,
                        onCheckedChange = onToggleBlockUnfilterableAppsStrict,
                        testTag = "cp_block_unfilterable_apps_switch"
                    )
                }
            }

            // 4. Honest Privacy & Zero-VPN Disclosure
            item(key = "cp_privacy_note") {
                GlassCard(contentPadding = PaddingValues(16.dp)) {
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(
                            imageVector = Icons.Rounded.PrivacyTip,
                            contentDescription = null,
                            tint = g.primaryAccent,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = "Zero-VPN Privacy & Platform Boundaries",
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                                color = g.textPrimary
                            )
                            Text(
                                text = "All filtering runs locally on-device with zero VPN, zero root, and zero browsing history storage. Network requests occur only when you verify a YouTube channel or when an uncached direct YouTube video ID is verified against your allowlist. Without VPN or screen capture, raw image pixels cannot be inspected — use the Strict Mode Image Search block above if you want complete visual certainty.",
                                style = MaterialTheme.typography.bodySmall,
                                color = g.textSecondary
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ProtectionToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    testTag: String
) {
    val g = FreedomGlass.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                color = g.textPrimary
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = g.textSecondary
            )
        }
        Spacer(Modifier.width(12.dp))
        FreedomSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag(testTag)
        )
    }
}
