package com.grinch.rivo4.controller.permission

import com.grinch.rivo4.controller.util.RivoText
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.Contacts
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.PictureInPicture
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.core.content.ContextCompat
import com.grinch.rivo4.controller.util.getDefaultDialerIntent
import com.grinch.rivo4.controller.util.isAlreadyDefaultDialer

enum class PermissionActionType {
    RUNTIME,
    ROLE_DIALER,
    OVERLAY,
    BATTERY_OPTIMIZATION,
    SETTINGS
}

data class PermissionCheckItem(
    val id: String,
    val title: String,
    val description: String,
    val icon: ImageVector,
    val isGranted: Boolean,
    val isEssential: Boolean,
    val actionType: PermissionActionType,
    val permissions: List<String> = emptyList()
)

object PermissionChecklistHelper {

    val ESSENTIAL_RUNTIME_PERMISSIONS = mutableListOf(
        Manifest.permission.CALL_PHONE,
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.WRITE_CONTACTS,
        Manifest.permission.READ_CALL_LOG,
        Manifest.permission.WRITE_CALL_LOG
    ).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            add(Manifest.permission.ANSWER_PHONE_CALLS)
        }
    }.toTypedArray()

    fun isDefaultDialer(context: Context): Boolean {
        return isAlreadyDefaultDialer(context)
    }

    fun hasPhonePermission(context: Context): Boolean {
        val callPhone = ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        val readPhoneState = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
        val answerCalls = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED
        } else true
        return callPhone && readPhoneState && answerCalls
    }

    fun hasContactsPermission(context: Context): Boolean {
        val readContacts = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
        val writeContacts = ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CONTACTS) == PackageManager.PERMISSION_GRANTED
        return readContacts && writeContacts
    }

    fun hasCallLogPermission(context: Context): Boolean {
        val readLogs = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED
        val writeLogs = ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALL_LOG) == PackageManager.PERMISSION_GRANTED
        return readLogs && writeLogs
    }

    fun hasNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    fun hasOverlayPermission(context: Context): Boolean {
        return Settings.canDrawOverlays(context)
    }

    fun isBatteryOptimizationIgnored(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }



    fun areAllEssentialGranted(context: Context): Boolean {
        return isDefaultDialer(context) &&
                hasPhonePermission(context) &&
                hasContactsPermission(context) &&
                hasCallLogPermission(context)
    }

    fun getEssentialItems(context: Context): List<PermissionCheckItem> {
        val phonePermissions = mutableListOf(
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_PHONE_STATE
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                add(Manifest.permission.ANSWER_PHONE_CALLS)
            }
        }

        return listOf(
            PermissionCheckItem(
                id = "default_dialer",
                title = RivoText.get(com.grinch.rivo4.R.string.ui_default_phone_app_72),
                description = RivoText.get(com.grinch.rivo4.R.string.ui_required_to_answer_calls_handle_incoming_phone_calls_and_manag_73),
                icon = Icons.Outlined.VerifiedUser,
                isGranted = isDefaultDialer(context),
                isEssential = true,
                actionType = PermissionActionType.ROLE_DIALER
            ),
            PermissionCheckItem(
                id = "phone_state",
                title = RivoText.get(com.grinch.rivo4.R.string.ui_phone_sim_access_74),
                description = RivoText.get(com.grinch.rivo4.R.string.ui_required_to_place_calls_directly_manage_dual_sim_cards_and_det_75),
                icon = Icons.Outlined.Call,
                isGranted = hasPhonePermission(context),
                isEssential = true,
                actionType = PermissionActionType.RUNTIME,
                permissions = phonePermissions
            ),
            PermissionCheckItem(
                id = "contacts",
                title = RivoText.get(com.grinch.rivo4.R.string.ui_contacts_76),
                description = RivoText.get(com.grinch.rivo4.R.string.ui_required_to_display_contact_names_show_caller_info_and_search__77),
                icon = Icons.Outlined.Contacts,
                isGranted = hasContactsPermission(context),
                isEssential = true,
                actionType = PermissionActionType.RUNTIME,
                permissions = listOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)
            ),
            PermissionCheckItem(
                id = "call_log",
                title = RivoText.get(com.grinch.rivo4.R.string.ui_call_history_78),
                description = RivoText.get(com.grinch.rivo4.R.string.ui_required_to_display_your_incoming_outgoing_and_missed_call_his_79),
                icon = Icons.Outlined.History,
                isGranted = hasCallLogPermission(context),
                isEssential = true,
                actionType = PermissionActionType.RUNTIME,
                permissions = listOf(Manifest.permission.READ_CALL_LOG, Manifest.permission.WRITE_CALL_LOG)
            )
        )
    }

    fun getRecommendedItems(context: Context): List<PermissionCheckItem> {
        val items = mutableListOf<PermissionCheckItem>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            items.add(
                PermissionCheckItem(
                    id = "notifications",
                    title = RivoText.get(com.grinch.rivo4.R.string.ui_call_notifications_80),
                    description = RivoText.get(com.grinch.rivo4.R.string.ui_show_incoming_call_banners_active_call_status_and_missed_call__81),
                    icon = Icons.Outlined.Notifications,
                    isGranted = hasNotificationPermission(context),
                    isEssential = false,
                    actionType = PermissionActionType.RUNTIME,
                    permissions = listOf(Manifest.permission.POST_NOTIFICATIONS)
                )
            )
        }

        items.add(
            PermissionCheckItem(
                id = "overlay",
                title = RivoText.get(com.grinch.rivo4.R.string.ui_floating_call_bubble_64),
                description = RivoText.get(com.grinch.rivo4.R.string.ui_multitask_while_on_a_call_with_a_floating_pill_overlay_with_mu_82),
                icon = Icons.Outlined.PictureInPicture,
                isGranted = hasOverlayPermission(context),
                isEssential = false,
                actionType = PermissionActionType.OVERLAY
            )
        )

        items.add(
            PermissionCheckItem(
                id = "battery",
                title = RivoText.get(com.grinch.rivo4.R.string.ui_reliable_background_calls_83),
                description = RivoText.get(com.grinch.rivo4.R.string.ui_prevents_system_battery_saver_from_suppressing_incoming_calls__84),
                icon = Icons.Outlined.BatteryChargingFull,
                isGranted = isBatteryOptimizationIgnored(context),
                isEssential = false,
                actionType = PermissionActionType.BATTERY_OPTIMIZATION
            )
        )

        // Recording authorization belongs to Shizuku in Call recording settings.

        return items
    }

    fun getOverlayIntent(context: Context): Intent {
        return Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        )
    }

    fun getBatteryOptimizationIntent(context: Context): Intent {
        val directRequest = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${context.packageName}")
        )
        if (directRequest.resolveActivity(context.packageManager) != null) {
            return directRequest
        }

        // Some OEM builds omit the per-app consent activity. In that case, keep
        // the button useful by opening the system battery-optimization list.
        return Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
    }

    fun getAppSettingsIntent(context: Context): Intent {
        return Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
        }
    }
}
