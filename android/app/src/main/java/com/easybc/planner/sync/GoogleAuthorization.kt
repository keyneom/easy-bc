package com.easybc.planner.sync

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

sealed interface AuthorizationStep {
    data class Authorized(val accessToken: String) : AuthorizationStep
    data class NeedsResolution(val pendingIntent: PendingIntent) : AuthorizationStep
}

class GoogleAuthorization {
    suspend fun begin(activity: Activity): AuthorizationStep {
        @Suppress("DEPRECATION")
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(DRIVE_APPDATA_SCOPE)))
            .build()
        val result = Identity.getAuthorizationClient(activity).authorize(request).await()
        return result.toStep()
    }

    fun finish(activity: Activity, data: Intent?): String {
        requireNotNull(data) { "Google authorization was cancelled." }
        val result = Identity.getAuthorizationClient(activity).getAuthorizationResultFromIntent(data)
        return result.accessToken ?: error("Google authorization did not return an access token.")
    }

    private fun AuthorizationResult.toStep(): AuthorizationStep {
        if (hasResolution()) {
            return AuthorizationStep.NeedsResolution(
                pendingIntent ?: error("Google authorization resolution was unavailable.")
            )
        }
        return AuthorizationStep.Authorized(
            accessToken ?: error("Google authorization did not return an access token.")
        )
    }
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { if (continuation.isActive) continuation.resume(it) }
    addOnFailureListener { if (continuation.isActive) continuation.resumeWithException(it) }
    addOnCanceledListener { if (continuation.isActive) continuation.cancel() }
}

