@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.jothivel.chits.ui

import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.zIndex
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.jothivel.chits.R
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jothivel.chits.ui.theme.*
import com.jothivel.chits.ui.components.AshAnimatedSearchBar
import com.jothivel.chits.ui.components.DetailFindToolbar
import com.jothivel.chits.ui.components.CashCalendarDialog
import com.jothivel.chits.ui.components.CashCalendarEntry
import com.jothivel.chits.ui.components.CashCalendarStatus
import com.jothivel.chits.ui.components.PremiumInputField
import com.jothivel.chits.ui.components.premiumInputFieldTriggerColors
import com.jothivel.chits.ui.components.BottomSheetPickerField
import com.jothivel.chits.ui.components.PremiumNotchedCard
import com.jothivel.chits.ui.ledger.ResizableLedgerSheet
import com.jothivel.chits.data.local.AppDatabase
import com.jothivel.chits.data.local.entity.ActivityLogEntity
import com.jothivel.chits.data.local.entity.ChitGroupEntity
import com.jothivel.chits.data.local.entity.InstallmentEntity
import com.jothivel.chits.data.local.entity.MemberEntity
import com.jothivel.chits.data.local.entity.ChitMembershipEntity
import com.jothivel.chits.data.local.entity.PaymentEntity
import com.jothivel.chits.data.local.entity.CollectionReceiptEntity
import com.jothivel.chits.data.local.entity.FinancialTransactionEntity
import com.jothivel.chits.data.models.ChitTemplate
import com.jothivel.chits.data.local.CollectionService
import com.jothivel.chits.data.local.SavedCollection
import com.jothivel.chits.data.local.FinancialService
import com.jothivel.chits.utils.CsvDownloadHelper
import com.jothivel.chits.utils.ReceiptPdfHelper
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.*

private enum class AppDestination { HOME, COLLECT, LEDGER, PENDING, NEW_CHIT, ADD_MEMBER, SETTINGS, SETTLEMENT, DELIVERY, TODAY_WORK, CUSTOMER_PROFILE, DAILY_CLOSING, LABOUR, CHIT_GROUP_DETAIL }

private data class DueCustomer(
    val name: String,
    val code: String,
    val phone: String,
    val area: String,
    val filterArea: String,
    val chit: String,
    val chitValue: Long,
    val payable: Long,
    val paid: Long,
    val pending: Long,
    val lastPaid: String,
    val dueDate: String,
    val installments: List<String>,
    val agent: String,
    val overdueDays: Int
    ,val recentContactAt: Long = 0L,
    val groupId: String = ""
)

private data class CollectionLookupData(
    val members: List<MemberEntity> = emptyList(),
    val groups: List<ChitGroupEntity> = emptyList(),
    val memberships: List<ChitMembershipEntity> = emptyList()
)

private data class RecentCollectionRow(val memberName: String, val groupCode: String, val time: String, val amount: String)

private data class HomeCloudStatus(
    val firebaseReady: Boolean = false,
    val pendingUploads: Int = 0,
    val lastSyncAt: Long = 0L
)

private data class DashboardCollectionSnapshot(
    val todayAmountPaise: Long = 0,
    val todayCount: Int = 0,
    val calendarEntries: List<CashCalendarEntry> = emptyList(),
    val recentRows: List<RecentCollectionRow> = emptyList(),
    val pendingPaise: Long = 0,
    val pendingCustomers: Int = 0,
    val settlementPaise: Long = 0,
    val deliveryPaise: Long = 0,
    val cloudStatus: HomeCloudStatus = HomeCloudStatus()
)

private fun money(value: Long): String = "₹" + NumberFormat.getNumberInstance(Locale("en", "IN")).format(value)

@Composable
fun ApprovedAppFlow(
    onLogout: () -> Unit,
    onBackupDatabase: () -> Unit,
    onRestoreDatabase: () -> Unit,
    onExportCsv: () -> Unit,
    onImportCsv: () -> Unit = {}
) {
    val roleCheckContext = LocalContext.current
    val userRole = remember { com.jothivel.chits.utils.AppPreferences(roleCheckContext).getUserRole() }
    if (userRole == com.jothivel.chits.utils.AppPreferences.ROLE_AGENT) {
        AgentAppFlow(onLogout = onLogout)
        return
    }

    // No install may keep running on the factory PIN 1234: block until the admin chooses their own.
    var mustChangePin by remember { mutableStateOf(com.jothivel.chits.utils.AppPreferences(roleCheckContext).isUsingDefaultPin()) }
    if (mustChangePin) com.jothivel.chits.ui.settings.ForcePinChangeDialog { mustChangePin = false }

    var destination by rememberSaveable { mutableStateOf(AppDestination.HOME) }
    var previousDestination by rememberSaveable { mutableStateOf(AppDestination.HOME) }
    var collectionCustomer by rememberSaveable { mutableStateOf("") }
    var collectionChit by rememberSaveable { mutableStateOf("") }
    var ledgerCustomer by remember { mutableStateOf<DueCustomer?>(null) }
    var profileCustomerId by rememberSaveable { mutableStateOf("") }
    var addMemberPresetGroupId by rememberSaveable { mutableStateOf("") }
    var selectedGroupIdForDetail by rememberSaveable { mutableStateOf("") }

    fun open(next: AppDestination) {
        if (next == destination) return
        previousDestination = destination
        destination = next
    }

    BackHandler(enabled = destination != AppDestination.HOME) {
        destination = when (destination) {
            AppDestination.NEW_CHIT,
            AppDestination.ADD_MEMBER,
            AppDestination.SETTINGS,
            AppDestination.SETTLEMENT,
            AppDestination.DELIVERY -> AppDestination.HOME
            AppDestination.CHIT_GROUP_DETAIL -> AppDestination.NEW_CHIT
            AppDestination.TODAY_WORK,
            AppDestination.DAILY_CLOSING -> AppDestination.HOME
            AppDestination.CUSTOMER_PROFILE -> previousDestination.takeIf { it != AppDestination.CUSTOMER_PROFILE } ?: AppDestination.HOME
            else -> previousDestination.takeIf { it != destination } ?: AppDestination.HOME
        }
        if (destination == AppDestination.HOME) previousDestination = AppDestination.HOME
    }

    Scaffold(
        containerColor = MaroonBackground,
        bottomBar = {
            val showBottomBar = destination !in setOf(AppDestination.LEDGER, AppDestination.NEW_CHIT, AppDestination.ADD_MEMBER, AppDestination.SETTINGS, AppDestination.SETTLEMENT, AppDestination.DELIVERY, AppDestination.LABOUR, AppDestination.CHIT_GROUP_DETAIL)
            if (showBottomBar) ApprovedBottomBar(destination) { open(it) }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            androidx.compose.animation.AnimatedContent(
                targetState = destination,
                modifier = Modifier.fillMaxSize(),
                transitionSpec = {
                    androidx.compose.animation.ContentTransform(
                        targetContentEnter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(140)),
                        initialContentExit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(90))
                    )
                },
                label = "appPageTransition"
            ) { activeDestination ->
            when (activeDestination) {
                AppDestination.HOME -> ApprovedDashboard(
                    onLedger = { ledgerCustomer = null; open(AppDestination.LEDGER) },
                    onOpenCustomer = { code -> profileCustomerId = code; open(AppDestination.CUSTOMER_PROFILE) },
                    onCollect = { collectionCustomer = ""; collectionChit = ""; open(AppDestination.COLLECT) },
                    onPending = { open(AppDestination.PENDING) },
                    onNewChit = { open(AppDestination.NEW_CHIT) },
                    onAddMember = { addMemberPresetGroupId = ""; open(AppDestination.ADD_MEMBER) },
                    onSettlement = { open(AppDestination.SETTLEMENT) },
                    onDelivery = { open(AppDestination.DELIVERY) },
                    onTodayWork = { open(AppDestination.TODAY_WORK) },
                    onClosing = { open(AppDestination.DAILY_CLOSING) },
                    onSettings = { open(AppDestination.SETTINGS) }
                )
                AppDestination.COLLECT -> CollectionScreen(
                    initialCustomer = collectionCustomer,
                    initialChit = collectionChit,
                    onBack = { destination = previousDestination }
                )
                AppDestination.LEDGER -> LedgerScreenApproved(
                    selectedCustomer = ledgerCustomer,
                    onBack = { destination = previousDestination },
                    onCollect = { customer, chit -> collectionCustomer = customer; collectionChit = chit; open(AppDestination.COLLECT) }
                )
                AppDestination.SETTINGS -> SettingsScreenApproved(
                    onBack = { destination = AppDestination.HOME },
                    onBackup = onBackupDatabase,
                    onRestore = onRestoreDatabase,
                    onExport = onExportCsv,
                    onImport = onImportCsv,
                    onLabour = { open(AppDestination.LABOUR) },
                    onLogout = onLogout
                )
                AppDestination.LABOUR -> com.jothivel.chits.ui.labour.LabourManagementScreen(
                    onBack = { destination = AppDestination.SETTINGS }
                )
                AppDestination.PENDING -> PendingScreen(
                    onBack = { destination = previousDestination },
                    onOpenLedger = { customer -> profileCustomerId=customer.code; open(AppDestination.CUSTOMER_PROFILE) },
                    onCollect = { customer -> collectionCustomer = customer.name; collectionChit = customer.chit; open(AppDestination.COLLECT) }
                )
                AppDestination.NEW_CHIT -> CompactNewChitScreen(onBack = { destination = AppDestination.HOME }, onAddMember = { groupId -> addMemberPresetGroupId = groupId; destination = AppDestination.ADD_MEMBER }, onOpenGroup = { groupId -> selectedGroupIdForDetail = groupId; open(AppDestination.CHIT_GROUP_DETAIL) })
                AppDestination.CHIT_GROUP_DETAIL -> ChitGroupDetailScreen(groupId = selectedGroupIdForDetail, onBack = { destination = AppDestination.NEW_CHIT }, onAddMember = { groupId -> addMemberPresetGroupId = groupId; open(AppDestination.ADD_MEMBER) })
                AppDestination.ADD_MEMBER -> CompactAddMemberScreen(onBack = { destination = AppDestination.HOME }, initialGroupId = addMemberPresetGroupId)
                AppDestination.SETTLEMENT -> FinancialEntryScreen("SETTLEMENT", onBack = { destination = AppDestination.HOME })
                AppDestination.DELIVERY -> FinancialEntryScreen("DELIVERY", onBack = { destination = AppDestination.HOME })
                AppDestination.TODAY_WORK -> TodayWorkScreen(onBack={destination=AppDestination.HOME}, onProfile={profileCustomerId=it;open(AppDestination.CUSTOMER_PROFILE)}, onCollect={customer,chit->collectionCustomer=customer;collectionChit=chit;open(AppDestination.COLLECT)})
                AppDestination.CUSTOMER_PROFILE -> CustomerProfileScreen(profileCustomerId, onBack={destination=previousDestination}, onCollect={customer,chit->collectionCustomer=customer;collectionChit=chit;open(AppDestination.COLLECT)})
                AppDestination.DAILY_CLOSING -> com.jothivel.chits.ui.closing.DailyClosingScreen(onBack = { destination = AppDestination.HOME })
            }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// LABOUR (FIELD AGENT) APP SHELL — separate nav for the AGENT role, admin flow untouched above
// ═══════════════════════════════════════════════════════════════════════════════

private enum class AgentDestination { MY_CHITS, MEMBER_LIST, COLLECT, TODAY_SUMMARY }

@Composable
private fun AgentAppFlow(onLogout: () -> Unit) {
    var destination by rememberSaveable { mutableStateOf(AgentDestination.MY_CHITS) }
    var previousDestination by rememberSaveable { mutableStateOf(AgentDestination.MY_CHITS) }
    var selectedGroupId by rememberSaveable { mutableStateOf("") }
    var selectedGroupLabel by rememberSaveable { mutableStateOf("") }
    var collectCustomer by rememberSaveable { mutableStateOf("") }
    var collectChit by rememberSaveable { mutableStateOf("") }

    // Deactivating an agent only blocked *future* logins before this - an already-open app kept
    // full access indefinitely. Re-checks isActive every few minutes for as long as the agent
    // stays in this flow, and forces a real logout (clearing the offline cache too) the moment a
    // deactivation is seen - without this, a fired/blocked agent whose app happens to stay open
    // could keep recording collections with no way to stop them short of pulling the device.
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        while (true) {
            val stillActive = withContext(Dispatchers.IO) { com.jothivel.chits.data.firebase.AgentAuthRepository.refreshAndCheckActive(context) }
            if (stillActive == false) {
                withContext(Dispatchers.IO) { com.jothivel.chits.utils.AppPreferences(context).clearAgentSession() }
                onLogout()
                break
            }
            delay(5 * 60 * 1000L)
        }
    }

    fun open(next: AgentDestination) {
        if (next == destination) return
        previousDestination = destination
        destination = next
    }

    BackHandler(enabled = destination != AgentDestination.MY_CHITS) {
        destination = if (destination == AgentDestination.MEMBER_LIST) AgentDestination.MY_CHITS
        else previousDestination.takeIf { it != destination } ?: AgentDestination.MY_CHITS
    }

    Scaffold(
        containerColor = MaroonBackground,
        bottomBar = {
            // The bottom bar's own "Collect" tab must start a fresh, blank collection every
            // time - without this reset it kept showing whichever specific member/chit was
            // last opened via a pending row's own "Collect" action (collectCustomer/collectChit
            // stayed set from that earlier navigation).
            if (destination != AgentDestination.MEMBER_LIST) AgentBottomBar(destination) { route ->
                if (route == AgentDestination.COLLECT) { collectCustomer = ""; collectChit = "" }
                open(route)
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            androidx.compose.animation.AnimatedContent(
                targetState = destination,
                modifier = Modifier.fillMaxSize(),
                transitionSpec = {
                    androidx.compose.animation.ContentTransform(
                        targetContentEnter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(140)),
                        initialContentExit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(90))
                    )
                },
                label = "agentPageTransition"
            ) { activeDestination ->
                when (activeDestination) {
                    AgentDestination.MY_CHITS -> Column(Modifier.fillMaxSize()) {
                        BrandTopBar(stringResource(R.string.agent_tab_my_chits), action = Icons.Default.Logout, onAction = onLogout)
                        com.jothivel.chits.ui.labour.AgentMyChitsScreen(
                            onOpenGroup = { groupId, label -> selectedGroupId = groupId; selectedGroupLabel = label; open(AgentDestination.MEMBER_LIST) }
                        )
                    }
                    AgentDestination.MEMBER_LIST -> Column(Modifier.fillMaxSize()) {
                        BrandTopBar(selectedGroupLabel.ifBlank { stringResource(R.string.agent_members_title) }, back = { destination = AgentDestination.MY_CHITS })
                        com.jothivel.chits.ui.labour.AgentMemberListScreen(
                            groupId = selectedGroupId,
                            onCollect = { memberName, chitLabel -> collectCustomer = memberName; collectChit = chitLabel; open(AgentDestination.COLLECT) }
                        )
                    }
                    AgentDestination.COLLECT -> CollectionScreen(
                        initialCustomer = collectCustomer,
                        initialChit = collectChit,
                        onBack = { destination = previousDestination }
                    )
                    AgentDestination.TODAY_SUMMARY -> Column(Modifier.fillMaxSize()) {
                        BrandTopBar(stringResource(R.string.agent_tab_today), action = Icons.Default.Logout, onAction = onLogout)
                        com.jothivel.chits.ui.labour.AgentTodaySummaryScreen()
                    }
                }
            }
        }
    }
}

@Composable
private fun AgentBottomBar(selected: AgentDestination, onSelect: (AgentDestination) -> Unit) {
    val entries = listOf(
        Triple(AgentDestination.MY_CHITS, Icons.Default.ListAlt, stringResource(R.string.agent_tab_my_chits)),
        Triple(AgentDestination.COLLECT, Icons.Default.AddCircle, stringResource(R.string.agent_tab_collect)),
        Triple(AgentDestination.TODAY_SUMMARY, Icons.Default.Today, stringResource(R.string.agent_tab_today))
    )
    Box(Modifier.fillMaxWidth().padding(horizontal=10.dp,vertical = 5.dp), contentAlignment = Alignment.Center) {
        Surface(
            modifier = Modifier.fillMaxWidth().height(54.dp),
            shape = RoundedCornerShape(18.dp),
            color = Color.Transparent,
            border = BorderStroke(1.dp, MaroonDark.copy(alpha = .35f)),
            shadowElevation = 3.dp
        ) {
            Row(
                Modifier.fillMaxSize().background(LoginGradient).padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
            entries.forEach { (route, icon, label) ->
                val isSelected = selected == route
                Column(
                    Modifier.weight(1f).fillMaxHeight().clickable { onSelect(route) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Box(
                        modifier = Modifier
                            .width(if (isSelected) 44.dp else 32.dp)
                            .height(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            icon,
                            label,
                            tint = if (isSelected) Color.White else Color.White.copy(alpha=.60f),
                            modifier = Modifier.size(15.dp)
                        )
                    }
                    Spacer(Modifier.height(1.dp))
                    Text(
                        label,
                        color = if (isSelected) Color.White else Color.White.copy(alpha=.62f),
                        fontSize = 8.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        maxLines = 1
                    )
                }
            }
        }
        }
    }
}

@Composable
private fun ApprovedBottomBar(selected: AppDestination, onSelect: (AppDestination) -> Unit) {
    val entries = listOf(
        Triple(AppDestination.HOME, Icons.Default.Home, "Home"),
        Triple(AppDestination.LEDGER, Icons.Default.MenuBook, "Ledger"),
        Triple(AppDestination.COLLECT, Icons.Default.AddCircle, "Collection"),
        Triple(AppDestination.PENDING, Icons.Default.PendingActions, "Pending")
    )
    Box(Modifier.fillMaxWidth().padding(horizontal=10.dp,vertical = 5.dp), contentAlignment = Alignment.Center) {
        Surface(
            modifier = Modifier.fillMaxWidth().height(54.dp),
            shape = RoundedCornerShape(18.dp),
            color = MaroonPrimary,
            border = BorderStroke(1.dp, MaroonDark.copy(alpha = .35f)),
            shadowElevation = 3.dp
        ) {
            Row(
                Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
            entries.forEach { (route, icon, label) ->
                val isSelected = selected == route
                Column(
                    Modifier.weight(1f).fillMaxHeight().clickable { onSelect(route) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Box(
                        modifier = Modifier
                            .width(if (isSelected) 44.dp else 32.dp)
                            .height(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            icon,
                            label,
                            tint = if (isSelected) Color.White else Color.White.copy(alpha=.60f),
                            modifier = Modifier.size(15.dp)
                        )
                    }
                    Spacer(Modifier.height(1.dp))
                    Text(
                        label,
                        color = if (isSelected) Color.White else Color.White.copy(alpha=.62f),
                        fontSize = 8.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        maxLines = 1
                    )
                }
            }
        }
        }
    }
}

@Composable
internal fun BrandTopBar(
    title: String,
    back: (() -> Unit)? = null,
    action: ImageVector? = null,
    onAction: (() -> Unit)? = null,
    secondAction: ImageVector? = null,
    onSecondAction: (() -> Unit)? = null,
    brandTitle: Boolean = false
) {
    Surface(color = if (brandTitle) Color.Transparent else MaroonPrimary, shadowElevation = if (brandTitle) 0.dp else 2.dp) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(52.dp)
                .padding(horizontal = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (back != null) {
                IconButton(onClick = back) {
                    Icon(Icons.Default.ArrowBack, "Back", tint = Color.White)
                }
            } else {
                Spacer(Modifier.width(8.dp))
            }
            if (brandTitle) {
                Box(Modifier.weight(1f)) { ShimmerBrandTitle(title) }
            } else {
                Text(title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, letterSpacing=.1.sp, modifier = Modifier.weight(1f))
            }
            if (action != null) IconButton(onClick = { onAction?.invoke() }, enabled = onAction != null) { Icon(action, null, tint = Color.White, modifier = Modifier.size(20.dp)) }
            if (secondAction != null) IconButton(onClick = { onSecondAction?.invoke() }, enabled = onSecondAction != null) { Icon(secondAction, null, tint = Color.White, modifier = Modifier.size(20.dp)) }
        }
    }
}

/**
 * The app's own brand wordmark on the Home top bar only — a distinct serif face (every other
 * screen title stays the plain sans-serif BrandTopBar look) plus a very mild, slow gold sheen
 * sweeping across it. Kept deliberately subtle: low-contrast colors, one slow 3.2s cycle, no
 * layout-affecting animation, so it reads as a quiet brand touch rather than a loading shimmer.
 */
@Composable
private fun ShimmerBrandTitle(title: String) {
    val infiniteTransition = rememberInfiniteTransition(label = "brandShimmer")
    val sweep by infiniteTransition.animateFloat(
        initialValue = -220f,
        targetValue = 420f,
        animationSpec = infiniteRepeatable(animation = tween(3200, easing = LinearEasing), repeatMode = RepeatMode.Restart),
        label = "brandShimmerSweep"
    )
    val brush = Brush.linearGradient(
        colors = listOf(Color.White, Color.White, AccentGoldLight.copy(alpha = .9f), Color.White, Color.White),
        start = Offset(sweep - 70f, 0f),
        end = Offset(sweep + 70f, 0f)
    )
    Text(
        text = title,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        style = TextStyle(
            brush = brush,
            fontFamily = FontFamily.Serif,
            fontWeight = FontWeight.Bold,
            fontSize = 23.sp,
            letterSpacing = .3.sp
        )
    )
}

@Composable
private fun ApprovedDashboard(onLedger: () -> Unit, onOpenCustomer: (String) -> Unit, onCollect: () -> Unit, onPending: () -> Unit, onNewChit: () -> Unit, onAddMember: () -> Unit, onSettlement: () -> Unit, onDelivery: () -> Unit, onTodayWork: () -> Unit, onClosing: () -> Unit, onSettings: () -> Unit) {
    val context = LocalContext.current
    var showCalendar by remember { mutableStateOf(false) }
    val dashboardListState = rememberLazyListState()
    val collectionSnapshot by produceState(initialValue = DashboardCollectionSnapshot(), context) {
        value = withContext(Dispatchers.IO) {
            val db = AppDatabase.getDatabase(context)
            val today = CollectionService.todayKey()
            val dayStartMillis = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
            val dayEndMillis = dayStartMillis + 86_400_000L
            val members = db.memberDao().getAllMembersSync().associateBy { it.id }
            val groups = db.groupDao().getAllGroupsSync().associateBy { it.id }
            val receipts = db.collectionReceiptDao().getRecentSync(Int.MAX_VALUE)
            val scheduledDues = CollectionService.buildCalendarSchedule(db)
            val dueAmounts = db.membershipDao().getAllActiveSync().map { CollectionService.calculateDuePaise(db, it.memberId, it.groupId) }.filter { it > 0 }
            val prefs = com.jothivel.chits.utils.AppPreferences(context)
            DashboardCollectionSnapshot(
                // Real-clock "today" (paidAt-based) so this matches the labour app's own Today
                // summary and the admin Today's Work/Day Closing screens - not the separately
                // editable businessDate field.
                todayAmountPaise = db.collectionReceiptDao().getTotalForTimeRangeSync(dayStartMillis, dayEndMillis),
                todayCount = db.collectionReceiptDao().getCountForTimeRangeSync(dayStartMillis, dayEndMillis),
                calendarEntries = receipts.map { receipt ->
                    val member = members[receipt.memberId]
                    val group = groups[receipt.groupId]
                    CashCalendarEntry(receipt.businessDate, member?.name ?: receipt.memberId, member?.id ?: receipt.memberId, group?.registerNo ?: receipt.groupId, receipt.amountPaidPaise / 100, CashCalendarStatus.PAID, receipt.mode, receipt.receiptNo)
                } + scheduledDues.map { due ->
                    CashCalendarEntry(due.dateKey, due.memberName, due.memberId, due.chitNo, due.remainingPaise / 100, CashCalendarStatus.DUE, "${due.installmentNo} TH")
                },
                recentRows = receipts.take(4).map { receipt ->
                    val member = members[receipt.memberId]
                    val group = groups[receipt.groupId]
                    RecentCollectionRow(member?.name ?: receipt.memberId, group?.registerNo ?: receipt.groupId, SimpleDateFormat("dd MMM, hh:mm a", Locale.ENGLISH).format(Date(receipt.paidAt)), money(receipt.amountPaidPaise / 100))
                },
                pendingPaise = dueAmounts.sum(),
                pendingCustomers = dueAmounts.size,
                settlementPaise = db.financialTransactionDao().getPostedTotalByTypeSync("SETTLEMENT"),
                deliveryPaise = db.financialTransactionDao().getPostedTotalByTypeSync("DELIVERY"),
                cloudStatus = HomeCloudStatus(
                    firebaseReady = com.jothivel.chits.data.firebase.FirebaseSetup.firestoreOrNull(context) != null,
                    pendingUploads = com.jothivel.chits.data.firebase.AgentCollectionSync.pendingCount(context),
                    lastSyncAt = prefs.getLastCloudSyncAt()
                )
            )
        }
    }
    val calendarEntries = collectionSnapshot.calendarEntries
    Column(Modifier.fillMaxSize().background(MaroonBackground)) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(bottomStart = 22.dp, bottomEnd = 22.dp))
                .background(LoginGradient)
        ) {
            BrandTopBar(
                "Jothi Vel Chits",
                action = Icons.Default.CalendarMonth,
                onAction = { showCalendar = true },
                secondAction = Icons.Default.Settings,
                onSecondAction = onSettings,
                brandTitle = true
            )
            Column(Modifier.padding(horizontal = 16.dp, vertical = 5.dp)) {
                Text(stringResource(R.string.home_welcome), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text(SimpleDateFormat("EEEE, dd MMM yyyy", Locale.ENGLISH).format(Date()), color = Color.White.copy(alpha = .70f), fontSize = 9.sp)
            }
            HomeMetricCarousel(
                cards = listOf(
                    HomeStatEntry(stringResource(R.string.home_today_collection), money(collectionSnapshot.todayAmountPaise / 100), stringResource(R.string.home_collections_count, collectionSnapshot.todayCount), MaroonPrimary, onCollect),
                    HomeStatEntry(stringResource(R.string.home_pending_metric), money(collectionSnapshot.pendingPaise / 100), stringResource(R.string.home_pending_dues_count, collectionSnapshot.pendingCustomers), AccentGold, onPending),
                    HomeStatEntry(stringResource(R.string.home_delivery), money(collectionSnapshot.deliveryPaise / 100), stringResource(R.string.home_recorded_delivery), AccentGreen, onDelivery),
                    HomeStatEntry(stringResource(R.string.home_settlement), money(collectionSnapshot.settlementPaise / 100), stringResource(R.string.home_recorded_settlement), MaroonDark, onSettlement)
                ),
                autoPlayEnabled = !dashboardListState.isScrollInProgress
            )
        }
        LazyColumn(
            state = dashboardListState,
            modifier = Modifier.fillMaxSize().background(MaroonBackground),
            contentPadding = PaddingValues(top = 8.dp, bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                HomeCloudStatusCard(
                    status = collectionSnapshot.cloudStatus,
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
            }
            item {
                HomeMaroonMenuCard(
                    onTodayWork = onTodayWork,
                    onClosing = onClosing,
                    onNewChit = onNewChit,
                    onAddMember = onAddMember,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
                )
            }
            item { Box(Modifier.padding(horizontal = 12.dp)) { SectionTitle(stringResource(R.string.home_recent_activity)) } }
            if (collectionSnapshot.recentRows.isEmpty()) item { Text(stringResource(R.string.home_no_collection_activity), color = TextGray, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) }
            items(collectionSnapshot.recentRows) { item ->
                Box(Modifier.padding(horizontal = 12.dp)) {
                    ActivityRow(stringResource(R.string.home_collection_received_from, item.memberName, item.groupCode), item.time, item.amount)
                }
            }
        }
    }
    if (showCalendar) CashCalendarDialog(calendarEntries, onDismiss = { showCalendar = false }, onCustomerClick = { entry -> showCalendar = false; onOpenCustomer(entry.customerCode) })
}

private data class HomeStatEntry(
    val title: String,
    val value: String,
    val subtitle: String,
    val color: Color,
    val onClick: () -> Unit
)

@Composable
private fun HomeMetricCarousel(cards: List<HomeStatEntry>, autoPlayEnabled: Boolean) {
    if (cards.isEmpty()) return
    var activeIndex by rememberSaveable(cards.size) { mutableIntStateOf(0) }
    var dragDistance by remember { mutableFloatStateOf(0f) }

    fun moveBy(step: Int) {
        activeIndex = ((activeIndex + step) % cards.size + cards.size) % cards.size
    }

    LaunchedEffect(cards.size, activeIndex, autoPlayEnabled) {
        if (!autoPlayEnabled) return@LaunchedEffect
        delay(2_000L)
        moveBy(1)
    }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .height(92.dp)
                .clipToBounds()
                .pointerInput(cards.size) {
                    detectHorizontalDragGestures(
                        onDragStart = { dragDistance = 0f },
                        onHorizontalDrag = { change, amount -> change.consume(); dragDistance += amount },
                        onDragEnd = {
                            if (dragDistance <= -45f) moveBy(1)
                            if (dragDistance >= 45f) moveBy(-1)
                            dragDistance = 0f
                        },
                        onDragCancel = { dragDistance = 0f }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            val cardWidth = (maxWidth * .62f).coerceIn(200.dp, 250.dp)
            val density = LocalDensity.current
            cards.forEachIndexed { index, card ->
                var relative = index - activeIndex
                val half = cards.size / 2
                if (relative > half) relative -= cards.size
                if (relative < -half) relative += cards.size
                if (kotlin.math.abs(relative) > 2) return@forEachIndexed

                val distance = kotlin.math.abs(relative)
                val targetX = when (relative) {
                    -2 -> (-cardWidth.value * .74f).dp
                    -1 -> (-cardWidth.value * .52f).dp
                    0 -> 0.dp
                    1 -> (cardWidth.value * .52f).dp
                    else -> (cardWidth.value * .74f).dp
                }
                val targetY = when (distance) {
                    0 -> 0.dp
                    1 -> 13.dp
                    else -> 25.dp
                }
                val targetScale = when (distance) {
                    0 -> 1f
                    1 -> .78f
                    else -> .62f
                }
                val targetAlpha = when (distance) {
                    0 -> 1f
                    1 -> .34f
                    else -> .16f
                }
                val targetZ = when (distance) {
                    0 -> 5f
                    1 -> 3f
                    else -> 1f
                }
                val x by animateFloatAsState(with(density) { targetX.toPx() }, tween(650, easing = androidx.compose.animation.core.FastOutSlowInEasing), label = "heroMetricX")
                val y by animateFloatAsState(with(density) { targetY.toPx() }, tween(650, easing = androidx.compose.animation.core.FastOutSlowInEasing), label = "heroMetricY")
                val scale by animateFloatAsState(targetScale, tween(650, easing = androidx.compose.animation.core.FastOutSlowInEasing), label = "heroMetricScale")
                val alpha by animateFloatAsState(targetAlpha, tween(420), label = "heroMetricAlpha")

                HeroMetricCard(
                    card = card,
                    modifier = Modifier
                        .width(cardWidth)
                        .zIndex(targetZ)
                        .graphicsLayer {
                            translationX = x
                            translationY = y
                            scaleX = scale
                            scaleY = scale
                            this.alpha = alpha
                        },
                    onClick = { if (index == activeIndex) card.onClick() else activeIndex = index }
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(32.dp)
                .padding(start = 8.dp, end = 8.dp, bottom = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            IconButton(
                onClick = { moveBy(-1) },
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .size(26.dp)
            ) {
                Icon(Icons.Default.ChevronLeft, null, tint = Color.White, modifier = Modifier.size(18.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                cards.indices.forEach { index ->
                    val selected = index == activeIndex
                    Box(
                        Modifier
                            .width(if (selected) 16.dp else 5.dp)
                            .height(4.dp)
                            .background(if (selected) Color.White else Color.White.copy(alpha = .28f), RoundedCornerShape(6.dp))
                            .clickable { activeIndex = index }
                    )
                }
            }
            IconButton(
                onClick = { moveBy(1) },
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .size(26.dp)
            ) {
                Icon(Icons.Default.ChevronRight, null, tint = Color.White, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun HeroMetricCard(card: HomeStatEntry, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(13.dp)
    Box(
        modifier
            .shadow(3.dp, shape, clip = false, ambientColor = Color.Black.copy(alpha = .12f), spotColor = Color.Black.copy(alpha = .10f))
            .background(Brush.linearGradient(listOf(Color.White, Color(0xFFFCF4F4))), shape)
            .border(.7.dp, Color(0xFFF1DCDD), shape)
            .clickable(onClick = onClick)
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Text(card.title.uppercase(Locale.ENGLISH), fontSize = 8.sp, color = TextGray, fontWeight = FontWeight.SemiBold, letterSpacing = .6.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(card.value, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = card.color, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(card.subtitle, fontSize = 9.sp, color = TextGray, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun HomeMaroonMenuCard(
    onTodayWork: () -> Unit,
    onClosing: () -> Unit,
    onNewChit: () -> Unit,
    onAddMember: () -> Unit,
    modifier: Modifier = Modifier
) {
    PremiumNotchedCard(
        modifier = modifier.fillMaxWidth(),
        cornerRadius = 26.dp,
        notchRadius = 14.dp,
        contentPadding = PaddingValues(start = 14.dp, top = 16.dp, end = 18.dp, bottom = 16.dp),
        gradient = LoginGradient
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Column(
                Modifier
                    .fillMaxWidth(.92f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MaroonMenuButton(stringResource(R.string.home_todays_work), stringResource(R.string.home_priority_daily_tasks), Icons.Default.Today, onTodayWork, Modifier.weight(1f))
                    MaroonMenuButton(stringResource(R.string.home_day_closing), stringResource(R.string.home_cash_bank_summary), Icons.Default.Assessment, onClosing, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MaroonMenuButton(stringResource(R.string.home_new_chit), stringResource(R.string.home_create_chit_group), Icons.Default.AddCard, onNewChit, Modifier.weight(1f))
                    MaroonMenuButton(stringResource(R.string.home_add_member), stringResource(R.string.home_join_customer_to_chit), Icons.Default.PersonAdd, onAddMember, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun MaroonMenuButton(title: String, subtitle: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.height(54.dp).clickable(onClick = onClick),
        shape = RoundedCornerShape(15.dp),
        color = Color.White,
        shadowElevation = 1.dp
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(32.dp).background(SurfaceElevated, RoundedCornerShape(11.dp)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = MaroonPrimary, modifier = Modifier.size(16.dp))
            }
            Column(Modifier.padding(start = 9.dp).weight(1f), verticalArrangement = Arrangement.Center) {
                Text(title, color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, color = TextGray, fontSize = 8.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
@Composable
private fun HomeCloudStatusCard(status: HomeCloudStatus, modifier: Modifier = Modifier) {
    val hasPending = status.pendingUploads > 0
    val accent = when {
        !status.firebaseReady -> AccentRed
        hasPending -> AccentGold
        else -> AccentGreen
    }
    val title = when {
        !status.firebaseReady -> "Cloud sync not ready"
        hasPending -> "${status.pendingUploads} update${if (status.pendingUploads == 1) "" else "s"} waiting"
        else -> "Cloud sync ready"
    }
    val subtitle = when {
        !status.firebaseReady -> "Firebase setup mudinja labour updates admin-ku reach aagum"
        hasPending -> "Internet/Firebase available aana auto retry pannum"
        status.lastSyncAt > 0L -> "Last sync ${relativeTime(status.lastSyncAt)}"
        else -> "Sync panna start pannala"
    }
    val icon = when {
        !status.firebaseReady -> Icons.Default.CloudOff
        hasPending -> Icons.Default.Sync
        else -> Icons.Default.CloudDone
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = Color.White,
        border = BorderStroke(1.dp, accent.copy(alpha = .20f)),
        shadowElevation = 1.dp
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(28.dp).background(accent.copy(alpha = .12f), RoundedCornerShape(9.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, null, tint = accent, modifier = Modifier.size(15.dp))
            }
            Column(Modifier.padding(start = 9.dp).weight(1f)) {
                Text(title, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Color.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, fontSize = 8.sp, color = TextGray, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Surface(shape = RoundedCornerShape(50), color = accent.copy(alpha = .10f)) {
                Text(
                    if (status.firebaseReady && !hasPending) "OK" else "Check",
                    color = accent,
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                )
            }
        }
    }
}

@Composable private fun SectionTitle(title: String, action: String? = null) = Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(title, fontWeight = FontWeight.SemiBold, fontSize = 13.sp); if (action != null) Text(action, color = Color(0xFF285A9B), fontSize = 10.sp) }

@Composable
private fun ActivityRow(title: String, time: String, amount: String) {
    val isOutgoing = title.contains("Pending") || title.contains("Delivery")
    val accent = if (isOutgoing) AccentRed else AccentGreen
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color.White,
        border = BorderStroke(1.dp, DividerGray),
        shadowElevation = 1.dp
    ) {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(Brush.verticalGradient(listOf(accent, accent.copy(alpha = .35f)))))
            Row(Modifier.weight(1f).padding(horizontal = 9.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(26.dp).background(Brush.linearGradient(listOf(accent.copy(alpha = .20f), accent.copy(alpha = .06f))), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(if (isOutgoing) Icons.Default.NorthEast else Icons.Default.SouthWest, null, tint = accent, modifier = Modifier.size(13.dp))
                }
                Column(Modifier.padding(start = 8.dp).weight(1f)) {
                    Text(title, fontSize = 10.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(time, fontSize = 8.sp, color = TextGray)
                }
                Surface(shape = RoundedCornerShape(8.dp), color = accent.copy(alpha = .10f)) {
                    Text(amount, color = accent, fontWeight = FontWeight.Bold, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp))
                }
            }
        }
    }
}

@Composable
private fun LedgerScreenApproved(
    selectedCustomer: DueCustomer?,
    onBack: () -> Unit,
    onCollect: (String, String) -> Unit
) {
    // The customer-search/consolidated-vs-detail ledger UI this screen originally had was never
    // wired to real data (it rendered a hardcoded "Thilban / C-39" sample customer) and was
    // superseded by ResizableLedgerSheet, the real Room-backed spreadsheet ledger, before ever
    // shipping - removed rather than left as unreachable dead code behind an early return.
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    var showRotatePrompt by remember { mutableStateOf(true) }
    ResizableLedgerSheet(onBack)
    if (!isLandscape && showRotatePrompt) {
        AlertDialog(
            onDismissRequest = { showRotatePrompt = false },
            icon = { Icon(Icons.Default.ScreenRotation, null, tint = MaroonPrimary, modifier = Modifier.size(38.dp)) },
            title = { Text("Rotate for wide Ledger") },
            text = { Text("100 customer rows are loaded. Rotate to landscape to see more columns at once.") },
            confirmButton = { Button(onClick = { showRotatePrompt = false }, colors = ButtonDefaults.buttonColors(containerColor = MaroonPrimary)) { Text("View table") } }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CollectionScreen(initialCustomer: String, initialChit: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val agentPrefs = remember { com.jothivel.chits.utils.AppPreferences(context) }
    val isAgentMode = remember { agentPrefs.isAgent() }
    val lookup by produceState(initialValue = CollectionLookupData(), context) {
        value = withContext(Dispatchers.IO) {
            val db = AppDatabase.getDatabase(context)
            CollectionLookupData(db.memberDao().getAllMembersSync().filter { it.isActive }, db.groupDao().getAllGroupsSync().filter { it.status.isNullOrBlank() || it.status == "ACTIVE" }, db.membershipDao().getAllActiveSync())
        }
    }
    val members = lookup.members
    // A field agent must only see chits assigned to them — not every active chit in the
    // system — so they can't collect against (or even see) a chit they weren't given.
    val groups = remember(lookup, isAgentMode) {
        if (isAgentMode) {
            val assigned = agentPrefs.getAgentAssignedGroups().toHashSet()
            lookup.groups.filter { it.id in assigned }
        } else lookup.groups
    }
    var selectedMember by remember { mutableStateOf<MemberEntity?>(null) }
    var selectedGroup by remember { mutableStateOf<ChitGroupEntity?>(null) }
    var dueAmountPaise by remember { mutableLongStateOf(0L) }
    var amount by remember { mutableStateOf("") }
    var businessDate by remember { mutableStateOf(CollectionService.todayKey()) }
    var mode by remember { mutableStateOf("Cash") }
    var reference by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("Monthly collection received.") }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmExcess by remember { mutableStateOf(false) }
    var savedReceipt by remember { mutableStateOf<SavedCollection?>(null) }
    // Chit is picked first; the customer list is then scoped to members of that chit only —
    // picking a chit with 100 members and no filter made "Customer" useless to search.
    val eligibleMembers = remember(selectedGroup, lookup) {
        val group = selectedGroup ?: return@remember emptyList()
        val linkedIds = lookup.memberships.filter { it.groupId == group.id }.mapTo(hashSetOf()) { it.memberId }
        if (linkedIds.isEmpty()) members.filter { it.selectedChitId == group.id }.mapTo(linkedIds) { it.id }
        members.filter { it.id in linkedIds }
    }
    LaunchedEffect(groups, initialChit) {
        if (selectedGroup == null) selectedGroup = groups.firstOrNull { it.registerNo.equals(initialChit, true) || it.id.equals(initialChit, true) }
    }
    LaunchedEffect(selectedGroup, eligibleMembers, initialCustomer) {
        if (selectedMember?.id !in eligibleMembers.map { it.id }) selectedMember = eligibleMembers.firstOrNull { it.name.equals(initialCustomer, true) || it.id.equals(initialCustomer, true) }
    }
    LaunchedEffect(selectedMember?.id, selectedGroup?.id) {
        val member = selectedMember
        val group = selectedGroup
        dueAmountPaise = if (member != null && group != null) withContext(Dispatchers.IO) { CollectionService.calculateCollectableDuePaise(AppDatabase.getDatabase(context), member.id, group.id) } else 0L
        amount = if (dueAmountPaise > 0) ((dueAmountPaise + 99) / 100).toString() else ""
        error = null
    }
    val customer = selectedMember?.name.orEmpty()
    val enteredRupees = amount.toLongOrNull() ?: 0L
    val enteredPaise = enteredRupees.coerceAtMost(Long.MAX_VALUE / 100) * 100
    val stateAdvance = stringResource(R.string.collection_state_advance)
    val statePartial = stringResource(R.string.collection_state_partial)
    val stateFull = stringResource(R.string.collection_state_full)
    val stateExcess = stringResource(R.string.collection_state_excess, money((enteredPaise - dueAmountPaise).coerceAtLeast(0) / 100))
    val paymentState = when {
        enteredPaise <= 0 -> null
        dueAmountPaise <= 0 -> stateAdvance
        enteredPaise < dueAmountPaise -> statePartial
        enteredPaise == dueAmountPaise -> stateFull
        else -> stateExcess
    }
    val errSelectChit = stringResource(R.string.collection_error_select_chit)
    val errSelectCustomer = stringResource(R.string.collection_error_select_customer)
    val errInvalidAmount = stringResource(R.string.collection_error_invalid_amount)
    val errAmountTooLarge = stringResource(R.string.collection_error_amount_too_large)
    val errReferenceRequired = stringResource(R.string.collection_error_reference_required, mode)
    val errSaveFailed = stringResource(R.string.collection_error_save_failed)
    fun saveCollection() {
        val member = selectedMember
        val group = selectedGroup
        val requestId = UUID.randomUUID().toString()
        error = when {
            group == null -> errSelectChit
            member == null -> errSelectCustomer
            enteredRupees <= 0 -> errInvalidAmount
            enteredRupees > Long.MAX_VALUE / 100 -> errAmountTooLarge
            mode != "Cash" && reference.isBlank() -> errReferenceRequired
            else -> null
        }
        if (error != null || member == null || group == null || saving) return
        saving = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    CollectionService.record(
                        AppDatabase.getDatabase(context),
                        requestId,
                        member.id,
                        member.name,
                        group.id,
                        enteredPaise,
                        mode,
                        reference,
                        notes,
                        businessDate,
                        collectedBy = if (isAgentMode) agentPrefs.getAgentName() else null,
                        collectedByAgentId = if (isAgentMode) agentPrefs.getAgentId() else null
                    )
                }
            }
            saving = false
            result.onSuccess { receipt ->
                savedReceipt = receipt
                dueAmountPaise = withContext(Dispatchers.IO) { CollectionService.calculateCollectableDuePaise(AppDatabase.getDatabase(context), member.id, group.id) }
                amount = if (dueAmountPaise > 0) ((dueAmountPaise + 99) / 100).toString() else ""
                // Back up every collection to Firestore - not just agent-collected ones - so
                // "Restore Data from Cloud" can recover full payment history after an
                // uninstall/reinstall, not just chit/member structure. Admin-recorded
                // collections are already in this device's own Room (written above), so they're
                // tagged syncedToAdmin=true - FirebaseSyncService must not re-apply them; agent
                // collections leave it false so the admin's listener mirrors them in as before.
                withContext(Dispatchers.IO) {
                    com.jothivel.chits.data.firebase.AgentCollectionSync.push(
                        context,
                        com.jothivel.chits.data.firebase.AgentCollectionDoc(
                            requestId = requestId,
                            agentId = if (isAgentMode) agentPrefs.getAgentId() else "",
                            agentName = if (isAgentMode) agentPrefs.getAgentName() else "Admin",
                            memberId = member.id,
                            memberName = member.name,
                            groupId = group.id,
                            chitNo = group.registerNo ?: group.id,
                            amountPaise = receipt.amountPaise,
                            mode = receipt.mode,
                            referenceNo = receipt.referenceNo,
                            receiptNo = receipt.receiptNo,
                            notes = notes,
                            businessDate = receipt.businessDate,
                            status = "PAID",
                            syncedToAdmin = !isAgentMode
                        )
                    )
                }
            }.onFailure { error = it.message ?: errSaveFailed }
        }
    }
    val chitFallback = stringResource(R.string.collection_chit_fallback)
    val noBranch = stringResource(R.string.collection_no_branch)
    val unnamed = stringResource(R.string.collection_unnamed)
    val noMobile = stringResource(R.string.collection_no_mobile)
    val noArea = stringResource(R.string.collection_no_area)
    val cashLabel = stringResource(R.string.collection_mode_cash)
    val upiLabel = stringResource(R.string.collection_mode_upi)
    Column(Modifier.fillMaxSize()) {
        BrandTopBar(stringResource(R.string.collection_title), onBack)
        LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            item { SearchableDropdownField(stringResource(R.string.collection_chit_label), selectedGroup?.let { "${it.registerNo} • ${it.name}" }.orEmpty(), groups, { "${it.registerNo.orEmpty()} ${it.name.orEmpty()} ${it.branch.orEmpty()}" }, { "${it.registerNo ?: it.id} • ${it.name ?: chitFallback}" }, { "${money(it.chitValue.toLong() / 100)} • ${it.durationMonths} months • ${it.branch ?: noBranch}" }, Icons.Default.Description) { group -> selectedGroup = group; selectedMember = null } }
            item { SearchableDropdownField(stringResource(R.string.collection_customer_label), selectedMember?.let { "${it.name} • ${it.id}" }.orEmpty(), eligibleMembers, { "${it.name} ${it.id} ${it.phone.orEmpty()} ${it.city.orEmpty()}" }, { it.name ?: unnamed }, { "${it.id} • ${it.phone ?: noMobile} • ${it.city ?: noArea}" }, Icons.Default.Person) { member -> selectedMember = member } }
            if (selectedGroup != null && eligibleMembers.isEmpty()) item { Text(stringResource(R.string.collection_no_customers_in_chit), color = AccentRed, fontSize = 10.sp) }
            if (isAgentMode && groups.isEmpty()) item { Text(stringResource(R.string.collection_no_chits_assigned), color = AccentRed, fontSize = 10.sp) }
            item { CompactDateField(stringResource(R.string.collection_date_label), businessDate, { businessDate=it }, Modifier.fillMaxWidth(), "yyyy-MM-dd") }
            item { Surface(shape = RoundedCornerShape(12.dp), color = Color(0xFFFFF7E2), border = BorderStroke(1.dp, AccentGold.copy(alpha = .45f))) { Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) { Text(stringResource(R.string.collection_current_due), fontWeight = FontWeight.Bold); Text(money(dueAmountPaise / 100), fontWeight = FontWeight.ExtraBold, fontSize = 18.sp) } } }
            item { PremiumInputField(amount, { amount = it.filter(Char::isDigit).take(10); error = null }, stringResource(R.string.collection_amount_received), Modifier.fillMaxWidth(), leadingIcon = Icons.Default.CurrencyRupee, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)) }
            paymentState?.let { state -> item { Text(state, color = if (state == stateExcess) AccentRed else if (state == stateFull) AccentGreen else AccentGold, fontSize = 10.sp, fontWeight = FontWeight.SemiBold) } }
            item { Text(stringResource(R.string.collection_payment_mode), fontSize = 12.sp); Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("Cash", "UPI").forEach { item -> FilterChip(mode == item, { mode = item }, { Text(if (item == "Cash") cashLabel else upiLabel) }, modifier = Modifier.weight(1f)) } } }
            if (mode != "Cash") item { FormField(stringResource(R.string.collection_reference_label), reference, { reference = it; error = null }, Icons.Default.Tag) }
            item { PremiumInputField(notes, { notes = it.take(150) }, stringResource(R.string.collection_notes_label), Modifier.fillMaxWidth().height(86.dp), leadingIcon = Icons.Default.Notes, singleLine = false, minLines = 3) }
            error?.let { message -> item { Text(message, color = AccentRed, fontSize = 10.sp) } }
            item { Button(onClick = { if (dueAmountPaise > 0 && enteredPaise > dueAmountPaise) confirmExcess = true else saveCollection() }, enabled = !saving && selectedMember != null && selectedGroup != null && enteredRupees > 0, modifier = Modifier.fillMaxWidth().height(48.dp), colors = ButtonDefaults.buttonColors(containerColor = MaroonPrimary), shape = RoundedCornerShape(10.dp)) { if (saving) CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp) else { Icon(Icons.Default.Send, null, modifier = Modifier.size(17.dp)); Spacer(Modifier.width(7.dp)); Text(stringResource(R.string.collection_save_button), fontWeight = FontWeight.SemiBold) } } }
        }
    }
    if (confirmExcess) AlertDialog(onDismissRequest = { confirmExcess = false }, title = { Text(stringResource(R.string.collection_excess_title)) }, text = { Text(stringResource(R.string.collection_excess_message, money(enteredPaise / 100), money(dueAmountPaise / 100))) }, confirmButton = { Button(onClick = { confirmExcess = false; saveCollection() }, colors = ButtonDefaults.buttonColors(containerColor = MaroonPrimary)) { Text(stringResource(R.string.collection_save_as_advance)) } }, dismissButton = { TextButton(onClick = { confirmExcess = false }) { Text(stringResource(R.string.collection_check_amount)) } })
    savedReceipt?.let { receipt -> ReceiptDialog(customer, selectedGroup?.registerNo.orEmpty(), receipt, { savedReceipt = null }, { savedReceipt = null; onBack() }) }
}

@Composable
private fun FinancialEntryScreen(type: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var refreshKey by remember { mutableIntStateOf(0) }
    val lookup by produceState(initialValue = CollectionLookupData(), context, refreshKey) {
        value = withContext(Dispatchers.IO) { val db=AppDatabase.getDatabase(context); CollectionLookupData(db.memberDao().getAllMembersSync().filter { it.isActive }, db.groupDao().getAllGroupsSync().filter { it.status.isNullOrBlank() || it.status=="ACTIVE" }, db.membershipDao().getAllActiveSync()) }
    }
    val entries by produceState(initialValue = emptyList<FinancialTransactionEntity>(), context, refreshKey) {
        value = withContext(Dispatchers.IO) { AppDatabase.getDatabase(context).financialTransactionDao().getAllSync().filter { it.type==type }.take(30) }
    }
    var member by remember { mutableStateOf<MemberEntity?>(null) }
    var group by remember { mutableStateOf<ChitGroupEntity?>(null) }
    var amount by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf("Cash") }
    var reference by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var reverseEntry by remember { mutableStateOf<FinancialTransactionEntity?>(null) }
    var reverseReason by remember { mutableStateOf("") }
    val groups = remember(member, lookup) { val ids=lookup.memberships.filter { it.memberId==member?.id }.mapTo(hashSetOf()) { it.groupId }; lookup.groups.filter { it.id in ids } }
    val isDelivery = type == "DELIVERY"
    val title = stringResource(if (type=="SETTLEMENT") R.string.fin_settlement_title else R.string.fin_delivery_title)
    val errCustomer = stringResource(R.string.fin_err_customer)
    val errChit = stringResource(R.string.fin_err_chit)
    val errAmount = stringResource(R.string.fin_err_amount)
    val errReference = stringResource(R.string.fin_err_reference)
    val savedTemplate = stringResource(R.string.fin_saved_toast)
    val modeLabels = mapOf("Cash" to stringResource(R.string.fin_mode_cash), "UPI" to stringResource(R.string.fin_mode_upi), "Bank" to stringResource(R.string.fin_mode_bank))

    // A Delivery is the prize money for an auction win: show what was won, what has gone out, and
    // what is left, so the amount typed is checked against something real (the service enforces it too).
    val selMember = member
    val selGroup = group
    val prize by produceState<Triple<Long, Long, Boolean>?>(null, selMember?.id, selGroup?.id, refreshKey, type) {
        value = if (isDelivery && selMember != null && selGroup != null) withContext(Dispatchers.IO) {
            val db = AppDatabase.getDatabase(context)
            Triple(
                com.jothivel.chits.data.local.ChitAdminService.prizePaise(db, selMember.id, selGroup.id),
                db.financialTransactionDao().getPostedTotalForMemberGroupTypeSync(selMember.id, selGroup.id, "DELIVERY"),
                db.installmentDao().getWonByMemberSync(selGroup.id, selMember.id).isNotEmpty()
            )
        } else null
    }

    fun save() {
        val paise=(amount.toLongOrNull() ?: 0L)*100
        error=when { member==null -> errCustomer; group==null -> errChit; paise<=0 -> errAmount; mode!="Cash" && reference.isBlank() -> errReference; else -> null }
        if(error!=null || saving) return
        saving=true
        scope.launch { val result=withContext(Dispatchers.IO) { runCatching { FinancialService.record(AppDatabase.getDatabase(context), UUID.randomUUID().toString(), type, member!!.id, group!!.id, paise, mode, reference, notes) } }; saving=false; result.onSuccess { amount=""; reference=""; notes=""; error=null; refreshKey++; android.widget.Toast.makeText(context, String.format(savedTemplate, title), android.widget.Toast.LENGTH_SHORT).show() }.onFailure { error=it.message } }
    }
    Column(Modifier.fillMaxSize().background(MaroonBackground)) {
        BrandTopBar(title, back=onBack)
        LazyColumn(contentPadding=PaddingValues(12.dp), verticalArrangement=Arrangement.spacedBy(8.dp)) {
            item { SearchableDropdownField(stringResource(R.string.fin_customer), member?.let { "${it.name} • ${it.id}" }.orEmpty(), lookup.members, { "${it.name} ${it.id} ${it.phone}" }, { it.name ?: it.id }, { "${it.id} • ${it.phone.orEmpty()}" }, Icons.Default.Person) { member=it; group=null } }
            item { SearchableDropdownField(stringResource(R.string.fin_chit), group?.let { "${it.registerNo} • ${it.name}" }.orEmpty(), groups, { "${it.registerNo} ${it.name}" }, { it.registerNo ?: it.id }, { it.name ?: "Chit" }, Icons.Default.AccountBalance) { group=it } }
            if (isDelivery) prize?.let { (won, delivered, isWinner) ->
                item {
                    if (!isWinner) Text(stringResource(R.string.fin_delivery_no_winner), color = AccentRed, fontSize = 10.sp)
                    else Text(stringResource(R.string.fin_delivery_info, money(won / 100), money(delivered / 100), money(maxOf(won - delivered, 0L) / 100)), color = AccentGreen, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            item { com.jothivel.chits.ui.components.AmountKeypadField(stringResource(R.string.fin_amount), amount, { amount=it; error=null }, Modifier.fillMaxWidth()) }
            item { Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) { listOf("Cash","UPI","Bank").forEach { value -> FilterChip(mode==value, { mode=value }, { Text(modeLabels.getValue(value)) }, Modifier.weight(1f)) } } }
            if(mode!="Cash") item { PremiumInputField(reference, { reference=it }, stringResource(R.string.fin_reference), Modifier.fillMaxWidth(), leadingIcon=Icons.Default.Tag) }
            item { PremiumInputField(notes, { notes=it.take(150) }, stringResource(R.string.fin_notes), Modifier.fillMaxWidth().height(78.dp), leadingIcon=Icons.Default.Notes, singleLine=false, minLines=2) }
            error?.let { item { Text(it, color=AccentRed, fontSize=10.sp) } }
            item { Button(::save, enabled=!saving, modifier=Modifier.fillMaxWidth().height(46.dp), colors=ButtonDefaults.buttonColors(containerColor=MaroonPrimary)) { Text(if(saving) stringResource(R.string.fin_saving) else stringResource(if (type=="SETTLEMENT") R.string.fin_save_settlement else R.string.fin_save_delivery)) } }
            item { Text(stringResource(R.string.fin_recent), fontWeight=FontWeight.Bold, fontSize=13.sp, modifier=Modifier.padding(top=6.dp)) }
            if(entries.isEmpty()) item { EmptyCreatedList(stringResource(if (type=="SETTLEMENT") R.string.fin_none_settlement else R.string.fin_none_delivery)) }
            items(entries, key={it.id}) { entry -> Surface(shape=RoundedCornerShape(11.dp), color=Color.White, border=BorderStroke(1.dp, DividerGray)) { Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment=Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("${entry.memberId} • ${entry.groupId}", fontSize=11.sp, fontWeight=FontWeight.SemiBold); Text(money(entry.amountPaise/100), fontSize=13.sp, color=if(entry.status=="POSTED") AccentGreen else TextGray, fontWeight=FontWeight.Bold); Text("${modeLabels[entry.mode] ?: entry.mode} • ${stringResource(if(entry.status=="POSTED") R.string.fin_posted else R.string.fin_reversed)}", fontSize=8.sp, color=TextGray) }; if(entry.status=="POSTED") TextButton({ reverseEntry=entry }) { Text(stringResource(R.string.fin_reverse), color=AccentRed, fontSize=9.sp) } } } }
        }
    }
    reverseEntry?.let { entry ->
        com.jothivel.chits.ui.components.ConfirmBottomSheet(
            show = true,
            onDismiss = { reverseEntry = null; reverseReason = "" },
            title = stringResource(R.string.fin_reverse_title),
            confirmLabel = stringResource(R.string.fin_reverse),
            cancelLabel = stringResource(R.string.common_cancel),
            isDestructive = true,
            onConfirm = {
                scope.launch {
                    val result = withContext(Dispatchers.IO) { runCatching { FinancialService.reverse(AppDatabase.getDatabase(context), entry.id, reverseReason) } }
                    result.onSuccess { reverseEntry = null; reverseReason = ""; refreshKey++ }.onFailure { error = it.message }
                }
            },
            content = { PremiumInputField(reverseReason, { reverseReason = it.take(100) }, stringResource(R.string.fin_reason), Modifier.fillMaxWidth(), leadingIcon = Icons.Default.Notes) }
        )
    }
}

@Composable
private fun <T> SearchableDropdownField(
    label: String,
    selectedText: String,
    items: List<T>,
    searchText: (T) -> String,
    itemTitle: (T) -> String,
    itemSubtitle: (T) -> String,
    leadingIcon: ImageVector,
    onSelected: (T) -> Unit
) {
    val selectedOption = remember(selectedText, items) {
        items.find { selectedText.isNotBlank() && selectedText.contains(itemTitle(it).substringBefore(" • "), true) }
    }
    BottomSheetPickerField(
        label = label,
        options = items,
        selectedOption = selectedOption,
        optionKey = { "${itemTitle(it)}|${itemSubtitle(it)}" },
        optionLabel = itemTitle,
        optionSubLabel = itemSubtitle,
        searchableText = searchText,
        onOptionSelected = onSelected,
        // Always show the search box here (Chit/Customer pickers), not just once the list
        // passes 6 items - these lists only grow over time, so search should never suddenly
        // appear/disappear as data is added.
        showSearch = true,
        trigger = { _, onClick ->
            Box(Modifier.fillMaxWidth()) {
                PremiumInputField(
                    value = selectedText,
                    onValueChange = {},
                    label = label,
                    readOnly = true,
                    leadingIcon = leadingIcon,
                    trailingContent = { Icon(Icons.Default.ArrowDropDown, null) },
                    modifier = Modifier.fillMaxWidth()
                )
                // PremiumInputField's OutlinedTextField is readOnly but still enabled, so it
                // steals the tap for its own cursor/focus before Modifier.clickable below it
                // ever sees it - the sheet silently never opened. A fully transparent box drawn
                // on top intercepts every touch first, so the click always reaches onClick.
                Box(
                    Modifier
                        .matchParentSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onClick
                        )
                )
            }
        }
    )
}

@Composable private fun FormField(label: String, value: String, onChange: (String) -> Unit, icon: ImageVector) = PremiumInputField(value, onChange, label, Modifier.fillMaxWidth(), leadingIcon = icon, trailingContent = { Icon(Icons.Default.ArrowDropDown, null) })

@Composable
private fun ReceiptDialog(customer: String, chitNo: String, receipt: SavedCollection, dismiss: () -> Unit, done: () -> Unit) {
    val context = LocalContext.current
    fun share() {
        ReceiptPdfHelper.share(context,customer,chitNo,receipt).onFailure { android.widget.Toast.makeText(context,"Invoice could not be created: ${it.message}",android.widget.Toast.LENGTH_LONG).show() }
    }
    AlertDialog(onDismissRequest = dismiss, icon = { Icon(Icons.Default.CheckCircle, null, tint = AccentGreen, modifier = Modifier.size(48.dp)) }, title = { Text("Collection saved") }, text = { Column(horizontalAlignment = Alignment.CenterHorizontally) { Text(customer, fontWeight = FontWeight.Bold); Text(money(receipt.amountPaise / 100), fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, color = MaroonPrimary); Text("${receipt.mode} • ${receipt.receiptNo}", color = TextGray, fontSize = 11.sp) } }, confirmButton = { Button(onClick = done, colors = ButtonDefaults.buttonColors(containerColor = MaroonPrimary)) { Text("Done") } }, dismissButton = { TextButton(onClick = ::share) { Text("Send PDF Invoice") } })
}

@Composable
private fun CompactNewChitScreen(onBack: () -> Unit, onAddMember: (String) -> Unit = {}, onOpenGroup: (String) -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var refreshKey by remember { mutableIntStateOf(0) }
    var groupSearchQuery by remember { mutableStateOf("") }
    val groups by produceState(initialValue = emptyList<ChitGroupEntity>(), context, refreshKey) {
        value = withContext(Dispatchers.IO) { AppDatabase.getDatabase(context).groupDao().getAllGroupsSync().asReversed() }
    }
    val filteredGroups = remember(groups, groupSearchQuery) {
        if (groupSearchQuery.isBlank()) groups
        else groups.filter { g -> listOfNotNull(g.registerNo, g.name, g.branch).any { it.contains(groupSearchQuery.trim(), ignoreCase = true) } }
    }
    val progress by produceState(initialValue = emptyMap<String, GroupProgress>(), groups, refreshKey) {
        value = withContext(Dispatchers.IO) {
            val db = AppDatabase.getDatabase(context)
            val payments = db.paymentDao().getAllPaymentsSync()
            groups.associate { g -> g.id to computeGroupProgress(db, g, payments.filter { it.groupId == g.id }) }
        }
    }
    var chitNo by remember { mutableStateOf("") }
    var chitName by remember { mutableStateOf("") }
    var selectedTemplate by remember { mutableStateOf<ChitTemplate?>(null) }
    var valueText by remember { mutableStateOf("") }
    var monthsText by remember { mutableStateOf("20") }
    var membersText by remember { mutableStateOf("20") }
    var branch by remember { mutableStateOf("") }
    var startDate by remember { mutableStateOf(SimpleDateFormat("dd-MMM-yyyy", Locale.ENGLISH).format(Date())) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val chitValue = valueText.replace(",", "").toLongOrNull() ?: 0L
    val months = monthsText.toIntOrNull() ?: 0
    val members = membersText.toIntOrNull() ?: 0
    val errNoRequired = stringResource(R.string.chits_error_no_required)
    val errNameRequired = stringResource(R.string.chits_error_name_required)
    val errInvalidValue = stringResource(R.string.chits_error_invalid_value)
    val errValueTooLarge = stringResource(R.string.chits_error_value_too_large)
    val errMonthsRange = stringResource(R.string.chits_error_months_range)
    val errMembersRange = stringResource(R.string.chits_error_members_range)
    val errBranchRequired = stringResource(R.string.chits_error_branch_required)
    val errStartDateFormat = stringResource(R.string.chits_error_start_date_format)
    val errNoExists = stringResource(R.string.chits_error_no_exists)
    val errSaveFailed = stringResource(R.string.chits_error_save_failed)
    val createdToast = stringResource(R.string.chits_created_toast)

    fun save() {
        val validStartDate = runCatching { SimpleDateFormat("dd-MMM-yyyy", Locale.ENGLISH).apply { isLenient = false }.parse(startDate.trim()) }.getOrNull()
        error = when {
            chitNo.isBlank() -> errNoRequired
            chitName.isBlank() -> errNameRequired
            chitValue <= 0 -> errInvalidValue
            chitValue > Int.MAX_VALUE / 100L -> errValueTooLarge
            months !in 1..100 -> errMonthsRange
            members !in 1..100 -> errMembersRange
            branch.isBlank() -> errBranchRequired
            validStartDate == null -> errStartDateFormat
            else -> null
        }
        if (error != null) return
        saving = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val db = AppDatabase.getDatabase(context)
                    if (db.groupDao().getAllGroupsSync().any { it.registerNo.equals(chitNo.trim(), true) }) throw IllegalArgumentException(errNoExists)
                    val id = "G-${UUID.randomUUID()}"
                    val valuePaise = (chitValue * 100).toInt()
                    val group = ChitGroupEntity().apply {
                        this.id = id; name = chitName.trim(); registerNo = chitNo.trim(); this.chitValue = valuePaise
                        durationMonths = months; subscriberCount = members; this.branch = branch.trim(); this.startDate = startDate.trim(); status = "ACTIVE"
                    }
                    val fixedSchedule = ChitTemplate.forChitValue(chitValue.toInt())?.fixedSchedule?.takeIf { it.size == months }
                    // Flat plans roll the paise remainder into the last installment, so the schedule
                    // always sums to exactly the chit value.
                    val installments = com.jothivel.chits.data.local.ChitAdminService.buildInstallments(id, valuePaise, months, fixedSchedule)
                    db.runInTransaction {
                        db.groupDao().insertGroup(group)
                        db.installmentDao().insertAll(installments)
                        db.activityLogDao().insertLog(ActivityLogEntity(actionType = "GROUP_CREATED", title = "New Chit Created", description = "${group.registerNo} - ${group.name}"))
                    }
                }
            }
            saving = false
            result.onSuccess {
                android.widget.Toast.makeText(context, createdToast, android.widget.Toast.LENGTH_SHORT).show()
                chitNo = ""; chitName = ""; selectedTemplate = null; valueText = ""; branch = ""; monthsText = "20"; membersText = "20"; error = null
                refreshKey++
            }
                .onFailure { error = it.message ?: errSaveFailed }
        }
    }

    Column(Modifier.fillMaxSize().background(MaroonBackground)) {
        BrandTopBar(stringResource(R.string.chits_new_chit_title), back = onBack)
        LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CompactFormField(stringResource(R.string.chits_no_field), chitNo, { chitNo = it }, Modifier.weight(1f))
                CompactFormField(stringResource(R.string.chits_name_field), chitName, { chitName = it }, Modifier.weight(1.35f))
            } }
            item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BottomSheetPickerField(
                    label = stringResource(R.string.chits_value_field),
                    options = ChitTemplate.entries.toList(),
                    selectedOption = selectedTemplate,
                    optionKey = { it.name },
                    optionLabel = { it.title },
                    onOptionSelected = { template ->
                        selectedTemplate = template
                        if (template == ChitTemplate.CUSTOM) {
                            valueText = ""
                        } else {
                            valueText = template.chitValue.toString()
                            monthsText = template.durationMonths.toString()
                            membersText = template.subscriberCount.toString()
                        }
                    },
                    modifier = Modifier.weight(1f),
                    trigger = { displayText, onClick ->
                        Box(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
                            // Material3's OutlinedTextField only shows `placeholder` while
                            // focused, and a disabled field (used here so the wrapping Box's
                            // clickable reliably gets the tap) can never focus - so "Select
                            // plan" has to be the field's actual `value` or it never renders.
                            PremiumInputField(
                                value = displayText.ifEmpty { stringResource(R.string.chits_value_placeholder) },
                                onValueChange = {},
                                label = stringResource(R.string.chits_value_field),
                                modifier = Modifier.fillMaxWidth().height(54.dp),
                                enabled = false,
                                colors = premiumInputFieldTriggerColors(),
                                trailingContent = { Icon(Icons.Default.ArrowDropDown, null) }
                            )
                        }
                    }
                )
                CompactFormField(stringResource(R.string.chits_months_field), monthsText, { monthsText = it.filter(Char::isDigit) }, Modifier.weight(1f), KeyboardType.Number)
                CompactFormField(stringResource(R.string.chits_members_field), membersText, { membersText = it.filter(Char::isDigit) }, Modifier.weight(1f), KeyboardType.Number)
            } }
            if (selectedTemplate == ChitTemplate.CUSTOM) item {
                CompactFormField(stringResource(R.string.chits_custom_value_field), valueText, { valueText = it.filter { c -> c.isDigit() || c == ',' } }, Modifier.fillMaxWidth(), KeyboardType.Number)
            }
            item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CompactFormField(stringResource(R.string.chits_branch_field), branch, { branch = it }, Modifier.weight(1f))
                CompactDateField(stringResource(R.string.chits_start_date_field), startDate, { startDate = it }, Modifier.weight(1f))
            } }
            if (chitValue > 0 && months > 0) item {
                val hasFixedSchedule = ChitTemplate.forChitValue(chitValue.toInt())?.fixedSchedule?.size == months
                Text(
                    if (hasFixedSchedule) stringResource(R.string.chits_installment_preview_fixed)
                    else stringResource(R.string.chits_installment_preview, money(chitValue / months), months),
                    color = TextGray, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 2.dp)
                )
            }
            error?.let { message -> item { Text(message, color = AccentRed, fontSize = 10.sp) } }
            item { Button(onClick = ::save, enabled = !saving, modifier = Modifier.fillMaxWidth().height(44.dp), colors = ButtonDefaults.buttonColors(containerColor = MaroonPrimary)) { if (saving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White) else Text(stringResource(R.string.chits_create_button)) } }
            item { AshAnimatedSearchBar(groupSearchQuery, { groupSearchQuery = it }, stringResource(R.string.chits_search_placeholder), Modifier.fillMaxWidth()) }
            item {
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.chits_groups_title), fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text(stringResource(R.string.chits_groups_count, filteredGroups.size), fontSize = 10.sp, color = TextGray)
                }
            }
            if (filteredGroups.isEmpty()) item { EmptyCreatedList(stringResource(R.string.chits_none_created)) }
            items(filteredGroups, key = { it.id }) { group ->
                CompactGroupCard(
                    group, progress[group.id] ?: GroupProgress(),
                    onOpen = { onOpenGroup(group.id) },
                    onAddMember = onAddMember
                )
            }
        }
    }
}

/** Suggests a "C-12345" style code. If it is already used by a different customer, saving says so. */
private fun newCustomerCode(): String = "C-${(10000..99999).random()}"

@Composable
private fun CompactAddMemberScreen(onBack: () -> Unit, initialGroupId: String = "") {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var refreshKey by remember { mutableIntStateOf(0) }
    val groups by produceState(initialValue = emptyList<ChitGroupEntity>(), context, refreshKey) { value = withContext(Dispatchers.IO) { AppDatabase.getDatabase(context).groupDao().getAllGroupsSync().filter { it.status.isNullOrBlank() || it.status == "ACTIVE" } } }
    val members by produceState(initialValue = emptyList<MemberEntity>(), context, refreshKey) { value = withContext(Dispatchers.IO) { AppDatabase.getDatabase(context).memberDao().getAllMembersSync().asReversed() } }
    val groupsById = remember(groups) { groups.associateBy { it.id } }
    var memberSearchQuery by remember { mutableStateOf("") }
    val filteredMembers = remember(members, groupsById, memberSearchQuery) {
        if (memberSearchQuery.isBlank()) members
        else members.filter { m ->
            val group = m.selectedChitId?.let(groupsById::get)
            listOfNotNull(m.name, m.phone, m.city, group?.registerNo, group?.name).any { it.contains(memberSearchQuery.trim(), ignoreCase = true) }
        }
    }
    var selectedGroup by remember { mutableStateOf<ChitGroupEntity?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var customerCode by remember { mutableStateOf(newCustomerCode()) }
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var ticket by remember { mutableStateOf("") }
    var installment by remember { mutableStateOf("") }
    var joiningDate by remember { mutableStateOf(SimpleDateFormat("dd-MMM-yyyy", Locale.ENGLISH).format(Date())) }
    var dueDate by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var city by remember { mutableStateOf("") }
    var ticketNo by remember { mutableStateOf("") }
    var aadhaarLast4 by remember { mutableStateOf("") }
    var nomineeName by remember { mutableStateOf("") }
    var nomineePhone by remember { mutableStateOf("") }
    var nomineeRelation by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun selectGroup(group: ChitGroupEntity) {
        selectedGroup = group
        val fixedSchedule = ChitTemplate.forChitValue((group.chitValue / 100))?.fixedSchedule?.takeIf { it.size == group.durationMonths }
        // Fixed-schedule plans (50K/1L/2L/3L/10L) pay a different amount every month, so there's
        // no single flat number to store per member - the group's own installment schedule
        // (baseAmount - kasaruAmount per month) is what CollectionService etc. read from instead.
        installment = when {
            fixedSchedule != null -> ""
            group.durationMonths > 0 -> ((group.chitValue.toLong() / 100) / group.durationMonths).toString()
            else -> ""
        }
        dueDate = runCatching {
            val format = SimpleDateFormat("dd-MMM-yyyy", Locale.ENGLISH)
            format.format(Calendar.getInstance().apply { time = format.parse(joiningDate)!!; add(Calendar.MONTH, group.durationMonths) }.time)
        }.getOrDefault("")
        menuOpen = false
    }

    val hasFixedSchedule = remember(selectedGroup) {
        selectedGroup?.let { g -> ChitTemplate.forChitValue(g.chitValue / 100)?.fixedSchedule?.size == g.durationMonths } ?: false
    }

    // Tapping the "+" on a specific chit card (New Chit screen's group list) should land
    // here with that exact chit already selected, not force picking it again from scratch.
    LaunchedEffect(groups, initialGroupId) {
        if (selectedGroup == null && initialGroupId.isNotBlank()) {
            groups.find { it.id == initialGroupId }?.let { selectGroup(it) }
        }
    }

    val errSelectChit = stringResource(R.string.collection_error_select_chit)
    val errCodeRequired = stringResource(R.string.addmember_error_code_required)
    val errNameRequired = stringResource(R.string.addmember_error_name_required)
    val errInvalidMobile = stringResource(R.string.addmember_error_invalid_mobile)
    val errInvalidInstallment = stringResource(R.string.addmember_error_invalid_installment)
    val errDateFormat = stringResource(R.string.addmember_error_date_format)
    val errDueBeforeJoining = stringResource(R.string.addmember_error_due_before_joining)
    val errSaveFailed = stringResource(R.string.addmember_error_save_failed)
    val addedToast = stringResource(R.string.addmember_added_toast)
    val chitFullTemplate = stringResource(R.string.addmember_error_chit_full)
    val mobileTakenTemplate = stringResource(R.string.addmember_error_mobile_taken)
    val alreadyAddedText = stringResource(R.string.addmember_error_already_added)
    val rejoinedToast = stringResource(R.string.addmember_rejoined_toast)
    val existingCustomerToast = stringResource(R.string.addmember_existing_toast)

    fun save() {
        val dateFormat = SimpleDateFormat("dd-MMM-yyyy", Locale.ENGLISH).apply { isLenient = false }
        val parsedJoining = runCatching { dateFormat.parse(joiningDate.trim()) }.getOrNull()
        val parsedDue = runCatching { dateFormat.parse(dueDate.trim()) }.getOrNull()
        val installmentValue = installment.toLongOrNull() ?: 0L
        error = when {
            selectedGroup == null -> errSelectChit
            customerCode.isBlank() -> errCodeRequired
            name.isBlank() -> errNameRequired
            phone.length != 10 -> errInvalidMobile
            !hasFixedSchedule && installmentValue <= 0 -> errInvalidInstallment
            parsedJoining == null || parsedDue == null -> errDateFormat
            parsedDue.before(parsedJoining) -> errDueBeforeJoining
            else -> null
        }
        if (error != null) return
        saving = true
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching {
                // Identity rules (existing code / mobile / rejoin / ticket / KYC) live in the service.
                com.jothivel.chits.data.local.MemberEnrollmentService.addMemberToChit(
                    AppDatabase.getDatabase(context),
                    selectedGroup!!.id,
                    com.jothivel.chits.data.local.NewMemberInput(
                        customerCode = customerCode, name = name, phone = phone, address = address, city = city,
                        nomineeName = nomineeName, nomineePhone = nomineePhone, nomineeRelationship = nomineeRelation,
                        aadhaarLast4 = aadhaarLast4, ticketNo = ticketNo,
                        installmentPaise = installmentValue * 100,
                        joiningDate = joiningDate, dueDate = dueDate
                    )
                )
            } }
            saving = false
            result.onSuccess { added ->
                android.widget.Toast.makeText(
                    context,
                    when {
                        added.rejoined -> rejoinedToast
                        added.reusedExistingCustomer -> existingCustomerToast
                        else -> addedToast
                    },
                    android.widget.Toast.LENGTH_SHORT
                ).show()
                customerCode = newCustomerCode()
                name = ""; phone = ""; address = ""; city = ""; error = null
                ticketNo = ""; aadhaarLast4 = ""; nomineeName = ""; nomineePhone = ""; nomineeRelation = ""
                selectedGroup = null; installment = ""; dueDate = ""
                refreshKey++
            }
                .onFailure { error = it.message ?: errSaveFailed }
        }
    }

    Column(Modifier.fillMaxSize().background(MaroonBackground)) {
        BrandTopBar(stringResource(R.string.addmember_title), back = onBack)
        LazyColumn(contentPadding = PaddingValues(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item {
                // ── Premium chit picker trigger ─────────────────────────────────────
                val memberCounts by produceState(initialValue = emptyMap<String, Int>(), groups) {
                    value = withContext(Dispatchers.IO) {
                        val db = AppDatabase.getDatabase(context)
                        groups.associate { g -> g.id to db.membershipDao().countActiveForGroupSync(g.id) }
                    }
                }
                BottomSheetPickerField(
                    label = stringResource(R.string.collection_chit_label),
                    options = groups,
                    selectedOption = selectedGroup,
                    optionKey = { it.id },
                    optionLabel = { "${it.registerNo} • ${it.name}" },
                    optionSubLabel = { g ->
                        val count = memberCounts[g.id] ?: 0
                        "${money(g.chitValue.toLong() / 100)} • $count/${g.subscriberCount} members"
                    },
                    onOptionSelected = ::selectGroup,
                    trigger = { displayText, onClick ->
                        Surface(
                            modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
                            shape = RoundedCornerShape(12.dp),
                            color = if (selectedGroup != null) SurfaceElevated else Color.White,
                            border = BorderStroke(
                                1.dp,
                                if (selectedGroup != null) MaroonPrimary.copy(alpha = .35f) else DividerGray
                            ),
                            shadowElevation = if (selectedGroup != null) 1.dp else 0.dp
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    Modifier.size(28.dp).background(SurfaceElevated, RoundedCornerShape(8.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        Icons.Default.AccountBalance,
                                        null,
                                        tint = if (selectedGroup != null) MaroonPrimary else TextGray,
                                        modifier = Modifier.size(15.dp)
                                    )
                                }
                                Spacer(Modifier.width(9.dp))
                                Column(Modifier.weight(1f)) {
                                    if (selectedGroup != null) {
                                        Text(
                                            "${selectedGroup!!.registerNo} • ${selectedGroup!!.name}",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = Color.Black,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            "${money(selectedGroup!!.chitValue.toLong() / 100)} • ${memberCounts[selectedGroup!!.id] ?: 0}/${selectedGroup!!.subscriberCount} members",
                                            fontSize = 9.sp,
                                            color = MaroonPrimary.copy(alpha = .80f)
                                        )
                                    } else {
                                        Text(stringResource(R.string.addmember_select_chit_group), fontSize = 12.sp, color = TextGray)
                                        Text(stringResource(R.string.addmember_tap_to_choose, groups.size), fontSize = 9.sp, color = TextGray.copy(alpha = .65f))
                                    }
                                }
                                Icon(
                                    Icons.Default.KeyboardArrowDown,
                                    null,
                                    tint = if (selectedGroup != null) MaroonPrimary else TextGray,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                )
            }
            item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CompactFormField(stringResource(R.string.addmember_customer_code), customerCode, { customerCode = it }, Modifier.fillMaxWidth())
            } }
            item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CompactFormField(stringResource(R.string.addmember_customer_name), name, { name = it }, Modifier.weight(1.25f))
                CompactFormField(stringResource(R.string.addmember_mobile), phone, { phone = it.filter(Char::isDigit).take(10) }, Modifier.weight(1f), KeyboardType.Phone)
            } }
            item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (hasFixedSchedule) {
                    // Material3's OutlinedTextField only shows `placeholder` while focused, and a
                    // disabled field can never focus - so the hint has to be the field's actual
                    // `value` (always visible) instead, or it silently never renders.
                    CompactFormField(stringResource(R.string.addmember_installment_scheduled), stringResource(R.string.addmember_installment_scheduled_hint), {}, Modifier.weight(1f), KeyboardType.Number, enabled = false)
                } else {
                    CompactFormField(stringResource(R.string.addmember_installment), installment, { installment = it.filter(Char::isDigit) }, Modifier.weight(1f), KeyboardType.Number)
                }
                CompactFormField(stringResource(R.string.addmember_city), city, { city = it }, Modifier.weight(1f))
            } }
            item { CompactFormField(stringResource(R.string.addmember_address), address, { address = it }, Modifier.fillMaxWidth()) }
            item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CompactFormField(stringResource(R.string.addmember_ticket), ticketNo, { ticketNo = it.filter(Char::isDigit).take(3) }, Modifier.weight(1f), KeyboardType.Number)
                CompactFormField(stringResource(R.string.addmember_aadhaar_last4), aadhaarLast4, { aadhaarLast4 = it.filter(Char::isDigit).take(4) }, Modifier.weight(1f), KeyboardType.Number)
            } }
            item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CompactFormField(stringResource(R.string.addmember_nominee_name), nomineeName, { nomineeName = it }, Modifier.weight(1.25f))
                CompactFormField(stringResource(R.string.addmember_nominee_phone), nomineePhone, { nomineePhone = it.filter(Char::isDigit).take(10) }, Modifier.weight(1f), KeyboardType.Phone)
            } }
            item { CompactFormField(stringResource(R.string.addmember_nominee_relation), nomineeRelation, { nomineeRelation = it }, Modifier.fillMaxWidth()) }
            item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CompactDateField(stringResource(R.string.addmember_joining_date), joiningDate, { joiningDate = it; selectedGroup?.let(::selectGroup) }, Modifier.weight(1f))
                CompactDateField(stringResource(R.string.addmember_due_date), dueDate, { dueDate = it }, Modifier.weight(1f))
            } }
            error?.let { message -> item { Text(message, color = AccentRed, fontSize = 10.sp) } }
            item { Button(onClick = ::save, enabled = !saving && groups.isNotEmpty(), modifier = Modifier.fillMaxWidth().height(44.dp), colors = ButtonDefaults.buttonColors(containerColor = MaroonPrimary)) { if (saving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White) else Text(stringResource(R.string.addmember_add_button)) } }
            item { AshAnimatedSearchBar(memberSearchQuery, { memberSearchQuery = it }, stringResource(R.string.addmember_search_placeholder), Modifier.fillMaxWidth()) }
            item {
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.addmember_members_title), fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text(stringResource(R.string.addmember_members_count, filteredMembers.size), fontSize = 10.sp, color = TextGray)
                }
            }
            if (filteredMembers.isEmpty()) item { EmptyCreatedList(stringResource(R.string.addmember_none_added)) }
            items(filteredMembers, key = { it.id }, contentType = { "member_card" }) { member ->
                CompactMemberCard(member, member.selectedChitId?.let(groupsById::get))
            }
        }
    }
}

@Composable
private fun EmptyCreatedList(message: String) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), color = Color.White, border = BorderStroke(1.dp, DividerGray)) {
        Text(message, modifier = Modifier.padding(18.dp), color = TextGray, fontSize = 11.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
private fun CompactGroupCard(
    group: ChitGroupEntity,
    progress: GroupProgress,
    onOpen: () -> Unit,
    onAddMember: (String) -> Unit = {}
) {
    val isFull = progress.members >= group.subscriberCount
    Surface(
        modifier = Modifier.fillMaxWidth().shadow(2.dp, RoundedCornerShape(14.dp), clip = false, ambientColor = Color.Black.copy(alpha = .06f), spotColor = Color.Black.copy(alpha = .10f)),
        shape = RoundedCornerShape(14.dp),
        color = Color.White,
        border = BorderStroke(1.dp, DividerGray)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpen)
                .padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Icon badge
            Box(
                Modifier.size(38.dp).background(SurfaceElevated, RoundedCornerShape(11.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.AccountBalance, null, tint = MaroonPrimary, modifier = Modifier.size(20.dp))
            }
            // Title + subtitle
            Column(Modifier.padding(start = 10.dp).weight(1f)) {
                Text(
                    "${group.registerNo} • ${group.name}",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${progress.members}/${group.subscriberCount} members • ${money(group.chitValue.toLong() / 100)}",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (isFull) AccentGreen else MaroonPrimary,
                    maxLines = 1
                )
            }
            // Status badge
            if (group.status == "COMPLETED") com.jothivel.chits.ui.groups.ChitClosedTag() else Text(
                group.status ?: "ACTIVE",
                fontSize = 8.sp,
                color = AccentGreen,
                modifier = Modifier
                    .background(AccentGreen.copy(alpha = .10f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 6.dp, vertical = 3.dp)
            )
            Spacer(Modifier.width(6.dp))
            // Plus icon – only when chit has vacant slots (and is still open)
            if (!isFull && group.status != "COMPLETED") {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .background(MaroonPrimary.copy(alpha = .08f), CircleShape)
                        .clickable(onClick = { onAddMember(group.id) }),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.PersonAdd,
                        contentDescription = "Add Member",
                        tint = MaroonPrimary,
                        modifier = Modifier.size(15.dp)
                    )
                }
                Spacer(Modifier.width(4.dp))
            }
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = stringResource(R.string.chits_open_group),
                tint = MaroonPrimary,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

/**
 * Full-page chit group view: details on top, the full member list below it, each member row
 * carrying the same monthly-schedule and ledger icons. Replaces the old inline expand/collapse
 * on the group card - a dedicated page instead of an accordion, since the accordion got cramped
 * with a full 20-member list squeezed into it.
 */
@Composable
private fun ChitGroupDetailScreen(groupId: String, onBack: () -> Unit, onAddMember: (String) -> Unit) {
    val context = LocalContext.current
    var refreshKey by remember { mutableIntStateOf(0) }
    val group by produceState<ChitGroupEntity?>(initialValue = null, groupId, refreshKey) {
        value = withContext(Dispatchers.IO) { AppDatabase.getDatabase(context).groupDao().getGroupByIdSync(groupId) }
    }
    val g = group
    if (g == null) {
        Column(Modifier.fillMaxSize().background(MaroonBackground)) {
            BrandTopBar(stringResource(R.string.chits_new_chit_title), back = onBack)
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = MaroonPrimary) }
        }
        return
    }

    val progress by produceState(initialValue = GroupProgress(), g, refreshKey) {
        value = withContext(Dispatchers.IO) {
            val db = AppDatabase.getDatabase(context)
            computeGroupProgress(db, g, db.paymentDao().getPaymentsForGroupSync(g.id))
        }
    }
    val memberships by produceState(initialValue = emptyList<ChitMembershipEntity>(), groupId, refreshKey) {
        value = withContext(Dispatchers.IO) { AppDatabase.getDatabase(context).membershipDao().getActiveForGroupSync(groupId) }
    }
    // Members who left the chit (shown with a Rejoin button).
    val leftMemberships by produceState(initialValue = emptyList<ChitMembershipEntity>(), groupId, refreshKey) {
        value = withContext(Dispatchers.IO) { AppDatabase.getDatabase(context).membershipDao().getAllForGroupSync(groupId).filter { !it.isActive } }
    }
    val memberNames by produceState(initialValue = emptyMap<String, String>(), memberships, leftMemberships) {
        if (memberships.isNotEmpty() || leftMemberships.isNotEmpty()) {
            value = withContext(Dispatchers.IO) {
                // One query for all names (this used to load the whole members table once per row).
                val byId = AppDatabase.getDatabase(context).memberDao().getAllMembersSync().associateBy { it.id }
                (memberships + leftMemberships).associate { ms -> ms.memberId to (byId[ms.memberId]?.name ?: ms.memberId) }
            }
        }
    }
    val installments by produceState(initialValue = emptyList<InstallmentEntity>(), groupId, refreshKey) {
        value = withContext(Dispatchers.IO) { AppDatabase.getDatabase(context).installmentDao().getInstallmentsForGroupSync(groupId) }
    }
    // Per-member "Paid up / Partial / Due" tag for the currently-due installment, using the same
    // canonical CollectionService.calculateDueBreakdown every other screen (Pending, Ledger,
    // Collection) reads from - so this tag never disagrees with what those screens show.
    val memberDueStatus by produceState(initialValue = emptyMap<String, MemberDueStatus>(), groupId, memberships, refreshKey) {
        if (memberships.isNotEmpty()) {
            value = withContext(Dispatchers.IO) {
                val db = AppDatabase.getDatabase(context)
                // Bulk-loaded (group, installments, payments read once) - same numbers as the
                // per-member calculation, without four queries per row.
                val breakdowns = com.jothivel.chits.data.local.DueLoader.loadForGroup(db, groupId)
                val paymentsByMember = db.paymentDao().getPaymentsForGroupSync(groupId).groupBy { it.memberId }
                memberships.associate { ms ->
                    val breakdown = breakdowns[ms.memberId]
                    val status = when {
                        breakdown == null || breakdown.pendingInstallments.isEmpty() -> MemberDueStatus.PAID_UP
                        else -> {
                            val first = breakdown.pendingInstallments.first().toString()
                            val allocated = paymentsByMember[ms.memberId].orEmpty().filter { it.installmentId == first }.sumOf { it.amountPaid }
                            if (allocated > 0) MemberDueStatus.PARTIAL else MemberDueStatus.DUE
                        }
                    }
                    ms.memberId to status
                }
            }
        }
    }
    val hasFixedSchedule = remember(g) { ChitTemplate.forChitValue(g.chitValue / 100)?.fixedSchedule?.size == g.durationMonths }
    val isFull = progress.members >= g.subscriberCount
    var monthlyViewMember by remember { mutableStateOf<ChitMembershipEntity?>(null) }
    var showEdit by remember { mutableStateOf(false) }
    var showClose by remember { mutableStateOf(false) }
    var removeTarget by remember { mutableStateOf<ChitMembershipEntity?>(null) }
    val pendingMembers by produceState(initialValue = 0, groupId, refreshKey) {
        value = withContext(Dispatchers.IO) { com.jothivel.chits.data.local.ChitAdminService.membersWithPendingDues(AppDatabase.getDatabase(context), groupId) }
    }
    val chitClosed = g.status == "COMPLETED"

    Column(Modifier.fillMaxSize().background(MaroonBackground)) {
        BrandTopBar("${g.registerNo} • ${g.name}", back = onBack)
        LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                // ── Chit details card ───────────────────────────────────
                Surface(
                    Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    color = Color.White,
                    border = BorderStroke(1.dp, DividerGray)
                ) {
                    Column(Modifier.fillMaxWidth().padding(14.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            ChitStatChip(label = stringResource(R.string.chits_stat_value), value = money(g.chitValue.toLong() / 100))
                            ChitStatChip(label = stringResource(R.string.chits_stat_duration), value = stringResource(R.string.chits_months_suffix, g.durationMonths))
                            ChitStatChip(label = stringResource(R.string.chits_stat_branch), value = g.branch.orEmpty().ifBlank { "-" })
                            ChitStatChip(label = stringResource(R.string.chits_stat_collected), value = money(progress.collected))
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(R.string.chits_started_label), fontSize = 9.sp, color = MaroonPrimary, fontWeight = FontWeight.SemiBold)
                                Text(g.startDate.orEmpty().ifBlank { "-" }, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                            }
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(R.string.chits_next_due_label), fontSize = 9.sp, color = MaroonPrimary, fontWeight = FontWeight.SemiBold)
                                Text(progress.nextDue, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = MaroonPrimary)
                            }
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(R.string.chits_progress_label), fontSize = 9.sp, color = MaroonPrimary, fontWeight = FontWeight.SemiBold)
                                Text(stringResource(R.string.chits_progress_value, progress.elapsed, g.durationMonths), fontSize = 11.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        val fillFraction = if (g.subscriberCount > 0) progress.members.toFloat() / g.subscriberCount else 0f
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                stringResource(R.string.chits_members_header, progress.members, g.subscriberCount),
                                fontSize = 10.sp, color = TextGray, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                if (isFull) stringResource(R.string.chits_full) else stringResource(R.string.chits_slots_open, g.subscriberCount - progress.members),
                                fontSize = 9.sp,
                                color = if (isFull) AccentGreen else AccentOrange
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Box(Modifier.fillMaxWidth().height(6.dp).background(DividerGray, RoundedCornerShape(4.dp))) {
                            Box(
                                Modifier.fillMaxWidth(fillFraction).height(6.dp)
                                    .background(if (isFull) AccentGreen else MaroonPrimary, RoundedCornerShape(4.dp))
                            )
                        }
                    }
                }
            }
            item {
                com.jothivel.chits.ui.groups.ChitGroupActionsRow(g, onEdit = { showEdit = true }, onCloseOrReopen = { showClose = true })
            }
            item {
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.chits_members_header, progress.members, g.subscriberCount), fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    if (chitClosed) com.jothivel.chits.ui.groups.ChitClosedTag()
                    if (!isFull && !chitClosed) {
                        TextButton(onClick = { onAddMember(g.id) }) {
                            Icon(Icons.Default.PersonAdd, null, tint = MaroonPrimary, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.addmember_title), color = MaroonPrimary, fontSize = 12.sp)
                        }
                    }
                }
            }
            if (memberships.isEmpty()) {
                item { EmptyCreatedList(stringResource(R.string.chits_no_members_yet)) }
            } else {
                items(memberships, key = { it.id }) { ms ->
                    // ── One member row: details + monthly-schedule / ledger icons ──
                    Surface(
                        Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = Color.White,
                        border = BorderStroke(1.dp, DividerGray)
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val memberName = memberNames[ms.memberId] ?: ms.memberId
                            Box(
                                Modifier.size(30.dp).background(SurfaceElevated, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(memberName.firstOrNull()?.uppercase() ?: "?", color = MaroonPrimary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                            Column(Modifier.padding(start = 10.dp).weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(memberName, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                    when (memberDueStatus[ms.memberId]) {
                                        MemberDueStatus.PARTIAL -> MemberStatusTag(stringResource(R.string.chits_status_partial), AccentOrange)
                                        MemberDueStatus.DUE -> MemberStatusTag(stringResource(R.string.chits_status_due), AccentRed)
                                        MemberDueStatus.PAID_UP, null -> {}
                                    }
                                }
                                Text(stringResource(R.string.chits_ticket_joined, ms.ticketNo ?: "-", ms.joiningDate ?: "-"), fontSize = 9.sp, color = TextGray, maxLines = 1)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    if (hasFixedSchedule) stringResource(R.string.chits_amount_varies) else "₹${ms.installmentAmountPaise / 100}",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaroonPrimary
                                )
                                Text(stringResource(R.string.chits_due_label, ms.dueDate ?: "-"), fontSize = 8.sp, color = TextGray)
                            }
                            Spacer(Modifier.width(8.dp))
                            Box(
                                Modifier.size(26.dp).clip(CircleShape).clickable { monthlyViewMember = ms },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.CalendarMonth, contentDescription = stringResource(R.string.chits_monthly_view_icon), tint = MaroonPrimary, modifier = Modifier.size(16.dp))
                            }
                            if (!chitClosed) {
                                Spacer(Modifier.width(4.dp))
                                Box(
                                    Modifier.size(26.dp).clip(CircleShape).clickable { removeTarget = ms },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Default.PersonRemove, contentDescription = stringResource(R.string.group_remove_icon), tint = AccentRed, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }
            }
            item { com.jothivel.chits.ui.groups.LeftMembersCard(leftMemberships, memberNames, g.id) { refreshKey++ } }
            item { com.jothivel.chits.ui.groups.WinnersCard(g, installments, memberships, memberNames) { refreshKey++ } }
        }
    }

    if (showEdit) com.jothivel.chits.ui.groups.EditChitSheet(g, onDismiss = { showEdit = false }, onSaved = { showEdit = false; refreshKey++ })
    if (showClose) com.jothivel.chits.ui.groups.CloseChitSheet(g, pendingMembers, onDismiss = { showClose = false }, onDone = { showClose = false; refreshKey++ })
    removeTarget?.let { ms ->
        com.jothivel.chits.ui.groups.RemoveMemberSheet(memberNames[ms.memberId] ?: ms.memberId, ms.memberId, g.id, onDismiss = { removeTarget = null }, onDone = { removeTarget = null; refreshKey++ })
    }

    monthlyViewMember?.let { ms ->
        MemberMonthlyScheduleDialog(
            memberName = memberNames[ms.memberId] ?: ms.memberId,
            group = g,
            membership = ms,
            installments = installments,
            onDismiss = { monthlyViewMember = null }
        )
    }
}

/** One month's payment status for a member, derived from PaymentEntity rows against the
 *  group's fixed installment schedule the same way CollectionService.calculateDueBreakdown does. */
private enum class MonthPaymentState { PAID, PARTIAL, PENDING }

/** A member's status for their currently-due installment, shown as a tag on their row in
 *  ChitGroupDetailScreen - PAID_UP means nothing outstanding right now. */
private enum class MemberDueStatus { PAID_UP, PARTIAL, DUE }

private data class MonthlyScheduleRow(val installmentNo: Int, val amountPaise: Long, val state: MonthPaymentState, val paidAt: Long?)

/** Table popup: the member's month-by-month payable amount (from this group's fixed schedule,
 *  or the member's own override), the date it was actually paid on, and a tick for months paid
 *  in full - merges what used to be two separate popups (schedule + ledger history) into one,
 *  since the paid date makes a standalone ledger popup redundant. */
@Composable
private fun MemberMonthlyScheduleDialog(
    memberName: String,
    group: ChitGroupEntity,
    membership: ChitMembershipEntity,
    installments: List<InstallmentEntity>,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val payments by produceState(initialValue = emptyList<PaymentEntity>(), membership.memberId, group.id) {
        value = withContext(Dispatchers.IO) {
            AppDatabase.getDatabase(context).paymentDao().getPaymentsByMemberAndGroupSync(membership.memberId, group.id)
        }
    }
    val installmentPayments = remember(payments) {
        payments.filter { it.installmentId != "ADVANCE" }.groupBy { it.installmentId }
    }
    val paidByInstallment = remember(installmentPayments) {
        installmentPayments.mapValues { (_, rows) -> rows.sumOf { it.amountPaid } }
    }
    // Latest payment date touching this installment - if it was paid across more than one
    // collection (partial then top-up), this is the most recent of those, which is what a
    // collector actually wants to see ("when did this get settled").
    val paidDateByInstallment = remember(installmentPayments) {
        installmentPayments.mapValues { (_, rows) -> rows.maxOf { it.paidAt } }
    }
    // Fixed-schedule plans (50K/1L/2L/3L/10L) pay a different amount every month by design, so
    // that month's plan amount always wins over any flat per-member override - see the matching
    // rule in CollectionService.scheduledAmountPaise, which this dialog must stay consistent with.
    val hasFixedSchedule = remember(group) { ChitTemplate.forChitValue(group.chitValue / 100)?.fixedSchedule?.size == group.durationMonths }
    val rows = remember(installments, membership, paidByInstallment, paidDateByInstallment, hasFixedSchedule) {
        installments.sortedBy { it.installmentNo }.map { inst ->
            val flatFallback = (inst.baseAmount - (inst.kasaruAmount ?: 0)).toLong()
            val scheduled = if (hasFixedSchedule) flatFallback else (membership.installmentAmountPaise.takeIf { it > 0 } ?: flatFallback)
            val key = inst.installmentNo.toString()
            val paid = paidByInstallment[key] ?: 0L
            val state = when {
                scheduled > 0 && paid >= scheduled -> MonthPaymentState.PAID
                paid > 0 -> MonthPaymentState.PARTIAL
                else -> MonthPaymentState.PENDING
            }
            MonthlyScheduleRow(inst.installmentNo, scheduled, state, paidDateByInstallment[key])
        }
    }
    val dateFormat = remember { SimpleDateFormat("dd MMM yy", Locale.ENGLISH) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(memberName, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text("${group.registerNo} • ${group.name}", fontSize = 10.sp, color = TextGray)
            }
        },
        text = {
            if (rows.isEmpty()) {
                Text(stringResource(R.string.chits_no_schedule), color = TextGray, fontSize = 12.sp)
            } else {
                Box(Modifier.heightIn(max = 400.dp).border(1.dp, DividerGray)) {
                    Column {
                        Row(Modifier.height(30.dp).background(Color(0xFFECEDEF))) {
                            MonthlyTableCell(Modifier.width(30.dp)) { Text(stringResource(R.string.chits_table_month), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = TextGray) }
                            MonthlyTableCell(Modifier.weight(1f), alignEnd = true) { Text(stringResource(R.string.chits_table_amount), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = TextGray) }
                            MonthlyTableCell(Modifier.width(76.dp), alignEnd = true) { Text(stringResource(R.string.chits_table_paid_date), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = TextGray) }
                            MonthlyTableCell(Modifier.width(30.dp), alignCenter = true, isLastColumn = true) {}
                        }
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            rows.forEach { row ->
                                val isLastRow = row.installmentNo == rows.last().installmentNo
                                Row(Modifier.height(34.dp)) {
                                    MonthlyTableCell(Modifier.width(30.dp), isLastRow = isLastRow) {
                                        Text(row.installmentNo.toString(), fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                    }
                                    MonthlyTableCell(Modifier.weight(1f), alignEnd = true, isLastRow = isLastRow) {
                                        Text(money(row.amountPaise / 100), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaroonPrimary)
                                    }
                                    MonthlyTableCell(Modifier.width(76.dp), alignEnd = true, isLastRow = isLastRow) {
                                        Text(row.paidAt?.let { dateFormat.format(Date(it)) } ?: "-", fontSize = 10.sp, color = TextGray)
                                    }
                                    MonthlyTableCell(Modifier.width(30.dp), alignCenter = true, isLastColumn = true, isLastRow = isLastRow) {
                                        when (row.state) {
                                            MonthPaymentState.PAID -> Icon(Icons.Default.CheckCircle, contentDescription = stringResource(R.string.chits_month_paid), tint = AccentGreen, modifier = Modifier.size(16.dp))
                                            MonthPaymentState.PARTIAL -> Icon(Icons.Default.CheckCircle, contentDescription = stringResource(R.string.chits_month_partial), tint = AccentOrange, modifier = Modifier.size(16.dp))
                                            MonthPaymentState.PENDING -> Box(Modifier.size(14.dp).background(Color.Transparent, CircleShape).border(1.5.dp, DividerGray, CircleShape))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.chits_close_button)) } }
    )
}

/** One grid cell of the monthly schedule table - draws its own right/bottom border lines
 *  (skipped on the last column/row so they don't double up with the table's outer border),
 *  same bordered-cell technique ResizableLedgerSheet's ledger grid uses. Requires a parent Row
 *  with an explicit height so fillMaxHeight() below has something bounded to fill. */
@Composable
private fun MonthlyTableCell(
    modifier: Modifier = Modifier,
    alignEnd: Boolean = false,
    alignCenter: Boolean = false,
    isLastColumn: Boolean = false,
    isLastRow: Boolean = false,
    content: @Composable () -> Unit
) {
    Box(
        modifier
            .fillMaxHeight()
            .drawBehind {
                val stroke = 1.dp.toPx()
                if (!isLastColumn) {
                    drawLine(DividerGray, Offset(size.width - stroke / 2f, 0f), Offset(size.width - stroke / 2f, size.height), stroke)
                }
                if (!isLastRow) {
                    drawLine(DividerGray, Offset(0f, size.height - stroke / 2f), Offset(size.width, size.height - stroke / 2f), stroke)
                }
            }
            .padding(horizontal = 6.dp, vertical = 6.dp),
        contentAlignment = when {
            alignCenter -> Alignment.Center
            alignEnd -> Alignment.CenterEnd
            else -> Alignment.CenterStart
        }
    ) { content() }
}

// Small stat chip used inside the expanded group card
@Composable
private fun ChitStatChip(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontSize = 8.sp, color = MaroonPrimary, fontWeight = FontWeight.SemiBold)
        Text(value, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = Color.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun MemberStatusTag(label: String, color: Color) {
    Text(
        label,
        fontSize = 8.sp,
        fontWeight = FontWeight.Bold,
        color = color,
        modifier = Modifier
            .padding(start = 6.dp)
            .background(color.copy(alpha = .12f), RoundedCornerShape(5.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp)
    )
}

@Composable
private fun CompactMemberCard(member: MemberEntity, group: ChitGroupEntity?) {
    val context = LocalContext.current
    val memberDetailsToast = stringResource(R.string.chits_member_details_toast)
    Surface(Modifier.fillMaxWidth().clickable { android.widget.Toast.makeText(context, memberDetailsToast, android.widget.Toast.LENGTH_SHORT).show() }, shape = RoundedCornerShape(12.dp), color = Color.White, border = BorderStroke(1.dp, DividerGray)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 11.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(34.dp).background(SurfaceElevated, CircleShape), contentAlignment = Alignment.Center) {
                Text(member.name?.firstOrNull()?.uppercase() ?: "M", color = MaroonPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
            Column(Modifier.padding(start = 9.dp).weight(1f)) {
                Text(member.name ?: "Unnamed", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${member.phone.orEmpty()} • ${member.city.orEmpty()}", fontSize = 9.sp, color = TextGray, maxLines = 1)
                val chitLine = buildString {
                    append(group?.registerNo ?: "No chit assigned")
                    member.ticketNo?.takeIf { it.isNotBlank() }?.let { append(" • Ticket $it") }
                    member.installmentAmount?.takeIf { it.isNotBlank() }?.let { append(" • ₹$it") }
                }
                Text(chitLine, fontSize = 8.sp, color = TextGray, maxLines = 1)
            }
            if (member.isActive) Text("Active", fontSize = 8.sp, color = AccentGreen)
        }
    }
}

@Composable
private fun CompactFormField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier, keyboardType: KeyboardType = KeyboardType.Text, enabled: Boolean = true, placeholder: String? = null) {
    PremiumInputField(
        value = value,
        onValueChange = onChange,
        label = label,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = modifier.height(54.dp),
        enabled = enabled,
        placeholder = placeholder
    )
}

@Composable
private fun CompactDateField(label:String,value:String,onChange:(String)->Unit,modifier:Modifier,pattern:String="dd-MMM-yyyy") {
    com.jothivel.chits.ui.components.DateBottomSheetField(label=label,value=value,onValueChange=onChange,modifier=modifier.height(54.dp),pattern=pattern)
}

@Composable
private fun PendingScreen(
    onBack: () -> Unit,
    onOpenLedger: (DueCustomer) -> Unit,
    onCollect: (DueCustomer) -> Unit
) {
    var selectedArea by remember { mutableStateOf<String?>(null) }
    var selectedChit by remember { mutableStateOf<String?>(null) }
    var selectedAging by remember { mutableStateOf<String?>(null) }
    var showFilters by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var activeResultIndex by remember { mutableIntStateOf(0) }
    var expandedDueKey by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val unnamedText = stringResource(R.string.pending_unnamed)
    val notPaidText = stringResource(R.string.pending_not_paid)
    val unassignedText = stringResource(R.string.pending_unassigned)
    val baseDues by produceState(initialValue = emptyList<DueCustomer>(), context) {
        value = withContext(Dispatchers.IO) {
            val db = AppDatabase.getDatabase(context)
            // Only the reminder entries are read (the log table grows without bound), and every
            // group / installment / payment is loaded once for all customers.
            val contacts = db.activityLogDao().getByTypeSync("CUSTOMER_CONTACTED").groupBy { it.description.substringBefore('|') }
            com.jothivel.chits.data.local.DueLoader.loadAll(db).mapNotNull { row ->
                val member = row.member
                val group = row.group
                val breakdown = row.breakdown
                val pending = breakdown.pendingPaise / 100
                if (pending == 0L) null else {
                    DueCustomer(
                        name = member.name ?: unnamedText,
                        code = member.id,
                        phone = member.phone.orEmpty(),
                        area = listOfNotNull(member.addressLine?.takeIf { it.isNotBlank() }, member.city?.takeIf { it.isNotBlank() }).joinToString(", "),
                        filterArea = member.city?.trim()?.takeIf { it.isNotBlank() } ?: member.addressLine?.trim().orEmpty(),
                        chit = listOfNotNull(group.registerNo?.takeIf { it.isNotBlank() }, group.name?.takeIf { it.isNotBlank() }).joinToString(" - "),
                        chitValue = group.chitValue.toLong() / 100,
                        payable = breakdown.payablePaise / 100,
                        paid = breakdown.paidPaise / 100,
                        pending = pending,
                        lastPaid = breakdown.lastPaidAt?.let { SimpleDateFormat("dd-MMM-yy", Locale.ENGLISH).format(Date(it)) } ?: notPaidText,
                        dueDate = breakdown.earliestDueDate,
                        installments = breakdown.pendingInstallments.map { "$it TH" },
                        agent = unassignedText,
                        overdueDays = breakdown.overdueDays,
                        recentContactAt = contacts[member.id]?.maxOfOrNull { it.timestamp } ?: 0L,
                        groupId = group.id
                    )
                }
            }
        }
    }
    // Which labour a chit is assigned to is a cloud lookup. It used to run inside the list load, so
    // an offline or slow connection held the whole Pending screen back; now the list appears at once
    // and the names fill in when (if) they arrive.
    val agentNames by produceState(initialValue = emptyMap<String, String>(), context) { value = loadAgentNamesByGroup(context) }
    val roomDues = remember(baseDues, agentNames) {
        if (agentNames.isEmpty()) baseDues else baseDues.map { due -> agentNames[due.groupId]?.let { due.copy(agent = it) } ?: due }
    }
    val areaOptions = remember(roomDues) { roomDues.map { it.filterArea }.filter { it.isNotBlank() }.distinct().sorted() }
    val chitOptions = remember(roomDues) { roomDues.map { it.chit }.filter { it.isNotBlank() }.distinct().sorted() }
    val agingOptions = listOf("1–30 days", "31–60 days", "61–90 days", "90+ days")
    val hasFilters = selectedArea != null || selectedChit != null || selectedAging != null
    val filtered = remember(roomDues, query, selectedArea, selectedChit, selectedAging) {
        roomDues.filter {
            (it.name.contains(query, true) || it.phone.contains(query) || it.chit.contains(query, true) || it.area.contains(query, true) || it.agent.contains(query, true) || it.installments.any { month -> month.contains(query, true) }) &&
                (selectedArea == null || it.filterArea == selectedArea) &&
                (selectedChit == null || it.chit == selectedChit) &&
                when (selectedAging) {
                    "1–30 days" -> it.overdueDays in 1..30
                    "31–60 days" -> it.overdueDays in 31..60
                    "61–90 days" -> it.overdueDays in 61..90
                    "90+ days" -> it.overdueDays > 90
                    else -> true
                }
            }.sortedWith(compareByDescending<DueCustomer> { it.overdueDays >= 90 }.thenByDescending { it.overdueDays }.thenByDescending { it.pending }.thenBy { if(it.recentContactAt==0L) 0 else 1 })
        }
    val activeDue = filtered.getOrNull(activeResultIndex)

    fun goToPendingResult(direction: Int) {
        if (filtered.isEmpty()) return
        activeResultIndex = (activeResultIndex + direction + filtered.size) % filtered.size
        scope.launch { listState.animateScrollToItem(activeResultIndex + 2) }
    }

    val downloadedToText = stringResource(R.string.ledger_downloaded_to)
    val downloadFailedText = stringResource(R.string.ledger_download_failed)
    fun csvCell(value: Any?): String {
        val text = value?.toString().orEmpty()
        return "\"${text.replace("\"", "\"\"")}\""
    }
    fun downloadPending() {
        val csv = buildString {
            appendLine(listOf("Customer", "Code", "Phone", "Area", "Agent", "Chit", "Payable", "Paid", "Pending", "Pending Months").joinToString(",") { csvCell(it) })
            filtered.forEach { due ->
                appendLine(
                    listOf(
                        due.name,
                        due.code,
                        due.phone,
                        due.area,
                        due.agent,
                        due.chit,
                        due.payable,
                        due.paid,
                        due.pending,
                        due.installments.joinToString("|")
                    ).joinToString(",") { csvCell(it) }
                )
            }
        }
        scope.launch {
            val fileName="Pending-${SimpleDateFormat("yyyyMMdd-HHmm",Locale.US).format(Date())}.csv"
            val result=withContext(Dispatchers.IO){CsvDownloadHelper.save(context,fileName,csv)}
            android.widget.Toast.makeText(context,result.fold({String.format(downloadedToText, it)},{String.format(downloadFailedText, it.message)}),android.widget.Toast.LENGTH_LONG).show()
        }
    }

    LaunchedEffect(query, selectedArea, selectedChit, selectedAging) {
        activeResultIndex = 0
        if (filtered.isNotEmpty() && query.isNotBlank()) listState.animateScrollToItem(2)
    }
    Column(Modifier.fillMaxSize()) {
        BrandTopBar(stringResource(R.string.pending_title), onBack, Icons.Default.FilterAlt, onAction = { showFilters = !showFilters })
        DetailFindToolbar(
            query = query,
            onQueryChange = { query = it },
            placeholder = stringResource(R.string.pending_search_placeholder),
            resultCount = if (query.isBlank()) 0 else filtered.size,
            activeResultIndex = activeResultIndex.coerceAtMost((filtered.size - 1).coerceAtLeast(0)),
            onPrevious = { goToPendingResult(-1) },
            onNext = { goToPendingResult(1) },
            onDownload = { downloadPending() },
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 8.dp)
        )
        LazyColumn(state = listState, contentPadding = PaddingValues(horizontal = 11.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .shadow(4.dp, RoundedCornerShape(16.dp), clip = false, ambientColor = MaroonPrimary.copy(alpha = .08f), spotColor = MaroonPrimary.copy(alpha = .13f))
                        .background(Brush.linearGradient(listOf(Color.White, Color(0xFFFFECEE))), RoundedCornerShape(16.dp))
                        .border(1.dp, MaroonPrimary.copy(alpha = .12f), RoundedCornerShape(16.dp))
                ) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(38.dp).background(MaroonPrimary, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) { Icon(Icons.Default.ReceiptLong, null, tint = Color.White, modifier = Modifier.size(19.dp)) }
                        Column(Modifier.padding(start = 10.dp).weight(1f)) {
                            Text(stringResource(R.string.pending_live_dues), color = MaroonPrimary, fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = .7.sp)
                            Text(money(filtered.sumOf { it.pending }), color = AccentRed, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                        }
                        Surface(shape = RoundedCornerShape(12.dp), color = Color.White.copy(alpha = .8f), border = BorderStroke(1.dp, MaroonPrimary.copy(alpha = .10f))) {
                            Text(stringResource(R.string.pending_customers_count, filtered.size), color = TextGray, fontSize = 9.sp, modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp))
                        }
                    }
                }
            }
            item {
                androidx.compose.animation.AnimatedVisibility(visible = showFilters || hasFilters) {
                    Surface(shape = RoundedCornerShape(11.dp), color = Color.White, border = BorderStroke(1.dp, DividerGray)) {
                        Column(Modifier.padding(horizontal = 7.dp, vertical = 6.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(R.string.pending_filter_title), fontSize = 10.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                if (hasFilters) TextButton(onClick = { selectedArea = null; selectedChit = null; selectedAging = null }, modifier = Modifier.height(28.dp), contentPadding = PaddingValues(horizontal = 7.dp)) { Text(stringResource(R.string.pending_clear), fontSize = 9.sp) }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                PendingFilterMenu(stringResource(R.string.pending_filter_area), selectedArea, areaOptions, Icons.Default.LocationOn, { selectedArea = it }, Modifier.weight(1f))
                                PendingFilterMenu(stringResource(R.string.pending_filter_chit), selectedChit, chitOptions, Icons.Default.AccountBalance, { selectedChit = it }, Modifier.weight(1f))
                                PendingFilterMenu(stringResource(R.string.pending_filter_aging), selectedAging, agingOptions, Icons.Default.Schedule, { selectedAging = it }, Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
            if (filtered.isEmpty()) item { Text(stringResource(R.string.pending_no_match), modifier = Modifier.fillMaxWidth().padding(28.dp), color = TextGray) }
            items(filtered, key = { "${it.code}-${it.chit}" }, contentType = { "pending_card" }) { due ->
                val dueKey = "${due.code}-${due.chit}"
                DueCard(
                    due = due,
                    highlighted = query.isNotBlank() && due == activeDue,
                    expanded = expandedDueKey == dueKey,
                    onToggle = { expandedDueKey = if (expandedDueKey == dueKey) null else dueKey },
                    onOpenLedger = { onOpenLedger(due) },
                    onCollect = { onCollect(due) },
                    onCall = { recordCustomerContact(context,due,"CALL"); context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${due.phone}"))) },
                    onWhatsApp = { recordCustomerContact(context,due,"WHATSAPP"); context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/91${due.phone.filter(Char::isDigit).takeLast(10)}?text=${Uri.encode("Vanakkam ${due.name}, ${due.chit} pending ${money(due.pending)}. Pending installments: ${due.installments.joinToString()}. Kindly make the payment. - Jothi Vel Chits")}"))) }
                )
            }
        }
    }
}

@Composable
private fun DueCard(
    due: DueCustomer,
    highlighted: Boolean = false,
    expanded: Boolean,
    onToggle: () -> Unit,
    onOpenLedger: () -> Unit,
    onCollect: () -> Unit,
    onCall: () -> Unit,
    onWhatsApp: () -> Unit
) {
    val cardColor by androidx.compose.animation.animateColorAsState(if (highlighted) Color(0xFFE3E5E8) else Color.White, label = "dueHighlight")
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = cardColor,
        border = BorderStroke(1.dp, if (highlighted) MaroonPrimary.copy(alpha = .40f) else MaroonPrimary.copy(alpha = .12f))
    ) {
        Column(Modifier.padding(horizontal=11.dp,vertical=10.dp)) {
            Row(Modifier.fillMaxWidth().clickable(onClick = onToggle), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("${due.name} (${due.code})", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, modifier = Modifier.clickable(onClick = onOpenLedger))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Call, null, tint = TextGray, modifier = Modifier.size(11.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(due.phone, fontSize = 10.sp, color = Color.Black)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.LocationOn, null, tint = TextGray, modifier = Modifier.size(11.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.pending_area_agent, due.area, due.agent), fontSize = 10.sp, color = TextGray)
                    }
                    Text("${due.chit} • ${money(due.chitValue)}", fontSize = 10.sp, color = Color.Black, fontWeight = FontWeight.Medium)
                }
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    OverdueBadge(due.overdueDays)
                    Box(Modifier.size(27.dp).background(if (expanded) MaroonSurfaceLight else Color(0xFFF4F1F1), CircleShape).clickable(onClick = onToggle), contentAlignment = Alignment.Center) {
                        Icon(
                            if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            if (expanded) "Hide pending details" else "Show pending details",
                            tint = if (expanded) MaroonPrimary else TextGray,
                            modifier = Modifier.size(19.dp)
                        )
                    }
                }
            }
            androidx.compose.animation.AnimatedVisibility(visible = expanded) {
                Column {
                    Divider(Modifier.padding(vertical = 9.dp), color = DividerGray)
                    Row { MiniDue(stringResource(R.string.pending_payable), due.payable, Color.Black, Modifier.weight(1f)); MiniDue(stringResource(R.string.pending_paid), due.paid, Color.Black, Modifier.weight(1f)); MiniDue(stringResource(R.string.pending_pending), due.pending, AccentRed, Modifier.weight(1f)) }
                    Row(Modifier.fillMaxWidth().padding(top = 7.dp), horizontalArrangement = Arrangement.SpaceBetween) { Text(stringResource(R.string.pending_last_paid, due.lastPaid), color = TextGray, fontSize = 10.sp); Text(stringResource(R.string.pending_due_date, due.dueDate), fontSize = 10.sp) }
                    Row(Modifier.padding(top = 7.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) { due.installments.forEach { Text(it, color = AccentRed, fontSize = 9.sp, modifier = Modifier.background(AccentRedLight, RoundedCornerShape(5.dp)).padding(horizontal = 6.dp, vertical = 3.dp)) } }
                    Row(Modifier.fillMaxWidth().padding(top = 7.dp), horizontalArrangement = Arrangement.SpaceEvenly) { TextButton(onClick = onCall) { Icon(Icons.Default.Call, null); Text(stringResource(R.string.pending_call)) }; TextButton(onClick = onWhatsApp) { Icon(Icons.Default.Chat, null, tint = AccentGreen); Text(stringResource(R.string.pending_whatsapp), color = AccentGreen) }; TextButton(onClick = onCollect) { Icon(Icons.Default.Add, null); Text(stringResource(R.string.pending_collect)) } }
                }
            }
        }
    }
}

@Composable
private fun PendingFilterMenu(
    label: String,
    selectedValue: String?,
    options: List<String>,
    icon: ImageVector,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(12.dp)
    val allLabel = stringResource(R.string.pending_filter_all, label)
    BottomSheetPickerField(
        label = label,
        options = listOf(allLabel) + options,
        selectedValue = selectedValue ?: allLabel,
        onValueSelected = { onSelect(if (it == allLabel) null else it) },
        modifier = modifier,
        showSearch = options.size > 6,
        trigger = { _, onClick ->
            Box(Modifier.fillMaxWidth()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(40.dp)
                        .background(if (selectedValue != null) MaroonPrimary else MaroonSurfaceLight.copy(alpha = .55f), shape)
                        .border(1.dp, if (selectedValue != null) MaroonPrimary else MaroonPrimary.copy(alpha = .10f), shape)
                        .clickable(onClick = onClick)
                        .padding(horizontal = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(icon, null, tint = if (selectedValue != null) Color.White else MaroonPrimary.copy(alpha = .72f), modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(selectedValue ?: label, color = if (selectedValue != null) Color.White else TextGray, fontSize = 8.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Icon(Icons.Default.ArrowDropDown, null, tint = if (selectedValue != null) Color.White else TextGray, modifier = Modifier.size(14.dp))
                }
            }
        }
    )
}

@Composable
private fun OverdueBadge(days: Int) {
    val badgeColor = when {
        days >= 180 -> Color(0xFFB20D24)
        days >= 90 -> Color(0xFFD53848)
        else -> Color(0xFFE85A66)
    }
    Row(
        Modifier
            .background(Brush.horizontalGradient(listOf(badgeColor.copy(alpha = .10f), badgeColor.copy(alpha = .18f))), RoundedCornerShape(10.dp))
            .border(1.dp, badgeColor.copy(alpha = .30f), RoundedCornerShape(10.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(Icons.Default.Schedule, null, tint = badgeColor, modifier = Modifier.size(12.dp))
        Text("${days}d overdue", color = badgeColor, fontSize = 8.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable private fun MiniDue(label: String, value: Long, color: Color, modifier: Modifier) = Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) { Text(label, fontSize = 10.sp, color = MaroonPrimary, fontWeight = FontWeight.SemiBold); Text(money(value), fontWeight = FontWeight.Bold, fontSize = 13.sp, color = color) }

private fun recordCustomerContact(context: android.content.Context, due: DueCustomer, channel: String) {
    kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
        AppDatabase.getDatabase(context).activityLogDao().insertLog(ActivityLogEntity(actionType="CUSTOMER_CONTACTED",title="$channel reminder",description="${due.code}|${due.chit}"))
    }
}

/** Chit id -> "Agent A, Agent B" for active labour accounts; empty if the cloud is unreachable within a few seconds. */
private suspend fun loadAgentNamesByGroup(context: android.content.Context): Map<String, String> = withContext(Dispatchers.IO) {
    kotlinx.coroutines.withTimeoutOrNull(6_000L) {
        runCatching { com.jothivel.chits.data.firebase.AgentAuthRepository.listAgents(context) }
            .getOrDefault(emptyList())
            .filter { it.isActive }
            .fold(HashMap<String, String>()) { acc, agent ->
                agent.assignedGroups.forEach { groupId -> acc[groupId] = acc[groupId]?.let { "$it, ${agent.name}" } ?: agent.name }
                acc
            }
    } ?: emptyMap()
}

private fun loadOperationalDues(db: AppDatabase, unassignedText: String = "Unassigned"): List<DueCustomer> {
    val contacts = db.activityLogDao().getByTypeSync("CUSTOMER_CONTACTED").groupBy { it.description.substringBefore('|') }
    return com.jothivel.chits.data.local.DueLoader.loadAll(db).mapNotNull { row ->
        val member = row.member
        val group = row.group
        val due = row.breakdown
        if (due.pendingPaise <= 0) return@mapNotNull null
        DueCustomer(
            name = member.name ?: "Unnamed", code = member.id, phone = member.phone.orEmpty(),
            area = listOfNotNull(member.addressLine, member.city).joinToString(", "), filterArea = member.city.orEmpty(),
            chit = group.registerNo ?: group.id, chitValue = group.chitValue.toLong() / 100,
            payable = due.payablePaise / 100, paid = due.paidPaise / 100, pending = due.pendingPaise / 100,
            lastPaid = due.lastPaidAt?.let { SimpleDateFormat("dd-MMM-yy", Locale.ENGLISH).format(Date(it)) } ?: "Not paid",
            dueDate = due.earliestDueDate, installments = due.pendingInstallments.map { "$it TH" },
            agent = unassignedText, overdueDays = due.overdueDays,
            recentContactAt = contacts[member.id]?.maxOfOrNull { it.timestamp } ?: 0L, groupId = group.id
        )
    }.sortedWith(compareByDescending<DueCustomer> { it.overdueDays >= 90 }.thenByDescending { it.overdueDays }.thenByDescending { it.pending }.thenBy { if (it.recentContactAt == 0L) 0 else 1 })
}

private data class TodayCompletedRow(val id: String, val memberId: String, val memberName: String, val chitLabel: String, val amountPaise: Long)
private data class TodayWorkSnapshot(val dues:List<DueCustomer> = emptyList(), val completed:List<TodayCompletedRow> = emptyList(), val settlements:Long=0, val deliveries:Long=0)
private data class GroupProgress(val members:Int=0,val collected:Long=0,val elapsed:Int=0,val nextDue:String="-")

/** Shared by the chit list (batched across all groups) and the chit detail page (one group) so
 *  "members joined / collected so far / elapsed months / next due date" stay computed identically
 *  everywhere they're shown. */
private fun computeGroupProgress(db: AppDatabase, group: ChitGroupEntity, groupPayments: List<PaymentEntity>): GroupProgress {
    val start = listOf("dd-MMM-yyyy", "yyyy-MM-dd").firstNotNullOfOrNull { p -> runCatching { SimpleDateFormat(p, Locale.ENGLISH).parse(group.startDate) }.getOrNull() }
    val elapsed = start?.let { s ->
        val a = Calendar.getInstance().apply { time = s }
        val b = Calendar.getInstance()
        ((b.get(Calendar.YEAR) - a.get(Calendar.YEAR)) * 12 + b.get(Calendar.MONTH) - a.get(Calendar.MONTH) + 1).coerceIn(0, group.durationMonths)
    } ?: 0
    val next = start?.let { s -> SimpleDateFormat("dd MMM yy", Locale.ENGLISH).format(Calendar.getInstance().apply { time = s; add(Calendar.MONTH, elapsed.coerceAtMost((group.durationMonths - 1).coerceAtLeast(0))) }.time) } ?: "-"
    return GroupProgress(db.membershipDao().countActiveForGroupSync(group.id), groupPayments.sumOf { it.amountPaid } / 100, elapsed, next)
}

@Composable
private fun TodayWorkScreen(onBack:()->Unit,onProfile:(String)->Unit,onCollect:(String,String)->Unit) {
    val context=LocalContext.current
    val unassignedText = stringResource(R.string.pending_unassigned)
    val snapshot by produceState(initialValue=TodayWorkSnapshot(),context) { value=withContext(Dispatchers.IO){
        val db=AppDatabase.getDatabase(context)
        val today=CollectionService.todayKey()
        val dayStart=SimpleDateFormat("yyyy-MM-dd",Locale.US).parse(today)!!.time
        val financial=db.financialTransactionDao().getPostedForTimeRangeSync(dayStart, dayStart+86_400_000L)
        val members=db.memberDao().getAllMembersSync().associateBy{it.id}
        val groups=db.groupDao().getAllGroupsSync().associateBy{it.id}
        val completedRows=db.collectionReceiptDao().getSavedForTimeRangeSync(dayStart, dayStart+86_400_000L).map{r->
            val group=groups[r.groupId]
            TodayCompletedRow(r.id, r.memberId, members[r.memberId]?.name ?: r.memberId, group?.registerNo ?: group?.name ?: r.groupId, r.amountPaidPaise)
        }
        TodayWorkSnapshot(loadOperationalDues(db,unassignedText),completedRows,financial.filter{it.type=="SETTLEMENT"}.sumOf{it.amountPaise}/100,financial.filter{it.type=="DELIVERY"}.sumOf{it.amountPaise}/100)
    } }
    val todayLabel=SimpleDateFormat("dd-MMM-yy",Locale.ENGLISH).format(Date())
    val dueToday=snapshot.dues.filter{it.dueDate.equals(todayLabel,true)}
    val overdue=snapshot.dues.filter{it.overdueDays>0}
    Column(Modifier.fillMaxSize().background(MaroonBackground)){ BrandTopBar("Today's Work",back=onBack); LazyColumn(contentPadding=PaddingValues(11.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
        item{Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){WorkMetric("Due Today",dueToday.size.toString(),AccentGold,Modifier.weight(1f));WorkMetric("Overdue",overdue.size.toString(),AccentRed,Modifier.weight(1f));WorkMetric("Completed",snapshot.completed.size.toString(),AccentGreen,Modifier.weight(1f))}}
        item{Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){WorkMetric("Settlement",money(snapshot.settlements),Color(0xFF285A9B),Modifier.weight(1f));WorkMetric("Delivery",money(snapshot.deliveries),AccentGreen,Modifier.weight(1f))}}
        item{Text("Priority customers",fontSize=14.sp,fontWeight=FontWeight.Bold,modifier=Modifier.padding(top=5.dp))}
        if(snapshot.dues.isEmpty()) item{ActionEmptyState("No pending dues","All customer collections are up to date",Icons.Default.CheckCircle,null)}
        items(snapshot.dues.take(30),key={"${it.code}-${it.chit}"}){due-> Surface(Modifier.fillMaxWidth().clickable{onProfile(due.code)},shape=RoundedCornerShape(12.dp),color=Color.White,border=BorderStroke(1.dp,if(due.overdueDays>=90)AccentRed.copy(alpha=.35f) else DividerGray)){Row(Modifier.padding(10.dp),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("${due.name} • ${due.chit}",fontSize=12.sp,fontWeight=FontWeight.SemiBold);Text("${money(due.pending)} pending • ${if(due.overdueDays>0)"${due.overdueDays}d overdue" else "Due today"}",fontSize=9.sp,color=if(due.overdueDays>0)AccentRed else AccentGold);if(due.recentContactAt>0)Text("Contacted ${relativeTime(due.recentContactAt)}",fontSize=8.sp,color=TextGray)};TextButton({onCollect(due.name,due.chit)}){Text("Collect",fontSize=9.sp)}}}}
        item{Text("Today completed",fontSize=14.sp,fontWeight=FontWeight.Bold,modifier=Modifier.padding(top=5.dp))}
        if(snapshot.completed.isEmpty()) item{ActionEmptyState("No collections completed yet","Saved collections will appear here",Icons.Default.ReceiptLong,null)}
        items(snapshot.completed.take(15),key={it.id}){r->Surface(Modifier.fillMaxWidth().clickable{onProfile(r.memberId)},shape=RoundedCornerShape(10.dp),color=Color.White,border=BorderStroke(1.dp,DividerGray)){Row(Modifier.fillMaxWidth().padding(10.dp),horizontalArrangement=Arrangement.SpaceBetween){Text("${r.memberName} • ${r.chitLabel}",fontSize=10.sp);Text(money(r.amountPaise/100),fontSize=11.sp,fontWeight=FontWeight.Bold,color=AccentGreen)}}}
    }}
}

@Composable private fun WorkMetric(label:String,value:String,color:Color,modifier:Modifier)=Surface(modifier,shape=RoundedCornerShape(14.dp),color=Color.White,border=BorderStroke(1.dp,DividerGray),shadowElevation=.5.dp){Column(Modifier.padding(horizontal=10.dp,vertical=9.dp)){Text(label.uppercase(),fontSize=7.sp,color=TextGray,letterSpacing=.4.sp);Spacer(Modifier.height(3.dp));Text(value,fontSize=14.sp,fontWeight=FontWeight.Bold,color=color,maxLines=1)}}
private fun relativeTime(at:Long):String { val hours=((System.currentTimeMillis()-at)/3_600_000L).coerceAtLeast(0); return if(hours<24)"${hours}h ago" else "${hours/24}d ago" }

private data class ProfileSnapshot(val member:MemberEntity?=null,val groups:List<Triple<ChitGroupEntity,ChitMembershipEntity,Long>> = emptyList(),val payments:List<com.jothivel.chits.data.local.entity.PaymentEntity> = emptyList(),val financial:List<FinancialTransactionEntity> = emptyList())

@Composable
private fun CustomerProfileScreen(customerId:String,onBack:()->Unit,onCollect:(String,String)->Unit){
    val context=LocalContext.current
    val data by produceState(initialValue=ProfileSnapshot(),customerId){value=withContext(Dispatchers.IO){val db=AppDatabase.getDatabase(context);val member=db.memberDao().getAllMembersSync().firstOrNull{it.id==customerId};val links=db.membershipDao().getForMemberSync(customerId);ProfileSnapshot(member,links.mapNotNull{l->db.groupDao().getGroupByIdSync(l.groupId)?.let{Triple(it,l,CollectionService.calculateDuePaise(db,customerId,it.id)/100)}},db.paymentDao().getPaymentsByMemberSync(customerId),db.financialTransactionDao().getAllSync().filter{it.memberId==customerId})}}
    val member=data.member
    Column(Modifier.fillMaxSize().background(MaroonBackground)){
        BrandTopBar("Customer Profile",back=onBack)
        if(member==null){
            ActionEmptyState("Customer not found","The customer may be inactive or removed",Icons.Default.PersonOff,onBack)
        } else {
            LazyColumn(contentPadding=PaddingValues(11.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
        item{Surface(shape=RoundedCornerShape(16.dp),color=Color.White,border=BorderStroke(1.dp,DividerGray),shadowElevation=1.dp){Column(Modifier.padding(14.dp)){Row(verticalAlignment=Alignment.CenterVertically){Box(Modifier.size(42.dp).background(MaroonSurfaceLight,CircleShape),contentAlignment=Alignment.Center){Text(member.name?.take(1)?.uppercase()?:"C",color=MaroonPrimary,fontWeight=FontWeight.Bold,fontSize=16.sp)};Column(Modifier.padding(start=10.dp).weight(1f)){Text(member.name ?: "Unnamed",fontSize=15.sp,fontWeight=FontWeight.Bold);Text("${member.phone.orEmpty()} • ${member.city.orEmpty()}",fontSize=9.sp,color=TextGray);Text(member.addressLine.orEmpty(),fontSize=8.sp,color=TextGray,maxLines=1)}};Divider(Modifier.padding(vertical=7.dp));Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly){TextButton({context.startActivity(Intent(Intent.ACTION_DIAL,Uri.parse("tel:${member.phone}")))}){Icon(Icons.Default.Call,null,modifier=Modifier.size(17.dp));Text("Call",fontSize=10.sp)};TextButton({context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://wa.me/91${member.phone.orEmpty().filter(Char::isDigit).takeLast(10)}")))}){Icon(Icons.Default.Chat,null,tint=AccentGreen,modifier=Modifier.size(17.dp));Text("WhatsApp",color=AccentGreen,fontSize=10.sp)}}}}}
        item{Text("Joined chits",fontSize=13.sp,fontWeight=FontWeight.Bold)}
        if(data.groups.isEmpty())item{ActionEmptyState("No active chit","Add this customer to a chit to start collection",Icons.Default.GroupAdd,null)}
        items(data.groups,key={it.first.id}){(g,l,due)->Surface(shape=RoundedCornerShape(12.dp),color=Color.White,border=BorderStroke(1.dp,DividerGray)){Column(Modifier.padding(10.dp)){Row{Column(Modifier.weight(1f)){Text("${g.registerNo} • ${g.name}",fontSize=12.sp,fontWeight=FontWeight.SemiBold);Text("Ticket ${l.ticketNo ?: "-"} • ${money(g.chitValue.toLong()/100)}",fontSize=9.sp,color=TextGray)};Text(money(due),color=if(due>0)AccentRed else AccentGreen,fontWeight=FontWeight.Bold,fontSize=12.sp)};Button({onCollect(member.name,g.registerNo?:g.id)},enabled=due>0,modifier=Modifier.fillMaxWidth().height(36.dp),colors=ButtonDefaults.buttonColors(containerColor=MaroonPrimary)){Text(if(due>0)"Collect" else "Paid up",fontSize=10.sp)}}}}
        item{Text("Chit Passbook",fontSize=13.sp,fontWeight=FontWeight.Bold)}
        if(data.payments.isEmpty())item{ActionEmptyState("No payment history","Collections saved for this customer will appear here",Icons.Default.ReceiptLong,null)}
        else item{ChitPassbook(member.name ?: "Unnamed", data.payments)}
        item{Text("Settlement & Delivery",fontSize=13.sp,fontWeight=FontWeight.Bold)}
        if(data.financial.isEmpty())item{ActionEmptyState("No settlement or delivery","Financial entries will appear here",Icons.Default.AccountBalanceWallet,null)}
        items(data.financial.take(30),key={it.id}){f->HistoryRow("${f.type.lowercase().replaceFirstChar(Char::uppercase)} • ${f.groupId}",f.amountPaise,f.mode,f.occurredAt,f.status)}
    }
        }
    }
}

@Composable private fun HistoryRow(title:String,paise:Long,mode:String,at:Long,status:String)=Surface(shape=RoundedCornerShape(10.dp),color=Color.White,border=BorderStroke(1.dp,DividerGray)){Row(Modifier.fillMaxWidth().padding(9.dp),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(title,fontSize=10.sp,fontWeight=FontWeight.SemiBold);Text("$mode • ${SimpleDateFormat("dd MMM yy, hh:mm a",Locale.ENGLISH).format(Date(at))} • $status",fontSize=8.sp,color=TextGray)};Text(money(paise/100),fontSize=11.sp,fontWeight=FontWeight.Bold)}}

@Composable private fun ActionEmptyState(title:String,subtitle:String,icon:ImageVector,onAction:(()->Unit)?){Surface(Modifier.fillMaxWidth(),shape=RoundedCornerShape(12.dp),color=Color.White,border=BorderStroke(1.dp,DividerGray)){Row(Modifier.padding(13.dp),verticalAlignment=Alignment.CenterVertically){Icon(icon,null,tint=AccentGreen,modifier=Modifier.size(25.dp));Column(Modifier.padding(start=9.dp).weight(1f)){Text(title,fontSize=11.sp,fontWeight=FontWeight.SemiBold);Text(subtitle,fontSize=9.sp,color=TextGray)};onAction?.let{TextButton(it){Text("Open",fontSize=9.sp)}}}}}

// ═══════════════════════════════════════════════════════════════════════════════
// CHIT PASSBOOK — a customer's payment history rendered as a flip-through paper
// passbook (the physical books this business's customers grew up using) instead
// of a flat list, with a 3D page-turn swipe between pages.
// ═══════════════════════════════════════════════════════════════════════════════

private val PassbookPaper = Color(0xFFFBF3E1)
private val PassbookRule = Color(0xFFD8C9A3)
private val PassbookInk = Color(0xFF3A2E22)

@Composable
private fun ChitPassbook(customerName: String, payments: List<PaymentEntity>) {
    val rowsPerPage = 8
    val sorted = remember(payments) { payments.sortedByDescending { it.paidAt } }
    val pages = remember(sorted) { sorted.chunked(rowsPerPage) }
    var pageIndex by remember(pages) { mutableIntStateOf(0) }
    val current = pageIndex.coerceIn(0, (pages.size - 1).coerceAtLeast(0))

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        AnimatedContent(
            targetState = current,
            transitionSpec = {
                if (targetState > initialState) {
                    (slideInHorizontally { it } + fadeIn()) togetherWith (slideOutHorizontally { -it } + fadeOut())
                } else {
                    (slideInHorizontally { -it } + fadeIn()) togetherWith (slideOutHorizontally { it } + fadeOut())
                }
            },
            label = "passbookPage"
        ) { page ->
            PassbookPage(customerName, pages[page], page + 1, pages.size, rowsPerPage)
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            IconButton(onClick = { pageIndex = (current - 1).coerceAtLeast(0) }, enabled = current > 0, modifier = Modifier.size(30.dp)) {
                Icon(Icons.Default.ChevronLeft, "Previous page", tint = if (current > 0) MaroonPrimary else DividerGray)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                pages.indices.forEach { index ->
                    Box(
                        Modifier
                            .size(if (index == current) 7.dp else 5.dp)
                            .background(if (index == current) MaroonPrimary else DividerGray, CircleShape)
                    )
                }
            }
            IconButton(onClick = { pageIndex = (current + 1).coerceAtMost(pages.size - 1) }, enabled = current < pages.size - 1, modifier = Modifier.size(30.dp)) {
                Icon(Icons.Default.ChevronRight, "Next page", tint = if (current < pages.size - 1) MaroonPrimary else DividerGray)
            }
        }
    }
}

@Composable
private fun PassbookPage(customerName: String, rows: List<PaymentEntity>, pageNo: Int, pageCount: Int, rowsPerPage: Int) {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = PassbookPaper,
        shadowElevation = 4.dp,
        border = BorderStroke(1.dp, PassbookRule)
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .drawBehind {
                    // Red margin line, like a ruled ledger page
                    drawLine(Color(0xFFB33), Offset(34f, 0f), Offset(34f, size.height), strokeWidth = 1.5f)
                }
                .padding(start = 14.dp, end = 12.dp, top = 12.dp, bottom = 10.dp)
        ) {
            Text("JOTHI VEL CHITS", color = MaroonPrimary, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Text(customerName, color = PassbookInk, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth().drawBehind {
                drawLine(PassbookRule, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1.5f)
            }.padding(bottom = 5.dp)) {
                Text("DATE", color = PassbookInk.copy(alpha = .65f), fontSize = 8.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1.1f))
                Text("MODE", color = PassbookInk.copy(alpha = .65f), fontSize = 8.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(0.8f))
                Text("STATUS", color = PassbookInk.copy(alpha = .65f), fontSize = 8.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(0.9f))
                Text("AMOUNT", color = PassbookInk.copy(alpha = .65f), fontSize = 8.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
            }
            rows.forEach { p ->
                Row(
                    Modifier.fillMaxWidth().drawBehind {
                        drawLine(PassbookRule.copy(alpha = .6f), Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1.dp.toPx())
                    }.padding(vertical = 7.dp)
                ) {
                    Text(SimpleDateFormat("dd-MMM-yy", Locale.ENGLISH).format(Date(p.paidAt)), color = PassbookInk, fontSize = 10.sp, modifier = Modifier.weight(1.1f))
                    Text(p.mode, color = PassbookInk, fontSize = 10.sp, modifier = Modifier.weight(0.8f))
                    Text(p.status, color = when (p.status) { "PAID", "ADVANCE" -> AccentGreen; "PARTIAL" -> AccentGold; else -> AccentRed }, fontSize = 9.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(0.9f))
                    Text(money(p.amountPaid / 100), color = PassbookInk, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
                }
            }
            repeat((rowsPerPage - rows.size).coerceAtLeast(0)) {
                Spacer(Modifier.fillMaxWidth().height(24.dp).drawBehind {
                    drawLine(PassbookRule.copy(alpha = .35f), Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1.dp.toPx())
                })
            }
            Spacer(Modifier.height(8.dp))
            Text("Page $pageNo of $pageCount", color = PassbookInk.copy(alpha = .5f), fontSize = 8.sp, modifier = Modifier.align(Alignment.End))
        }
    }
}


/**
 * The admin PIN had no in-app way to be changed off its "1234" default before this - the setup
 * screen that would have set it is permanently disabled (isSetupMode = false in LoginActivity),
 * so every fresh install silently sat open on 1234 forever. This is the only place the PIN can
 * be changed now.
 */
@Composable
private fun ChangePinSection() {
    val context = LocalContext.current
    var showDialog by remember { mutableStateOf(false) }
    var currentPin by remember { mutableStateOf("") }
    var newPin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val changedToast = stringResource(R.string.settings_change_pin_done)

    fun close() {
        if (saving) return
        showDialog = false; currentPin = ""; newPin = ""; confirmPin = ""; error = null
    }

    MoreRow(Icons.Default.Lock, stringResource(R.string.settings_change_pin)) { showDialog = true }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = ::close,
            icon = { Icon(Icons.Default.Lock, null, tint = MaroonPrimary) },
            title = { Text(stringResource(R.string.settings_change_pin_title)) },
            text = {
                Column {
                    val pinFieldColors = OutlinedTextFieldDefaults.colors(focusedBorderColor = MaroonPrimary)
                    OutlinedTextField(
                        value = currentPin,
                        onValueChange = { if (it.length <= 4 && it.all(Char::isDigit)) currentPin = it },
                        label = { Text(stringResource(R.string.settings_change_pin_current)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        colors = pinFieldColors,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = newPin,
                        onValueChange = { if (it.length <= 4 && it.all(Char::isDigit)) newPin = it },
                        label = { Text(stringResource(R.string.settings_change_pin_new)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        colors = pinFieldColors,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = confirmPin,
                        onValueChange = { if (it.length <= 4 && it.all(Char::isDigit)) confirmPin = it },
                        label = { Text(stringResource(R.string.settings_change_pin_confirm)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        colors = pinFieldColors,
                        modifier = Modifier.fillMaxWidth()
                    )
                    error?.let { message ->
                        Spacer(Modifier.height(6.dp))
                        Text(message, color = AccentRed, fontSize = 11.sp)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (saving) return@Button
                        when {
                            currentPin.length != 4 -> error = context.getString(R.string.settings_change_pin_error_current)
                            newPin.length != 4 -> error = context.getString(R.string.settings_change_pin_error_new)
                            newPin != confirmPin -> error = context.getString(R.string.settings_change_pin_error_mismatch)
                            com.jothivel.chits.ui.settings.isWeakPin(newPin) -> error = context.getString(R.string.force_pin_weak)
                            else -> {
                                saving = true
                                scope.launch {
                                    val result = withContext(Dispatchers.IO) { com.jothivel.chits.data.firebase.AdminPin.changePin(context, currentPin, newPin) }
                                    saving = false
                                    when (result) {
                                        com.jothivel.chits.data.firebase.AdminPin.ChangeResult.Changed -> {
                                            android.widget.Toast.makeText(context, changedToast, android.widget.Toast.LENGTH_SHORT).show()
                                            close()
                                        }
                                        com.jothivel.chits.data.firebase.AdminPin.ChangeResult.WrongCurrentPin -> error = context.getString(R.string.settings_change_pin_error_wrong)
                                        com.jothivel.chits.data.firebase.AdminPin.ChangeResult.InvalidNewPin -> error = context.getString(R.string.settings_change_pin_error_new)
                                        is com.jothivel.chits.data.firebase.AdminPin.ChangeResult.CloudFailed -> error = context.getString(R.string.settings_change_pin_cloud_failed, result.message)
                                    }
                                }
                            }
                        }
                    },
                    enabled = !saving,
                    colors = ButtonDefaults.buttonColors(containerColor = MaroonPrimary)
                ) {
                    if (saving) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                    else Text(stringResource(R.string.settings_change_pin_save))
                }
            },
            dismissButton = { TextButton(onClick = ::close, enabled = !saving) { Text(stringResource(R.string.common_cancel)) } }
        )
    }
}

@Composable
private fun SettingsScreenApproved(onBack: () -> Unit, onBackup: () -> Unit, onRestore: () -> Unit, onExport: () -> Unit, onImport: () -> Unit, onLabour: () -> Unit, onLogout: () -> Unit) {
    val context=LocalContext.current;val last=remember{com.jothivel.chits.utils.AppPreferences(context).getLastBackupAt()}
    val backupText = if (last == 0L) stringResource(R.string.settings_no_backup_yet) else stringResource(R.string.settings_last_backup, relativeTime(last))
    Column(Modifier.fillMaxSize()) { BrandTopBar(stringResource(R.string.settings_title), back = onBack); LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item { LabourHighlightCard(onLabour) }
        item { ChangePinSection() }
        item { com.jothivel.chits.ui.settings.CloudAccountSection() }
        item { CloudSyncSection() }
        item { LanguageToggleRow() }
        item{Text(backupText,fontSize=10.sp,color=if(last==0L)AccentRed else AccentGreen,modifier=Modifier.padding(horizontal=5.dp,vertical=3.dp))}; item { MoreRow(Icons.Default.Backup, stringResource(R.string.settings_backup_database), onBackup) }; item { MoreRow(Icons.Default.Restore, stringResource(R.string.settings_restore_database), onRestore) }; item { MoreRow(Icons.Default.FileDownload, stringResource(R.string.settings_export_csv), onExport) }; item { MoreRow(Icons.Default.FileUpload, stringResource(R.string.settings_import_csv), onImport) }; item { MoreRow(Icons.Default.Logout, stringResource(R.string.settings_logout), onLogout) } } }
}

@Composable
private fun LabourHighlightCard(onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = MaroonPrimary
    ) {
        Row(
            Modifier.fillMaxWidth().background(MaroonGradient).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(44.dp).background(Color.White.copy(alpha = .18f), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Groups, null, tint = Color.White, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.settings_labour_title), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text(stringResource(R.string.settings_labour_subtitle), color = Color.White.copy(alpha = .82f), fontSize = 11.sp)
            }
            Icon(Icons.Default.ChevronRight, null, tint = Color.White)
        }
    }
}

/**
 * Push local data to Firestore / pull it back down. Lives here (shared by Home and Settings)
 * rather than inside the Labour screen — this affects the whole local database (chits,
 * members, payments), not just field-agent management, so it doesn't belong scoped to Labour.
 */
@Composable
private fun CloudSyncSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isSyncing by remember { mutableStateOf(false) }
    var isRestoring by remember { mutableStateOf(false) }
    val syncSuccessTemplate = stringResource(R.string.labour_sync_success)
    val syncQueuedSuffixTemplate = stringResource(R.string.labour_sync_queued_suffix)
    val syncFailedText = stringResource(R.string.labour_sync_failed)
    val restoreSuccessTemplate = stringResource(R.string.labour_restore_success)
    val restoreFailedText = stringResource(R.string.labour_restore_failed)

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.cloud_sync_section_title), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TextGray)
        Button(
            onClick = {
                if (isSyncing || isRestoring) return@Button
                isSyncing = true
                scope.launch {
                    try {
                        val result = withContext(Dispatchers.IO) { com.jothivel.chits.data.firebase.FirestoreDataSync.syncAllToCloud(context) }
                        val flushed = withContext(Dispatchers.IO) { com.jothivel.chits.data.firebase.AgentCollectionSync.flushPending(context) }
                        result.onSuccess {
                            com.jothivel.chits.utils.AppPreferences(context).setLastCloudSyncAt()
                            val suffix = if (flushed > 0) String.format(syncQueuedSuffixTemplate, flushed) else ""
                            android.widget.Toast.makeText(context, String.format(syncSuccessTemplate, it.groups, it.members, suffix), android.widget.Toast.LENGTH_LONG).show()
                        }.onFailure {
                            android.widget.Toast.makeText(context, it.message ?: syncFailedText, android.widget.Toast.LENGTH_LONG).show()
                        }
                    } finally { isSyncing = false }
                }
            },
            enabled = !isSyncing && !isRestoring,
            modifier = Modifier.fillMaxWidth().height(46.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaroonPrimary),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(Icons.Default.Sync, null, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(7.dp))
            Text(if (isSyncing) stringResource(R.string.labour_syncing) else stringResource(R.string.labour_sync_to_cloud))
        }
        Button(
            onClick = {
                if (isSyncing || isRestoring) return@Button
                isRestoring = true
                scope.launch {
                    try {
                        val result = withContext(Dispatchers.IO) { com.jothivel.chits.data.firebase.FirestoreDataSync.restoreAllFromCloud(context) }
                        result.onSuccess {
                            com.jothivel.chits.utils.AppPreferences(context).setLastCloudSyncAt()
                            android.widget.Toast.makeText(context, String.format(restoreSuccessTemplate, it.groups, it.members, it.collections), android.widget.Toast.LENGTH_LONG).show()
                        }.onFailure {
                            android.widget.Toast.makeText(context, it.message ?: restoreFailedText, android.widget.Toast.LENGTH_LONG).show()
                        }
                    } finally { isRestoring = false }
                }
            },
            enabled = !isSyncing && !isRestoring,
            modifier = Modifier.fillMaxWidth().height(46.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaroonSurfaceLight, contentColor = MaroonPrimary),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(Icons.Default.CloudDownload, null, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(7.dp))
            Text(if (isRestoring) stringResource(R.string.labour_restoring) else stringResource(R.string.labour_restore_from_cloud))
        }
    }
}

/**
 * Switches the whole app's locale (English/Tamil). The actual visible-text translation only
 * covers Home + Settings so far (a large app-wide localization effort, being rolled out screen
 * by screen) — every screen already picks up whichever strings ARE resource-backed once this
 * is toggled, since [LocaleHelper]/[BaseActivity] apply the saved language on every Activity
 * (re)start; screens not yet migrated to string resources simply stay in English until they are.
 */
@Composable
private fun LanguageToggleRow() {
    val context = LocalContext.current
    val prefs = remember { com.jothivel.chits.utils.AppPreferences(context) }
    var current by remember { mutableStateOf(prefs.getLanguage()) }

    fun select(lang: String) {
        if (lang == current) return
        prefs.setLanguage(lang)
        current = lang
        (context as? android.app.Activity)?.recreate()
    }

    Surface(shape = RoundedCornerShape(12.dp), color = Color.White, border = BorderStroke(1.dp, DividerGray.copy(alpha = .8f))) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Language, null, tint = MaroonPrimary, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.settings_language_label), Modifier.padding(start = 10.dp).weight(1f), fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(current == "en", { select("en") }, { Text(stringResource(R.string.settings_language_english)) }, Modifier.weight(1f))
                FilterChip(current == "ta", { select("ta") }, { Text(stringResource(R.string.settings_language_tamil)) }, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun MoreRow(icon: ImageVector, label: String, onClick: () -> Unit) = Surface(
    Modifier.fillMaxWidth().height(46.dp).clickable(onClick = onClick),
    shape = RoundedCornerShape(10.dp),
    color = Color.White,
    border = BorderStroke(1.dp, DividerGray.copy(alpha = .8f))
) {
    Row(Modifier.fillMaxSize().padding(horizontal = 11.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaroonPrimary, modifier = Modifier.size(18.dp))
        Text(label, Modifier.padding(start = 10.dp).weight(1f), fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Icon(Icons.Default.ChevronRight, null, tint = TextGray, modifier = Modifier.size(17.dp))
    }
}

