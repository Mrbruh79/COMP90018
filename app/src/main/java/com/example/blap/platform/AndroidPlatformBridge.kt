package com.example.blap.platform

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import androidx.lifecycle.lifecycleScope
import com.example.blap.NearbyPermissions
import com.example.blap.VenuePermissions
import com.example.blap.application.ApplicationViewModel
import com.example.blap.application.PermissionSnapshot
import com.example.blap.application.PlatformAction
import com.example.blap.application.PlatformRequest
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Activity-bound launchers only. No account, navigation or permission-flow decisions live here. */
class AndroidPlatformBridge(
    private val activity: ComponentActivity,
    private val model: ApplicationViewModel,
    savedState: Bundle?,
) {
    private val launchIds = PlatformAction.entries.associateWith {
        savedState?.getLong("platform_${it.name}", -1L) ?: -1L
    }.toMutableMap()

    private val nearby = activity.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        reportPermission(PlatformAction.NEARBY_PERMISSION, NearbyPermissions.missing(activity))
    }
    private val location = activity.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        reportPermission(PlatformAction.LOCATION_PERMISSION, VenuePermissions.missing(activity))
    }
    private val contacts = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        reportPermission(PlatformAction.CONTACTS_PERMISSION, if (granted) emptyList() else listOf(Manifest.permission.READ_CONTACTS))
    }
    private val microphone = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        reportPermission(PlatformAction.MICROPHONE_PERMISSION, if (granted) emptyList() else listOf(Manifest.permission.RECORD_AUDIO))
    }
    private val notifications = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val missing = if (!granted && Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()
        reportPermission(PlatformAction.NOTIFICATION_PERMISSION, missing)
    }

    fun saveState(bundle: Bundle) {
        launchIds.forEach { (action, id) -> bundle.putLong("platform_${action.name}", id) }
    }

    fun permissionSnapshot() = PermissionSnapshot(
        missingNearby = NearbyPermissions.missing(activity),
        notifications = Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS),
        microphone = granted(Manifest.permission.RECORD_AUDIO),
    )

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(activity, permission) == PackageManager.PERMISSION_GRANTED

    private fun reportPermission(action: PlatformAction, missing: List<String>) {
        val id = launchIds[action] ?: return
        launchIds[action] = -1L
        model.permissionResult(id, missing)
    }

    fun launch(request: PlatformRequest) {
        if (!model.claimRequest(request.id)) return
        launchIds[request.action] = request.id
        try {
            when (request.action) {
                PlatformAction.NEARBY_PERMISSION -> {
                    val missing = NearbyPermissions.missing(activity)
                    if (missing.isEmpty()) reportPermission(request.action, missing) else nearby.launch(missing.toTypedArray())
                }
                PlatformAction.LOCATION_PERMISSION -> {
                    val missing = VenuePermissions.missing(activity)
                    if (missing.isEmpty()) reportPermission(request.action, missing) else location.launch(missing.toTypedArray())
                }
                PlatformAction.CONTACTS_PERMISSION -> if (granted(Manifest.permission.READ_CONTACTS))
                    reportPermission(request.action, emptyList()) else contacts.launch(Manifest.permission.READ_CONTACTS)
                PlatformAction.MICROPHONE_PERMISSION -> if (granted(Manifest.permission.RECORD_AUDIO))
                    reportPermission(request.action, emptyList()) else microphone.launch(Manifest.permission.RECORD_AUDIO)
                PlatformAction.NOTIFICATION_PERMISSION -> if (Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS))
                    reportPermission(request.action, emptyList()) else notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                PlatformAction.CONTACT_QR, PlatformAction.EVENT_QR -> scanQr(request)
                PlatformAction.GOOGLE_SIGN_IN -> signInWithGoogle(request)
                PlatformAction.APP_SETTINGS -> {
                    activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", activity.packageName, null)))
                    model.platformFinished(request.id)
                }
            }
        } catch (error: Exception) {
            model.platformFinished(request.id, error.localizedMessage ?: "This device could not open the requested feature.")
        }
    }

    private fun scanQr(request: PlatformRequest) {
        val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .enableAutoZoom().build()
        // Task listeners capture only the retained model, not the old Activity after rotation.
        val receiver = model
        GmsBarcodeScanning.getClient(activity, options).startScan()
            .addOnSuccessListener { receiver.qrResult(request.id, it.rawValue) }
            .addOnCanceledListener { receiver.qrResult(request.id, null, cancelled = true) }
            .addOnFailureListener { error ->
                val cancelled = (error as? MlKitException)?.errorCode == MlKitException.CODE_SCANNER_CANCELLED
                receiver.qrResult(request.id, null,
                    if (cancelled) null else error.message ?: "The QR code could not be scanned.", cancelled)
            }
    }

    private fun signInWithGoogle(request: PlatformRequest) {
        val resourceId = activity.resources.getIdentifier("default_web_client_id", "string", activity.packageName)
        if (resourceId == 0) {
            model.googleResult(request.id, error = "Google sign-in needs an updated Firebase config with a Web OAuth client.")
            return
        }
        val clientId = activity.getString(resourceId)
        activity.lifecycleScope.launch {
            try {
                val option = GetSignInWithGoogleOption.Builder(clientId).build()
                val result = CredentialManager.create(activity).getCredential(activity,
                    GetCredentialRequest.Builder().addCredentialOption(option).build())
                val credential = result.credential
                if (credential !is CustomCredential || credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL)
                    model.googleResult(request.id)
                else model.googleResult(request.id, token = GoogleIdTokenCredential.createFrom(credential.data).idToken)
            } catch (_: GetCredentialCancellationException) {
                model.googleResult(request.id, cancelled = true)
            } catch (_: NoCredentialException) {
                model.googleResult(request.id, error = "No Google account is available. Add one in device Settings, then try again.")
            } catch (error: CancellationException) {
                model.retryRequest(request.id)
                throw error
            } catch (error: Exception) {
                model.googleResult(request.id, error = error.localizedMessage ?: "Google sign-in failed.")
            }
        }
    }

    suspend fun clearCredentialState() {
        try { CredentialManager.create(activity).clearCredentialState(ClearCredentialStateRequest()) }
        catch (error: CancellationException) { throw error }
        catch (_: Exception) { /* Local sign-out must still finish if no credential provider is available. */ }
    }
}
