package com.grinch.rivo4.view.screen.onboarding

import com.grinch.rivo4.controller.util.RivoText
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.outlined.CheckCircleOutline
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Stars
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.grinch.rivo4.R
import com.grinch.rivo4.controller.permission.PermissionActionType
import com.grinch.rivo4.controller.permission.PermissionCheckItem
import com.grinch.rivo4.controller.permission.PermissionChecklistHelper
import com.grinch.rivo4.controller.util.getDefaultDialerIntent
import com.grinch.rivo4.view.theme.LocalCardRoundness
import com.grinch.rivo4.view.theme.RivoMaterialShapes
import com.grinch.rivo4.view.theme.RivoMorphShape
import com.grinch.rivo4.view.theme.RivoMotion
import com.grinch.rivo4.view.theme.rememberRivoMorph
import com.grinch.rivo4.view.theme.rememberRivoMorphShape
import com.grinch.rivo4.view.theme.rivoCornerDp

data class MorphingPage(
    val icon: ImageVector,
    @androidx.annotation.StringRes val titleRes: Int,
    @androidx.annotation.StringRes val descriptionRes: Int,
    val morphProgress: Float,
    val rotation: Float,
    val scale: Float
)

private val pages = listOf(
    MorphingPage(
        icon = Icons.Default.Palette,
        titleRes = R.string.onboarding_page1_title,
        descriptionRes = R.string.onboarding_page1_description,
        morphProgress = 0f,
        rotation = 0f,
        scale = 1f
    ),
    MorphingPage(
        icon = Icons.Default.Dialpad,
        titleRes = R.string.onboarding_page2_title,
        descriptionRes = R.string.onboarding_page2_description,
        morphProgress = 0.5f,
        rotation = 30f,
        scale = 1.15f
    ),
    MorphingPage(
        icon = Icons.Default.Security,
        titleRes = R.string.onboarding_page3_title,
        descriptionRes = R.string.onboarding_page3_description,
        morphProgress = 1f,
        rotation = 0f,
        scale = 1f
    )
)

private const val STAGE_INTRO = 0
private const val STAGE_ESSENTIAL = 1
private const val STAGE_RECOMMENDED = 2
private const val STAGE_COMPLETE = 3

@Composable
fun MorphingOnboardingScreen(onFinished: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val roundness = LocalCardRoundness.current

    var currentStage by remember { mutableIntStateOf(STAGE_INTRO) }
    var currentIntroPage by remember { mutableIntStateOf(0) }
    var refreshTrigger by remember { mutableIntStateOf(0) }

    // Observe lifecycle ON_RESUME to refresh permission checklist automatically
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshTrigger++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val essentialItems = remember(refreshTrigger) {
        PermissionChecklistHelper.getEssentialItems(context)
    }
    val recommendedItems = remember(refreshTrigger) {
        PermissionChecklistHelper.getRecommendedItems(context)
    }
    val allEssentialGranted = essentialItems.all { it.isGranted }

    // Activity result launchers for permissions & intents
    val roleLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        refreshTrigger++
    }

    val runtimeLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        refreshTrigger++
        // If essential runtime permissions were granted but default dialer is still needed, launch role intent
        if (!PermissionChecklistHelper.isDefaultDialer(context)) {
            roleLauncher.launch(getDefaultDialerIntent(context))
        }
    }

    val singleRuntimeLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        refreshTrigger++
    }

    val settingsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        refreshTrigger++
    }

    fun handlePermissionItemClick(item: PermissionCheckItem) {
        when (item.actionType) {
            PermissionActionType.ROLE_DIALER -> {
                roleLauncher.launch(getDefaultDialerIntent(context))
            }
            PermissionActionType.OVERLAY -> {
                settingsLauncher.launch(PermissionChecklistHelper.getOverlayIntent(context))
            }
            PermissionActionType.BATTERY_OPTIMIZATION -> {
                settingsLauncher.launch(PermissionChecklistHelper.getBatteryOptimizationIntent(context))
            }
            PermissionActionType.SETTINGS -> {
                settingsLauncher.launch(PermissionChecklistHelper.getAppSettingsIntent(context))
            }
            PermissionActionType.RUNTIME -> {
                singleRuntimeLauncher.launch(item.permissions.toTypedArray())
            }
        }
    }

    fun grantAllEssential() {
        val ungrantedRuntime = PermissionChecklistHelper.ESSENTIAL_RUNTIME_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (ungrantedRuntime.isNotEmpty()) {
            runtimeLauncher.launch(ungrantedRuntime.toTypedArray())
        } else if (!PermissionChecklistHelper.isDefaultDialer(context)) {
            roleLauncher.launch(getDefaultDialerIntent(context))
        }
    }

    fun enableRecommended() {
        val ungrantedRuntime = mutableListOf<String>()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            !PermissionChecklistHelper.hasNotificationPermission(context)
        ) {
            ungrantedRuntime.add(android.Manifest.permission.POST_NOTIFICATIONS)
        }

        if (ungrantedRuntime.isNotEmpty()) {
            singleRuntimeLauncher.launch(ungrantedRuntime.toTypedArray())
        } else if (!PermissionChecklistHelper.hasOverlayPermission(context)) {
            settingsLauncher.launch(PermissionChecklistHelper.getOverlayIntent(context))
        } else if (!PermissionChecklistHelper.isBatteryOptimizationIgnored(context)) {
            settingsLauncher.launch(PermissionChecklistHelper.getBatteryOptimizationIntent(context))
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        AnimatedContent(
            targetState = currentStage,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "onboardingStageTransition"
        ) { stage ->
            when (stage) {
                STAGE_INTRO -> {
                    IntroStage(
                        currentPage = currentIntroPage,
                        onPageChange = { currentIntroPage = it },
                        onNext = {
                            if (currentIntroPage < pages.size - 1) {
                                currentIntroPage++
                            } else {
                                currentStage = STAGE_ESSENTIAL
                            }
                        },
                        onSkip = { currentStage = STAGE_ESSENTIAL }
                    )
                }
                STAGE_ESSENTIAL -> {
                    EssentialPermissionsStage(
                        items = essentialItems,
                        allGranted = allEssentialGranted,
                        onItemClick = { handlePermissionItemClick(it) },
                        onGrantAll = { grantAllEssential() },
                        onContinue = { currentStage = STAGE_RECOMMENDED },
                        onBack = { currentStage = STAGE_INTRO }
                    )
                }
                STAGE_RECOMMENDED -> {
                    RecommendedPermissionsStage(
                        items = recommendedItems,
                        onItemClick = { handlePermissionItemClick(it) },
                        onEnableRecommended = { enableRecommended() },
                        onContinue = { currentStage = STAGE_COMPLETE },
                        onBack = { currentStage = STAGE_ESSENTIAL }
                    )
                }
                STAGE_COMPLETE -> {
                    SetupCompleteStage(
                        essentialItems = essentialItems,
                        recommendedItems = recommendedItems,
                        onFinish = onFinished
                    )
                }
            }
        }
    }
}

@Composable
private fun IntroStage(
    currentPage: Int,
    onPageChange: (Int) -> Unit,
    onNext: () -> Unit,
    onSkip: () -> Unit
) {
    val roundness = LocalCardRoundness.current

    val morphProgress by animateFloatAsState(
        targetValue = pages[currentPage].morphProgress,
        animationSpec = RivoMotion.shapeMorph(),
        label = "onboardingMorph"
    )
    val rotation by animateFloatAsState(
        targetValue = pages[currentPage].rotation,
        animationSpec = RivoMotion.spatialDefault(),
        label = "onboardingRotation"
    )
    val scale by animateFloatAsState(
        targetValue = pages[currentPage].scale,
        animationSpec = RivoMotion.spatialDefault(),
        label = "onboardingScale"
    )
    val shapeSize by animateDpAsState(
        targetValue = (144 * pages[currentPage].scale).dp,
        animationSpec = RivoMotion.spatialDefault(),
        label = "onboardingSize"
    )

    val heroMorph = rememberRivoMorph(RivoMaterialShapes.Circle, RivoMaterialShapes.Cookie12Sided)
    val heroShape = RivoMorphShape(heroMorph) { morphProgress }

    val bgMorph = rememberRivoMorph(RivoMaterialShapes.Cookie9Sided, RivoMaterialShapes.Circle)
    val bgShape = RivoMorphShape(bgMorph) { morphProgress }

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .size(220.dp)
                .offset(x = (-60).dp, y = 80.dp)
                .rotate(rotation * 1.5f)
                .clip(bgShape)
                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f))
        )

        Box(
            modifier = Modifier
                .size(170.dp)
                .align(Alignment.TopEnd)
                .offset(x = 60.dp, y = 180.dp)
                .rotate(-rotation)
                .clip(heroShape)
                .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f))
        )

        TextButton(
            onClick = onSkip,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(16.dp)
        ) {
            Text(
                text = stringResource(R.string.onboarding_skip),
                style = MaterialTheme.typography.labelLargeEmphasized,
                color = MaterialTheme.colorScheme.primary
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.weight(1f))

            Surface(
                modifier = Modifier
                    .size(shapeSize)
                    .rotate(rotation),
                shape = heroShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                shadowElevation = 4.dp
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = pages[currentPage].icon,
                        contentDescription = null,
                        modifier = Modifier
                            .size(60.dp)
                            .rotate(-rotation),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            Spacer(modifier = Modifier.height(48.dp))

            AnimatedContent(
                targetState = currentPage,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "onboardingTextTransition"
            ) { pageIdx ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = stringResource(pages[pageIdx].titleRes),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = stringResource(pages[pageIdx].descriptionRes),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 24.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                repeat(pages.size) { index ->
                    val isCurrent = index == currentPage
                    val indicatorWidth by animateDpAsState(
                        targetValue = if (isCurrent) 28.dp else 10.dp,
                        animationSpec = RivoMotion.shapeMorph(),
                        label = "indicatorWidth"
                    )
                    val indicatorColor = if (isCurrent) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outlineVariant
                    }

                    Box(
                        modifier = Modifier
                            .padding(horizontal = 4.dp)
                            .size(indicatorWidth, 10.dp)
                            .clip(RoundedCornerShape(rivoCornerDp(12, roundness)))
                            .background(indicatorColor)
                    )
                }
            }

            Spacer(modifier = Modifier.height(36.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (currentPage > 0) {
                    TextButton(onClick = { onPageChange(currentPage - 1) }) {
                        Text(
                            text = stringResource(R.string.action_back),
                            style = MaterialTheme.typography.labelLargeEmphasized
                        )
                    }
                } else {
                    Spacer(modifier = Modifier.width(60.dp))
                }

                Button(
                    onClick = onNext,
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    modifier = Modifier.height(52.dp)
                ) {
                    Text(
                        text = if (currentPage == pages.size - 1) RivoText.get(com.grinch.rivo4.R.string.ui_set_up_permissions_461) else stringResource(R.string.onboarding_next),
                        style = MaterialTheme.typography.labelLargeEmphasized,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun EssentialPermissionsStage(
    items: List<PermissionCheckItem>,
    allGranted: Boolean,
    onItemClick: (PermissionCheckItem) -> Unit,
    onGrantAll: () -> Unit,
    onContinue: () -> Unit,
    onBack: () -> Unit
) {
    val roundness = LocalCardRoundness.current
    val grantedCount = items.count { it.isGranted }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(horizontal = 24.dp, vertical = 16.dp)
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(modifier = Modifier.height(12.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(48.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Outlined.Shield,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text(
                        text = RivoText.get(com.grinch.rivo4.R.string.ui_essential_permissions_206),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = RivoText.get(com.grinch.rivo4.R.string.ui_stage_1_of_2_of_granted_462, (grantedCount).toString(), (items.size).toString()),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (allGranted) Color(0xFF386A20) else MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = RivoText.get(com.grinch.rivo4.R.string.ui_to_make_and_receive_calls_display_caller_names_and_organize_yo_463),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(20.dp))

            PermissionsChecklistCard(
                items = items,
                onItemClick = onItemClick
            )

            Spacer(modifier = Modifier.height(24.dp))
        }

        // Action controls
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (allGranted) {
                Button(
                    onClick = onContinue,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(rivoCornerDp(24, roundness)),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text(
                        text = RivoText.get(com.grinch.rivo4.R.string.ui_continue_to_features_464),
                        style = MaterialTheme.typography.labelLargeEmphasized
                    )
                }
            } else {
                Button(
                    onClick = onGrantAll,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(rivoCornerDp(24, roundness)),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text(
                        text = RivoText.get(com.grinch.rivo4.R.string.ui_grant_all_essential_465),
                        style = MaterialTheme.typography.labelLargeEmphasized
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onBack) {
                    Text(
                        text = stringResource(R.string.action_back),
                        style = MaterialTheme.typography.labelLargeEmphasized
                    )
                }

                if (!allGranted) {
                    TextButton(onClick = onContinue) {
                        Text(
                            text = RivoText.get(com.grinch.rivo4.R.string.ui_skip_for_now_466),
                            style = MaterialTheme.typography.labelLargeEmphasized,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RecommendedPermissionsStage(
    items: List<PermissionCheckItem>,
    onItemClick: (PermissionCheckItem) -> Unit,
    onEnableRecommended: () -> Unit,
    onContinue: () -> Unit,
    onBack: () -> Unit
) {
    val roundness = LocalCardRoundness.current
    val grantedCount = items.count { it.isGranted }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(horizontal = 24.dp, vertical = 16.dp)
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(modifier = Modifier.height(12.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.size(48.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Outlined.Stars,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text(
                        text = RivoText.get(com.grinch.rivo4.R.string.ui_recommended_features_467),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = RivoText.get(com.grinch.rivo4.R.string.ui_stage_2_of_2_of_enabled_468, (grantedCount).toString(), (items.size).toString()),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.secondary,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = RivoText.get(com.grinch.rivo4.R.string.ui_these_optional_enhancements_enable_floating_ongoing_call_contr_469),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(20.dp))

            PermissionsChecklistCard(
                items = items,
                onItemClick = onItemClick
            )

            Spacer(modifier = Modifier.height(24.dp))
        }

        // Action controls
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = onContinue,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(rivoCornerDp(24, roundness)),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Text(
                    text = RivoText.get(com.grinch.rivo4.R.string.ui_finish_setup_470),
                    style = MaterialTheme.typography.labelLargeEmphasized
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onBack) {
                    Text(
                        text = stringResource(R.string.action_back),
                        style = MaterialTheme.typography.labelLargeEmphasized
                    )
                }

                TextButton(onClick = onEnableRecommended) {
                    Text(
                        text = RivoText.get(com.grinch.rivo4.R.string.ui_enable_recommended_471),
                        style = MaterialTheme.typography.labelLargeEmphasized,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@Composable
private fun SetupCompleteStage(
    essentialItems: List<PermissionCheckItem>,
    recommendedItems: List<PermissionCheckItem>,
    onFinish: () -> Unit
) {
    val roundness = LocalCardRoundness.current
    val totalGranted = essentialItems.count { it.isGranted } + recommendedItems.count { it.isGranted }
    val totalItems = essentialItems.size + recommendedItems.size

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Spacer(modifier = Modifier.weight(1f))

        Surface(
            modifier = Modifier.size(120.dp),
            shape = CircleShape,
            color = Color(0xFF386A20),
            shadowElevation = 4.dp
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = Color.White
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = RivoText.get(com.grinch.rivo4.R.string.ui_you_re_all_set_472),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = RivoText.get(com.grinch.rivo4.R.string.ui_rivo_phone_is_ready_you_have_configured_of_features_you_can_al_473, (totalGranted).toString(), (totalItems).toString()),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        Spacer(modifier = Modifier.weight(1f))

        Button(
            onClick = onFinish,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(rivoCornerDp(24, roundness)),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            )
        ) {
            Text(
                text = RivoText.get(com.grinch.rivo4.R.string.ui_start_using_rivo_474),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}
