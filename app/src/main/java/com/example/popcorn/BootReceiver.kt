package com.example.popcorn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Starts Free Popcorn when the machine powers on (the vendor app starts itself the same way). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val a = intent.action
        if (a == Intent.ACTION_BOOT_COMPLETED || a == "android.intent.action.QUICKBOOT_POWERON") {
            ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
