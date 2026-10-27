package com.mentra.bluetoothsdk.controllers

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Hands keys from the active [HidRing] to it, in the background, and keeps them from also acting on the phone.
 * Every other key passes through untouched. The user turns this on once in Android's Accessibility settings.
 */
class RingKeyService : AccessibilityService() {
    companion object {
        fun isEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            val self = ComponentName(context, RingKeyService::class.java)
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == self }
        }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean = HidRing.active?.onKey(event) ?: false

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}
}
