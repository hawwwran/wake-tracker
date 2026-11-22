package com.hawwwran.waketracker.data

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import androidx.core.net.toUri

/**
 * Whether Android lets the app work while nobody has it open.
 *
 * An app that is rarely opened is put to sleep bit by bit: its hourly job is then run a few
 * times a day, or once. The record does not suffer, because Android keeps its log for ten
 * days and any read catches up. A phone that shares its record does: the phones paired with
 * it see it as it was at the last upload. An app the owner exempted from battery
 * optimisation is left alone and keeps its hour.
 */
object Background {

    fun unrestricted(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    /** Android's own question whether the app may run unrestricted; where that is missing, the list to find it in. */
    // The exemption is what this app needs to do its job on time, which is the case the
    // permission exists for; Play's policy on asking for it does not apply to an app outside Play.
    @SuppressLint("BatteryLife")
    fun askToBeUnrestricted(context: Context) {
        try {
            context.startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:${context.packageName}".toUri())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (_: ActivityNotFoundException) {
            context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
