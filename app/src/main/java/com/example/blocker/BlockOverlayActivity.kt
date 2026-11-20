package com.example.blocker

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.FreedomApplication
import com.example.MainActivity
import com.example.domain.SettingsWaitStateMachine
import com.example.ui.theme.BackdropMood
import com.example.ui.theme.ForestGlassBackdrop
import com.example.ui.theme.FreedomGlass
import com.example.ui.theme.FreedomTheme
import com.example.ui.theme.GlassCard
import com.example.ui.theme.GlassPillButton
import com.example.ui.theme.InterFontFamily
import kotlinx.coroutines.delay

/**
 * Full-screen non-resizable blocking & Settings Emergency Waiting Room activity.
 *
 * Modes:
 *  - MODE_APP_BLOCKED: Non-allowlisted app or hard-blocked non-essential system app
 *  - MODE_WEBSITE_BLOCKED: Zero-VPN Full Internet Browsing Block or blocked category/auto-detected site
 *  - MODE_SETTINGS_WAITING_ROOM: Fixed 5-minute continuous monotonic emergency wait for Android
 *    Settings only -> grants 3 minutes of temporary access before auto-relocking. Leaving resets wait.
 */
class BlockOverlayActivity : ComponentActivity() {

    private var waitTargetElapsedMs: Long = 0L
    private var currentMode: String = MODE_APP_BLOCKED

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        renderFromIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        renderFromIntent(intent)
    }

    override fun onStop() {
        super.onStop()
        // Leaving the Waiting Room before 00:00 immediately resets the 5-minute wait
        if (currentMode == MODE_SETTINGS_WAITING_ROOM) {
            waitTargetElapsedMs = 0L
        }
    }

    private fun renderFromIntent(intent: Intent?) {
        val container = (application as? FreedomApplication)?.container
        val themeMode = container?.fastStore?.getThemeMode() ?: com.example.domain.ThemeMode.SYSTEM

        val mode = intent?.getStringExtra(EXTRA_MODE) ?: MODE_APP_BLOCKED
        currentMode = mode
        val blockedPkg = intent?.getStringExtra(EXTRA_BLOCKED_PACKAGE).orEmpty()
        val blockedLabel = intent?.getStringExtra(EXTRA_BLOCKED_LABEL) ?: "Blocked App"
        val reasonSubtitle = intent?.getStringExtra(EXTRA_REASON_SUBTITLE).orEmpty()
        val sessionLabel = intent?.getStringExtra(EXTRA_SESSION_LABEL) ?: "Focus Session"
        val strictMode = intent?.getBooleanExtra(EXTRA_STRICT_MODE, false) ?: false

        if (mode == MODE_SETTINGS_WAITING_ROOM) {
            waitTargetElapsedMs = SystemClock.elapsedRealtime() + SettingsWaitStateMachine.EMERGENCY_WAIT_DURATION_MS
        } else {
            waitTargetElapsedMs = 0L
        }

        setContent {
            FreedomTheme(themeMode = themeMode) {
                BackHandler { navigateHome() }

                val snapshot = remember { container?.fastStore?.readSnapshot() }
                var sessionRemainingMs by remember {
                    mutableLongStateOf(
                        snapshot?.remainingMillis(System.currentTimeMillis(), SystemClock.elapsedRealtime()) ?: 0L
                    )
                }

                LaunchedEffect(Unit) {
                    while (true) {
                        val snap = container?.fastStore?.readSnapshot()
                        if (snap != null) {
                            val rem = snap.remainingMillis(System.currentTimeMillis(), SystemClock.elapsedRealtime())
                            sessionRemainingMs = rem
                            if (rem <= 0L) {
                                finish()
                                break
                            }
                        }
                        delay(1_000L)
                    }
                }

                if (mode == MODE_SETTINGS_WAITING_ROOM) {
                    SettingsWaitingRoomScreen(
                        routeLabel = reasonSubtitle.ifBlank { "Android Settings" },
                        sessionLabel = sessionLabel,
                        waitTargetElapsedMs = waitTargetElapsedMs,
                        onGrantAndOpenSettings = {
                            container?.fastStore?.grantTemporarySettingsUnlock(SystemClock.elapsedRealtime())
                            val targetIntent = if (blockedPkg.isNotBlank()) {
                                packageManager.getLaunchIntentForPackage(blockedPkg)
                            } else {
                                null
                            } ?: Intent(android.provider.Settings.ACTION_SETTINGS)
                            targetIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            runCatching { startActivity(targetIntent) }
                            finish()
                        },
                        onReturnHome = { navigateHome() }
                    )
                } else {
                    BlockedShieldScreen(
                        isWebsiteMode = mode == MODE_WEBSITE_BLOCKED,
                        blockedLabel = blockedLabel,
                        reasonSubtitle = reasonSubtitle,
                        sessionLabel = sessionLabel,
                        strictMode = strictMode,
                        remainingMs = sessionRemainingMs,
                        onReturnHome = { navigateHome() },
                        onOpenFreedom = {
                            startActivity(
                                Intent(this, MainActivity::class.java).apply {
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                                }
                            )
                            finish()
                        }
                    )
                }
            }
        }
    }

    private fun navigateHome() {
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        runCatching { startActivity(homeIntent) }
        finish()
    }

    companion object {
        const val MODE_APP_BLOCKED = "APP_BLOCKED"
        const val MODE_WEBSITE_BLOCKED = "WEBSITE_BLOCKED"
        const val MODE_SETTINGS_WAITING_ROOM = "SETTINGS_WAITING_ROOM"

        const val EXTRA_MODE = "extra_mode"
        const val EXTRA_BLOCKED_PACKAGE = "extra_blocked_package"
        const val EXTRA_BLOCKED_LABEL = "extra_blocked_label"
        const val EXTRA_REASON_SUBTITLE = "extra_reason_subtitle"
        const val EXTRA_SESSION_LABEL = "extra_session_label"
        const val EXTRA_STRICT_MODE = "extra_strict_mode"
    }
}

@Composable
private fun BlockedShieldScreen(
    isWebsiteMode: Boolean,
    blockedLabel: String,
    reasonSubtitle: String,
    sessionLabel: String,
    strictMode: Boolean,
    remainingMs: Long,
    onReturnHome: () -> Unit,
    onOpenFreedom: () -> Unit
) {
    val g = FreedomGlass.colors
    ForestGlassBackdrop(mood = if (strictMode) BackdropMood.LOCKED else BackdropMood.SHIELD) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = 24.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(76.dp)
                    .clip(CircleShape)
                    .background(g.primaryAccent.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isWebsiteMode) Icons.Rounded.Language else Icons.Rounded.Block,
                    contentDescription = "Blocked",
                    tint = g.primaryAccent,
                    modifier = Modifier.size(38.dp)
                )
            }

            Spacer(Modifier.height(22.dp))

            Text(
                text = if (isWebsiteMode) "Stay focused" else "$blockedLabel is blocked",
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                color = g.textPrimary,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = reasonSubtitle.ifBlank { "This app is not on your active allowlist." },
                style = MaterialTheme.typography.bodyMedium,
                color = g.textSecondary,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(24.dp))

            GlassCard(contentPadding = PaddingValues(20.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = sessionLabel,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = g.textPrimary
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = if (strictMode) "Locked Mode active" else "Active Focus Session",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (strictMode) g.lockedAmber else g.shieldActive
                        )
                    }
                    Text(
                        text = FreedomMonitorService.formatDuration(remainingMs),
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontFamily = InterFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontFeatureSettings = "tnum"
                        ),
                        color = g.primaryAccent
                    )
                }
            }

            Spacer(Modifier.height(28.dp))

            GlassPillButton(
                text = "Return Home",
                onClick = onReturnHome,
                testTag = "block_return_home_button"
            )

            Spacer(Modifier.height(12.dp))

            OutlinedButton(
                onClick = onOpenFreedom,
                shape = RoundedCornerShape(50),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .testTag("block_open_freedom_button")
            ) {
                Text(
                    text = "OPEN FREEDOM",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                    color = g.textPrimary
                )
            }
        }
    }
}

@Composable
private fun SettingsWaitingRoomScreen(
    routeLabel: String,
    sessionLabel: String,
    waitTargetElapsedMs: Long,
    onGrantAndOpenSettings: () -> Unit,
    onReturnHome: () -> Unit
) {
    val g = FreedomGlass.colors
    val totalWaitMs = SettingsWaitStateMachine.EMERGENCY_WAIT_DURATION_MS
    var remainingWaitMs by remember(waitTargetElapsedMs) {
        mutableLongStateOf(
            SettingsWaitStateMachine.remainingWaitMs(SystemClock.elapsedRealtime(), waitTargetElapsedMs)
        )
    }
    var unlockedReady by remember(waitTargetElapsedMs) { mutableStateOf(false) }

    LaunchedEffect(waitTargetElapsedMs) {
        while (waitTargetElapsedMs > 0L) {
            val now = SystemClock.elapsedRealtime()
            val rem = SettingsWaitStateMachine.remainingWaitMs(now, waitTargetElapsedMs)
            remainingWaitMs = rem
            if (SettingsWaitStateMachine.hasCompletedWait(now, waitTargetElapsedMs)) {
                unlockedReady = true
                break
            }
            delay(250L)
        }
    }

    val progress = ((totalWaitMs - remainingWaitMs).toFloat() / totalWaitMs.toFloat()).coerceIn(0f, 1f)
    val mins = (remainingWaitMs / 60_000L)
    val secs = ((remainingWaitMs % 60_000L) / 1000L)

    ForestGlassBackdrop(mood = BackdropMood.LOCKED) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = 24.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(76.dp)
                    .clip(CircleShape)
                    .background(g.lockedAmber.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (unlockedReady) Icons.Rounded.LockOpen else Icons.Rounded.HourglassTop,
                    contentDescription = "Emergency Wait",
                    tint = g.lockedAmber,
                    modifier = Modifier.size(38.dp)
                )
            }

            Spacer(Modifier.height(20.dp))

            Text(
                text = if (unlockedReady) "Emergency Access Ready" else "Emergency Settings Wait",
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                color = g.textPrimary,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = "$routeLabel is protected during Locked Mode ($sessionLabel). Stay on this screen continuously for 5 minutes to unlock Settings for 3 minutes.",
                style = MaterialTheme.typography.bodyMedium,
                color = g.textSecondary,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(24.dp))

            GlassCard(
                accentBorderColor = g.lockedAmber,
                contentPadding = PaddingValues(24.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = String.format("%02d:%02d", mins, secs),
                        style = MaterialTheme.typography.displayMedium.copy(
                            fontFamily = InterFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 48.sp,
                            fontFeatureSettings = "tnum"
                        ),
                        color = if (unlockedReady) g.shieldActive else g.lockedAmber
                    )
                    Spacer(Modifier.height(14.dp))
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(50)),
                        color = if (unlockedReady) g.shieldActive else g.lockedAmber,
                        trackColor = g.divider
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Rounded.Lock,
                            contentDescription = null,
                            tint = g.textSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = if (unlockedReady) {
                                "Auto-relocks automatically after 3 minutes"
                            } else {
                                "Leaving this screen resets the 5-minute countdown"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = g.textSecondary
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            if (unlockedReady) {
                GlassPillButton(
                    text = "Open Settings (3 min window)",
                    onClick = onGrantAndOpenSettings,
                    containerColor = g.shieldActive,
                    testTag = "settings_wait_unlock_button"
                )
                Spacer(Modifier.height(12.dp))
            }

            GlassPillButton(
                text = "Cancel & Stay Focused",
                onClick = onReturnHome,
                containerColor = if (unlockedReady) g.glassSurfaceElevated else g.primaryAccent,
                contentColor = if (unlockedReady) g.textPrimary else g.onPrimaryAccent,
                testTag = "settings_wait_cancel_button"
            )
        }
    }
}
