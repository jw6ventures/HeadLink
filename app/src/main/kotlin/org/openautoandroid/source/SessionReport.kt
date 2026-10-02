package org.openautoandroid.source

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.UserManager
import java.time.Instant

/**
 * Local diagnostic report shown in the activity, saved as it changes by
 * [ReportPersistence], and exported on request (spec §10).
 * Nonsecret metadata only: the accessory serial is redacted, and no raw wire data,
 * keys, or unrelated device identifiers are included. Vehicle, head-unit firmware,
 * USB policy, port, cable, and Bluetooth details are recorded by the operator in
 * docs/g1-setup-record.md; no public API reports them.
 */
object SessionReport {
    private val nativeProbe: String by lazy { NativeBridge.probe() }

    fun compose(context: Context, usb: UsbManager, filter: AccessoryFilter): String {
        val attempt = SessionController.attempt
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
        val debuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        val notifications =
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        return buildString {
            appendLine("report_format=openautoandroid-session-2")
            appendLine("generated_utc=${Instant.now()}")
            appendLine(
                "scope=USB accessory acquisition, version, TLS, authentication, encrypted service discovery, and " +
                    "the projection demonstration; a gate is passed only by the evidence named in " +
                    "docs/g1-setup-record.md and docs/g4-g5-procedure.md",
            )
            appendLine()
            appendLine("[app]")
            appendLine("application_id=${context.packageName}")
            appendLine("version_name=${packageInfo.versionName}")
            appendLine("version_code=${packageInfo.longVersionCode}")
            appendLine("debuggable=$debuggable")
            appendLine()
            appendLine("[device]")
            appendLine("manufacturer=${Build.MANUFACTURER}")
            appendLine("model=${Build.MODEL}")
            appendLine("os_display=${Build.DISPLAY}")
            appendLine("os_incremental=${Build.VERSION.INCREMENTAL}")
            appendLine("android_release=${Build.VERSION.RELEASE}")
            appendLine("sdk_int=${Build.VERSION.SDK_INT}")
            appendLine("security_patch=${Build.VERSION.SECURITY_PATCH}")
            appendLine("owner_user=${context.getSystemService(UserManager::class.java).isSystemUser}")
            appendLine("post_notifications_granted=$notifications")
            appendLine(
                "notifications_enabled=${context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()}",
            )
            appendLine()
            appendLine("[attempt]")
            appendLine("attempt_started_utc=${attempt?.startedUtc ?: "-"}")
            // False in a saved report means the app ended before the attempt finished.
            appendLine("attempt_complete=${attempt != null && SessionController.stage == Stage.IDLE}")
            appendLine("report_file=${attempt?.let(ReportPersistence::reportName) ?: "-"}")
            appendLine("report_store_error=${ReportPersistence.store?.lastError ?: "-"}")
            appendLine("stage=${SessionController.stage.name}")
            appendLine("generation=${attempt?.generation ?: "-"}")
            appendLine("session_uuid=${attempt?.sessionUuid ?: "-"}")
            appendLine("profile=${attempt?.profile ?: "-"}")
            appendLine("stop_after=${attempt?.settings?.stopAfter ?: "-"}")
            appendLine("transport=${attempt?.settings?.labSocket?.let { "lab-socket:$it" } ?: "accessory"}")
            appendLine("sensors=${attempt?.settings?.sensors ?: "-"}")
            // Says which case the run was, so evidence from a drive is unambiguous about
            // whether coordinates could be in this file at all (spec §10).
            appendLine("sensor_location=${attempt?.settings?.sensorLocation ?: "-"}")
            appendLine("software_encoder=${attempt?.settings?.softwareEncoder ?: "-"}")
            appendLine("projection_ms=${attempt?.settings?.projectionMs ?: "-"}")
            appendLine("tls_profile=${attempt?.settings?.tlsProfile ?: "-"}")
            appendLine("first_use_armed=${attempt?.settings?.armedFirstUse ?: "-"}")
            appendLine("pins_at_start=${attempt?.settings?.pins?.joinToString(",")?.ifEmpty { "-" } ?: "-"}")
            appendLine("last_completed=${SessionController.lastCompleted}")
            appendLine("final_category=${SessionController.finalCategory}")
            appendLine("final_detail=${SessionController.finalDetail}")
            appendLine("accessory_filter=$filter")
            appendLine("filter_match=${attempt?.filterMatch ?: "-"}")
            appendLine("manual_override=${attempt?.manualOverride ?: false}")
            appendLine(Accessories.describe(attempt?.accessory))
            appendLine("currently_enumerated=${Accessories.list(usb).size}")
            appendLine()
            appendLine("[trust]")
            appendLine("identity_busy=${SessionController.identityBusy}")
            appendLine(SessionController.identityReport.ifEmpty { "identity=-" }.trimEnd())
            appendLine("pinned_head_units=${IdentityWork.trust(context).pins().joinToString(",").ifEmpty { "-" }}")
            appendLine()
            appendLine("[events]")
            SessionController.events.forEach { appendLine(it) }
            appendLine()
            appendLine("[projection]")
            appendLine(SessionController.projectionReport.ifEmpty { "projection=-" }.trimEnd())
            appendLine()
            appendLine("[routing]")
            appendLine(Routing.report(context).trimEnd())
            appendLine()
            // P9: counts, minutes and booleans; never a box, a road, a reason or the key.
            appendLine("[traffic]")
            appendLine(Traffic.report().trimEnd())
            appendLine()
            appendLine("[native_session]")
            appendLine(SessionController.nativeReport.ifEmpty { "-" }.trimEnd())
            appendLine()
            appendLine("[native_probe]")
            appendLine(nativeProbe.trimEnd())
        }
    }
}
