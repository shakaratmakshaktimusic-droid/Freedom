package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
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
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AddCircleOutline
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import android.util.LruCache
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.example.data.local.BlocklistProfileEntity
import com.example.data.repository.InstalledAppInfo
import com.example.domain.WebsiteCategory
import com.example.ui.FreedomUiState
import com.example.ui.theme.FreedomGlass
import com.example.ui.theme.FreedomMotion
import com.example.ui.theme.FreedomSwitch
import com.example.ui.theme.GlassCard
import com.example.ui.theme.GlassDivider
import com.example.ui.theme.GlassGroupedRow
import com.example.ui.theme.GlassPillButton
import com.example.ui.theme.GlassSegmentedControl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private object AppIconCache {
    private val cache = LruCache<String, ImageBitmap>(180)
    fun get(pkg: String): ImageBitmap? = cache.get(pkg)
    fun put(pkg: String, bmp: ImageBitmap) {
        cache.put(pkg, bmp)
    }
}

private sealed class BlocklistSubScreen(val depth: Int) {
    data object List : BlocklistSubScreen(0)
    data class EditProfile(val profileId: Long) : BlocklistSubScreen(1)
    data object PickAllowedApps : BlocklistSubScreen(2)
    data object PickWebCategories : BlocklistSubScreen(2)
    data object ManageAllowedWebsites : BlocklistSubScreen(2)
}

/**
 * Clean Blocklists hub & editors matching Screenshots 3, 5, and 7:
 *  - Reusable named Blocklists (Allowlist apps + 16 hidden website categories + Full Internet Browsing Block)
 *  - Underlying built-in, synced community, and auto-learned domain lists are NEVER shown in the UI
 *  - Dedicated Website Allowlist (URL Exceptions) screen to explicitly permit specific domains/URLs
 */
@Composable
fun BlocklistsScreen(
    uiState: FreedomUiState,
    onSaveBlocklist: (
        id: Long,
        name: String,
        fullBrowsingBlock: Boolean,
        categories: Set<WebsiteCategory>,
        allowedApps: Map<String, String>,
        onSuccess: () -> Unit
    ) -> Unit,
    onDeleteBlocklist: (Long) -> Unit,
    onAddAllowedWebsite: (String) -> Unit,
    onRemoveAllowedWebsite: (String) -> Unit,
    onAddCustomBlockedWebsite: (String) -> Unit,
    onRemoveCustomBlockedWebsite: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var subScreen by remember { mutableStateOf<BlocklistSubScreen>(BlocklistSubScreen.List) }

    // Draft state for currently edited Blocklist profile
    var draftProfileId by remember { mutableStateOf(0L) }
    var draftName by remember { mutableStateOf("") }
    var draftFullBrowsing by remember { mutableStateOf(false) }
    var draftCategories by remember { mutableStateOf<Set<WebsiteCategory>>(emptySet()) }
    var draftAllowedApps by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var openedExceptionsFromEditor by remember { mutableStateOf(false) }

    fun openEditorFor(profile: BlocklistProfileEntity?) {
        if (profile == null) {
            draftProfileId = 0L
            draftName = ""
            draftFullBrowsing = false
            draftCategories = setOf(WebsiteCategory.SOCIAL, WebsiteCategory.ADULT)
            draftAllowedApps = emptyMap()
        } else {
            draftProfileId = profile.id
            draftName = profile.name
            draftFullBrowsing = profile.fullInternetBrowsingBlock
            draftCategories = WebsiteCategory.parseCsv(profile.blockedWebCategoriesCsv)
            draftAllowedApps = uiState.allowedAppsByBlocklist[profile.id]
                ?.associate { it.packageName to it.appLabel }
                ?: emptyMap()
        }
        subScreen = BlocklistSubScreen.EditProfile(draftProfileId)
    }

    AnimatedContent(
        targetState = subScreen,
        transitionSpec = {
            val deeper = targetState.depth >= initialState.depth
            (slideInHorizontally(
                animationSpec = FreedomMotion.SlideOffsetSpec,
                initialOffsetX = { if (deeper) it / 8 else -it / 10 }
            ) + fadeIn(FreedomMotion.FastFadeIn)) togetherWith (slideOutHorizontally(
                animationSpec = FreedomMotion.SlideOffsetSpec,
                targetOffsetX = { if (deeper) -it / 10 else it / 8 }
            ) + fadeOut(FreedomMotion.FastFadeOut))
        },
        label = "blocklists_subscreen"
    ) { active ->
        when (active) {
            BlocklistSubScreen.List -> {
                BlocklistListView(
                    uiState = uiState,
                    onEditProfile = { openEditorFor(it) },
                    onDeleteProfile = onDeleteBlocklist,
                    onCreateNew = { openEditorFor(null) },
                    onOpenAllowedWebsites = {
                        openedExceptionsFromEditor = false
                        subScreen = BlocklistSubScreen.ManageAllowedWebsites
                    },
                    modifier = modifier
                )
            }

            is BlocklistSubScreen.EditProfile -> {
                BackHandler { subScreen = BlocklistSubScreen.List }
                BlocklistEditorView(
                    isNew = draftProfileId == 0L,
                    name = draftName,
                    onNameChange = { draftName = it },
                    allowedAppCount = draftAllowedApps.size,
                    selectedCategoryCount = draftCategories.size,
                    allowedWebsiteCount = uiState.allowedWebsites.size,
                    fullBrowsingBlock = draftFullBrowsing,
                    onFullBrowsingChange = { draftFullBrowsing = it },
                    onOpenAppPicker = { subScreen = BlocklistSubScreen.PickAllowedApps },
                    onOpenWebCategories = { subScreen = BlocklistSubScreen.PickWebCategories },
                    onOpenAllowedWebsites = {
                        openedExceptionsFromEditor = true
                        subScreen = BlocklistSubScreen.ManageAllowedWebsites
                    },
                    onCancel = { subScreen = BlocklistSubScreen.List },
                    onSave = {
                        onSaveBlocklist(
                            draftProfileId,
                            draftName.ifBlank { "My Blocklist" },
                            draftFullBrowsing,
                            draftCategories,
                            draftAllowedApps
                        ) {
                            subScreen = BlocklistSubScreen.List
                        }
                    },
                    modifier = modifier
                )
            }

            BlocklistSubScreen.PickAllowedApps -> {
                BackHandler { subScreen = BlocklistSubScreen.EditProfile(draftProfileId) }
                AllowedAppsPickerView(
                    installedApps = uiState.installedApps,
                    selectedApps = draftAllowedApps,
                    onToggleApp = { app ->
                        draftAllowedApps = if (app.packageName in draftAllowedApps) {
                            draftAllowedApps - app.packageName
                        } else {
                            draftAllowedApps + (app.packageName to app.label)
                        }
                    },
                    onBack = { subScreen = BlocklistSubScreen.EditProfile(draftProfileId) },
                    modifier = modifier
                )
            }

            BlocklistSubScreen.PickWebCategories -> {
                BackHandler { subScreen = BlocklistSubScreen.EditProfile(draftProfileId) }
                WebFilterCategoriesView(
                    selectedCategories = draftCategories,
                    customBlockedDomains = uiState.customBlockedWebsites.map { it.domain },
                    onToggleCategory = { cat, enabled ->
                        draftCategories = if (enabled) draftCategories + cat else draftCategories - cat
                    },
                    onAddCustomBlocked = onAddCustomBlockedWebsite,
                    onRemoveCustomBlocked = onRemoveCustomBlockedWebsite,
                    onBack = { subScreen = BlocklistSubScreen.EditProfile(draftProfileId) },
                    modifier = modifier
                )
            }

            BlocklistSubScreen.ManageAllowedWebsites -> {
                BackHandler {
                    subScreen = if (openedExceptionsFromEditor) {
                        BlocklistSubScreen.EditProfile(draftProfileId)
                    } else {
                        BlocklistSubScreen.List
                    }
                }
                AllowedWebsitesView(
                    allowedWebsites = uiState.allowedWebsites.map { it.pattern },
                    isStrictModeActive = uiState.isStrictModeActive,
                    onAdd = onAddAllowedWebsite,
                    onRemove = onRemoveAllowedWebsite,
                    onBack = {
                        subScreen = if (openedExceptionsFromEditor) {
                            BlocklistSubScreen.EditProfile(draftProfileId)
                        } else {
                            BlocklistSubScreen.List
                        }
                    },
                    modifier = modifier
                )
            }
        }
    }
}

@Composable
private fun BlocklistListView(
    uiState: FreedomUiState,
    onEditProfile: (BlocklistProfileEntity) -> Unit,
    onDeleteProfile: (Long) -> Unit,
    onCreateNew: () -> Unit,
    onOpenAllowedWebsites: () -> Unit,
    modifier: Modifier = Modifier
) {
    val g = FreedomGlass.colors
    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 104.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item(key = "blocklists_title") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Blocklists",
                        style = MaterialTheme.typography.titleLarge,
                        color = g.textPrimary
                    )
                }
            }

            items(uiState.blocklists, key = { it.id }) { profile ->
                var menuExpanded by remember { mutableStateOf(false) }
                val allowedCount = uiState.allowedPackagesFor(profile.id).size
                val catCount = remember(profile.blockedWebCategoriesCsv) {
                    WebsiteCategory.parseCsv(profile.blockedWebCategoriesCsv).size
                }

                GlassCard(
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
                    onClick = { onEditProfile(profile) }
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Shield,
                            contentDescription = null,
                            tint = g.primaryAccent,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = profile.name,
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = g.textPrimary
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = formatProfileSubtitle(profile, allowedCount, catCount),
                                style = MaterialTheme.typography.bodySmall,
                                color = g.textSecondary
                            )
                        }
                        Box {
                            IconButton(onClick = { menuExpanded = true }) {
                                Icon(
                                    imageVector = Icons.Rounded.MoreHoriz,
                                    contentDescription = "Profile options",
                                    tint = g.textSecondary
                                )
                            }
                            DropdownMenu(
                                expanded = menuExpanded,
                                onDismissRequest = { menuExpanded = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Edit") },
                                    onClick = {
                                        menuExpanded = false
                                        onEditProfile(profile)
                                    }
                                )
                                if (uiState.blocklists.size > 1) {
                                    DropdownMenuItem(
                                        text = { Text("Delete", color = g.dangerCoral) },
                                        onClick = {
                                            menuExpanded = false
                                            onDeleteProfile(profile.id)
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Always-accessible Website Allowlist (URL Exceptions) card
            item(key = "blocklists_exceptions") {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Website Exceptions",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = g.textPrimary
                )
                Spacer(Modifier.height(8.dp))
                GlassCard(contentPadding = PaddingValues(0.dp)) {
                    GlassGroupedRow(
                        title = "Allowed Websites",
                        subtitle = "URLs you add here stay accessible even when categories are blocked",
                        leadingIcon = Icons.Rounded.Public,
                        trailingText = "${uiState.allowedWebsites.size}",
                        onClick = onOpenAllowedWebsites,
                        modifier = Modifier.testTag("open_allowed_websites_row")
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(g.navSurface.copy(alpha = 0.85f))
                .padding(horizontal = 20.dp, vertical = 14.dp)
        ) {
            GlassPillButton(
                text = "Create a Blocklist",
                onClick = onCreateNew,
                testTag = "create_blocklist_button"
            )
        }
    }
}

@Composable
private fun BlocklistEditorView(
    isNew: Boolean,
    name: String,
    onNameChange: (String) -> Unit,
    allowedAppCount: Int,
    selectedCategoryCount: Int,
    allowedWebsiteCount: Int,
    fullBrowsingBlock: Boolean,
    onFullBrowsingChange: (Boolean) -> Unit,
    onOpenAppPicker: () -> Unit,
    onOpenWebCategories: () -> Unit,
    onOpenAllowedWebsites: () -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier
) {
    val g = FreedomGlass.colors
    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 104.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onCancel) {
                        Text("Cancel", color = g.primaryAccent, fontWeight = FontWeight.Medium)
                    }
                    Text(
                        text = if (isNew) "Create Blocklist" else "Edit Blocklist",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = g.textPrimary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(64.dp))
                }
            }

            // Blocklist Name card
            item {
                GlassCard(contentPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Blocklist Name",
                            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                            color = g.textPrimary
                        )
                        Spacer(Modifier.width(12.dp))
                        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                            if (name.isEmpty()) {
                                Text(
                                    text = "Enter a name",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = g.primaryAccent.copy(alpha = 0.65f)
                                )
                            }
                            BasicTextField(
                                value = name,
                                onValueChange = onNameChange,
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodyLarge.copy(
                                    color = g.primaryAccent,
                                    fontWeight = FontWeight.Medium,
                                    textAlign = TextAlign.End
                                ),
                                cursorBrush = SolidColor(g.primaryAccent),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("blocklist_name_input")
                            )
                        }
                    }
                }
            }

            // Allowed Apps (Allowlist mode)
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Allowed Apps (Allowlist)",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = g.textPrimary
                    )
                    GlassCard(contentPadding = PaddingValues(0.dp)) {
                        GlassGroupedRow(
                            title = "Select Allowed Android Apps",
                            subtitle = "Only chosen apps can run; all other apps & non-essential system apps are blocked",
                            leadingIcon = Icons.Rounded.AddCircleOutline,
                            trailingText = "$allowedAppCount",
                            onClick = onOpenAppPicker,
                            modifier = Modifier.testTag("editor_pick_apps_row")
                        )
                    }
                }
            }

            // Blocked Websites (16 Hidden Filter Categories + Allowed Website Exceptions)
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Website Protection",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = g.textPrimary
                    )
                    GlassCard(contentPadding = PaddingValues(0.dp)) {
                        GlassGroupedRow(
                            title = "Filter Categories",
                            subtitle = "16 hidden auto-updating categories (no blocked lists exposed)",
                            leadingIcon = Icons.Rounded.AddCircleOutline,
                            trailingText = "$selectedCategoryCount/16",
                            onClick = onOpenWebCategories,
                            modifier = Modifier.testTag("editor_pick_web_categories_row")
                        )
                        GlassDivider()
                        GlassGroupedRow(
                            title = "Allowed Websites (Exceptions)",
                            subtitle = "Explicitly permit specific domains or URLs",
                            leadingIcon = Icons.Rounded.CheckCircle,
                            trailingText = "$allowedWebsiteCount",
                            onClick = onOpenAllowedWebsites,
                            modifier = Modifier.testTag("editor_allowed_websites_row")
                        )
                    }
                }
            }

            // Advanced -> Full Internet Browsing Block (matching Screenshot 3!)
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Advanced",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = g.textPrimary
                    )
                    GlassCard(contentPadding = PaddingValues(18.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Full internet block",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = g.textPrimary
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = "When active, internet browsing is blocked in all browsers and in-app links (zero VPN). Other allowlisted apps can still access the internet.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = g.textSecondary
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            FreedomSwitch(
                                checked = fullBrowsingBlock,
                                onCheckedChange = onFullBrowsingChange,
                                modifier = Modifier.testTag("full_internet_browsing_switch")
                            )
                        }
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(g.navSurface.copy(alpha = 0.85f))
                .padding(horizontal = 20.dp, vertical = 14.dp)
        ) {
            GlassPillButton(
                text = if (isNew) "Create Blocklist" else "Save Blocklist",
                onClick = onSave,
                testTag = "save_blocklist_button"
            )
        }
    }
}

@Composable
private fun AllowedAppsPickerView(
    installedApps: List<InstalledAppInfo>,
    selectedApps: Map<String, String>,
    onToggleApp: (InstalledAppInfo) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val g = FreedomGlass.colors
    var query by remember { mutableStateOf("") }
    var filterTab by remember { mutableStateOf(0) } // 0 = All, 1 = Allowed, 2 = System

    val filtered = remember(installedApps, query, filterTab, selectedApps) {
        installedApps.filter { app ->
            val matchesQuery = query.isBlank() || app.label.contains(query, ignoreCase = true)
            val matchesFilter = when (filterTab) {
                1 -> app.packageName in selectedApps || app.isAlwaysExempt
                2 -> app.isSystemApp
                else -> true
            }
            matchesQuery && matchesFilter
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = g.primaryAccent)
            }
            Text(
                text = "Allowed Apps (${selectedApps.size})",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = g.textPrimary,
                modifier = Modifier.weight(1f)
            )
        }

        Column(
            modifier = Modifier.padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(g.glassSurface)
                    .border(1.dp, g.glassBorder, RoundedCornerShape(14.dp))
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Rounded.Search, contentDescription = null, tint = g.textSecondary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Box(modifier = Modifier.weight(1f)) {
                    if (query.isEmpty()) {
                        Text("Search apps…", color = g.textMuted, style = MaterialTheme.typography.bodyMedium)
                    }
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = g.textPrimary),
                        cursorBrush = SolidColor(g.primaryAccent),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            GlassSegmentedControl(
                options = listOf("All", "Allowed", "System"),
                selectedIndex = filterTab,
                onSelect = { filterTab = it },
                testTagPrefix = "apps_filter"
            )
        }

        Spacer(Modifier.height(10.dp))

        val context = LocalContext.current
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(filtered, key = { it.packageName }) { app ->
                val isChecked = app.isAlwaysExempt || app.packageName in selectedApps
                val iconBitmap by produceState(
                    initialValue = AppIconCache.get(app.packageName),
                    key1 = app.packageName
                ) {
                    if (value == null) {
                        val loaded = withContext(Dispatchers.IO) {
                            runCatching {
                                context.packageManager.getApplicationIcon(app.packageName)
                                    .toBitmap(72, 72)
                                    .asImageBitmap()
                            }.getOrNull()
                        }
                        if (loaded != null) {
                            AppIconCache.put(app.packageName, loaded)
                            value = loaded
                        }
                    }
                }
                GlassCard(
                    cornerRadius = 16.dp,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                    onClick = if (!app.isAlwaysExempt) {
                        { onToggleApp(app) }
                    } else null
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val bmp = iconBitmap
                        if (bmp != null) {
                            Image(
                                bitmap = bmp,
                                contentDescription = null,
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(10.dp))
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(g.primarySoft),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(app.label.take(1).uppercase(), color = g.primaryAccent, fontWeight = FontWeight.Bold)
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = app.label,
                                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                                color = g.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (app.isAlwaysExempt) {
                                Text(
                                    text = "Essential system function (always allowed)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = g.shieldActive
                                )
                            } else if (app.isSystemApp) {
                                Text(
                                    text = "System app (blocked unless allowed)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = g.textMuted
                                )
                            }
                        }
                        Checkbox(
                            checked = isChecked,
                            enabled = !app.isAlwaysExempt,
                            onCheckedChange = { onToggleApp(app) },
                            colors = CheckboxDefaults.colors(
                                checkedColor = g.primaryAccent,
                                checkmarkColor = g.onPrimaryAccent
                            )
                        )
                    }
                }
            }
        }
    }
}

/**
 * Clean 16 Filter Categories view matching Screenshot 7:
 *  - Shows ONLY clean category toggles and user's own optional custom blocked sites
 *  - Underlying built-in, community-synced, and auto-learned filter lists are NEVER shown!
 */
@Composable
private fun WebFilterCategoriesView(
    selectedCategories: Set<WebsiteCategory>,
    customBlockedDomains: List<String>,
    onToggleCategory: (WebsiteCategory, Boolean) -> Unit,
    onAddCustomBlocked: (String) -> Unit,
    onRemoveCustomBlocked: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val g = FreedomGlass.colors
    var customInput by remember { mutableStateOf("") }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = g.primaryAccent)
            }
            Text(
                text = "Select Websites to Block",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = g.textPrimary,
                modifier = Modifier.weight(1f)
            )
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Add Custom Blocked Website input
            item {
                GlassCard(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(modifier = Modifier.weight(1f)) {
                            if (customInput.isEmpty()) {
                                Text(
                                    text = "Add Custom Website to Block",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = g.primaryAccent.copy(alpha = 0.7f)
                                )
                            }
                            BasicTextField(
                                value = customInput,
                                onValueChange = { customInput = it },
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodyLarge.copy(color = g.textPrimary),
                                cursorBrush = SolidColor(g.primaryAccent),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("custom_blocked_website_input")
                            )
                        }
                        IconButton(
                            onClick = {
                                if (customInput.isNotBlank()) {
                                    onAddCustomBlocked(customInput)
                                    customInput = ""
                                }
                            },
                            modifier = Modifier.testTag("add_custom_blocked_button")
                        ) {
                            Icon(Icons.Rounded.Add, contentDescription = "Add custom site", tint = g.primaryAccent)
                        }
                    }
                    if (customBlockedDomains.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        customBlockedDomains.forEach { domain ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(domain, style = MaterialTheme.typography.bodyMedium, color = g.textSecondary)
                                IconButton(
                                    onClick = { onRemoveCustomBlocked(domain) },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(Icons.Rounded.Close, contentDescription = "Remove", tint = g.textMuted, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }
            }

            // 16 Clean Hidden Filter Categories matching Screenshot 7
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "Filter Categories",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = g.textPrimary
                    )
                    Text(
                        text = "Powered by regularly updated hidden domain lists and smart auto-detection.",
                        style = MaterialTheme.typography.bodySmall,
                        color = g.textSecondary
                    )
                }
            }

            item {
                GlassCard(contentPadding = PaddingValues(vertical = 4.dp)) {
                    WebsiteCategory.entries.forEachIndexed { idx, cat ->
                        val enabled = cat in selectedCategories
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onToggleCategory(cat, !enabled) }
                                .padding(horizontal = 18.dp, vertical = 10.dp)
                                .testTag("web_category_${cat.id.lowercase()}"),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = cat.title,
                                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                                    color = g.textPrimary
                                )
                                Text(
                                    text = cat.subtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = g.textMuted
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            FreedomSwitch(
                                checked = enabled,
                                onCheckedChange = { onToggleCategory(cat, it) }
                            )
                        }
                        if (idx < WebsiteCategory.entries.lastIndex) {
                            GlassDivider()
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun AllowedWebsitesView(
    allowedWebsites: List<String>,
    isStrictModeActive: Boolean,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val g = FreedomGlass.colors
    var urlInput by remember { mutableStateOf("") }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = g.primaryAccent)
            }
            Text(
                text = "Allowed Websites",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = g.textPrimary,
                modifier = Modifier.weight(1f)
            )
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Text(
                    text = "Put any domain or URL here (for example wikipedia.org or youtube.com/@channel) to keep it accessible even when filter categories or full browsing block are active.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = g.textSecondary
                )
            }

            item {
                GlassCard(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(modifier = Modifier.weight(1f)) {
                            if (urlInput.isEmpty()) {
                                Text(
                                    text = if (isStrictModeActive) "Locked during active session" else "Enter allowed URL or domain…",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = g.textMuted
                                )
                            }
                            BasicTextField(
                                value = urlInput,
                                onValueChange = { urlInput = it },
                                enabled = !isStrictModeActive,
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodyLarge.copy(color = g.textPrimary),
                                cursorBrush = SolidColor(g.primaryAccent),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("allowed_website_input")
                            )
                        }
                        IconButton(
                            enabled = !isStrictModeActive && urlInput.isNotBlank(),
                            onClick = {
                                onAdd(urlInput)
                                urlInput = ""
                            },
                            modifier = Modifier.testTag("add_allowed_website_button")
                        ) {
                            Icon(Icons.Rounded.Add, contentDescription = "Allow website", tint = g.primaryAccent)
                        }
                    }
                }
            }

            if (allowedWebsites.isEmpty()) {
                item {
                    GlassCard(contentPadding = PaddingValues(20.dp)) {
                        Text(
                            text = "No allowed website exceptions yet.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = g.textSecondary
                        )
                    }
                }
            } else {
                item {
                    GlassCard(contentPadding = PaddingValues(vertical = 4.dp)) {
                        allowedWebsites.forEachIndexed { idx, pattern ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 18.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.CheckCircle,
                                    contentDescription = null,
                                    tint = g.shieldActive,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    text = pattern,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = g.textPrimary,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(onClick = { onRemove(pattern) }) {
                                    Icon(Icons.Rounded.Close, contentDescription = "Remove", tint = g.textSecondary)
                                }
                            }
                            if (idx < allowedWebsites.lastIndex) {
                                GlassDivider()
                            }
                        }
                    }
                }
            }
        }
    }
}
