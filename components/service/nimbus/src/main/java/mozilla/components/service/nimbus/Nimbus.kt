/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package mozilla.components.service.nimbus

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import mozilla.components.service.glean.Glean
import mozilla.components.support.base.coroutines.Dispatchers
import mozilla.components.support.base.log.Log
import mozilla.components.support.locale.getLocaleTag
import org.mozilla.experiments.nimbus.AppContext
import org.mozilla.experiments.nimbus.AvailableRandomizationUnits
import org.mozilla.experiments.nimbus.EnrolledExperiment
import org.mozilla.experiments.nimbus.RemoteSettingsConfig
import org.mozilla.experiments.nimbus.NimbusClient
import java.io.File
import java.util.Locale

/**
 * This is the main experiments API, which is exposed through the global [Nimbus.shared] object.
 */
open class Nimbus internal constructor() {
    companion object {
        internal const val LOG_TAG = "service/Nimbus"
        private const val EXPERIMENT_COLLECTION_NAME = "nimbus-mobile-experiments"
        internal const val NIMBUS_DATA_DIR: String = "nimbus_data"

        val shared by lazy { Nimbus() }
    }

    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Cached)

    private lateinit var nimbus: NimbusClient
    private lateinit var dataDir: File
    private var onExperimentUpdated: ((List<EnrolledExperiment>) -> Unit)? = null

    var isInitialized = false

    init {
        // Set the name of the native library
        System.setProperty("uniffi.component.nimbus.libraryOverride", "megazord")
    }

    /**
     * Initialize the Nimbus SDK library.
     *
     * This should only be initialized once by the application.
     *
     * @param context [Context] to access application and device parameters.  As we cannot enforce
     * through the compiler that the context pass to the initialize function is a Application
     * Context, there could potentially be a memory leak if the initializing application doesn't
     * comply.
     *
     * @param onExperimentUpdated callback that will be executed when the list of experiments has
     * been updated from the experiments endpoint and evaluated by the Nimbus-SDK. This is meant to
     * be used for consuming applications to perform any actions as a result of enrollment in an
     * experiment so that the application is not required to await the network request. The
     * callback will be supplied with the list of active experiments (if any) for which the client
     * is enrolled.
     */
    fun initialize(
        context: Context,
        onExperimentUpdated: ((activeExperiments: List<EnrolledExperiment>) -> Unit)? = null
    ) {
        this.onExperimentUpdated = onExperimentUpdated

        // Do initialization off of the main thread
        scope.launch {
            // Build Nimbus AppContext object to pass into initialize
            val experimentContext = buildExperimentContext(context)

            // Build a File object to represent the data directory for Nimbus data
            dataDir = File(context.applicationInfo.dataDir, NIMBUS_DATA_DIR)

            // Initialize Nimbus
            nimbus = NimbusClient(
                EXPERIMENT_COLLECTION_NAME,
                experimentContext,
                dataDir.path,
                RemoteSettingsConfig(
                    serverUrl = context.resources.getString(R.string.nimbus_default_endpoint),
                    bucketName = null
                ),
                AvailableRandomizationUnits(clientId = null)
            )

            // Get experiments
            val activeExperiments = nimbus.getActiveExperiments()

            // Record enrollments in telemetry
            recordExperimentTelemetry(activeExperiments)

            isInitialized = true

            // Invoke the callback with the list of active experiments for the consuming app to
            // process.
            onExperimentUpdated?.invoke(activeExperiments)
        }
    }

    /**
     * Get the list of currently enrolled experiments
     *
     * @return A list of [EnrolledExperiment]s
     */
    fun getActiveExperiments(): List<EnrolledExperiment> =
        if (isInitialized) { nimbus.getActiveExperiments() } else { emptyList() }

    /**
     * Get the currently enrolled branch for the given experiment
     *
     * @param experimentId The string experiment-id or "slug" for which to retrieve the branch
     *
     * @return A String representing the branch-id or "slug"
     */
    fun getExperimentBranch(experimentId: String): String? =
        if (isInitialized) { nimbus.getExperimentBranch(experimentId) } else { null }

    internal fun recordExperimentTelemetry(experiments: List<EnrolledExperiment>) {
        // Call Glean.setExperimentActive() for each active experiment.
        experiments.forEach {
            // For now, we will just record the experiment id and the branch id. Once we can call
            // Glean from the Nimbus-SDK Rust core, we will also record the targeting parameters
            // and the bucketing parameters.
            Glean.setExperimentActive(it.slug, it.branchSlug)
        }

        // Note, we cannot call setExperimentInactive unless we track the state of the
        // experiments in this component and persist the info, since Glean doesn't persist
        // experiment info, we would only have to do this in the case where we unenrolled from
        // an experiment during application runtime, and since we currently only check for
        // experiments during init, we don't currently have a case where we could detect this,
        // but since Glean doesn't persist this info either, simply not 'enrolling' the
        // experiment in Glean will be sufficient to show as 'unenrolled'.
    }

    private fun buildExperimentContext(context: Context): AppContext {
        val packageInfo: PackageInfo? = try {
            context.packageManager.getPackageInfo(
                context.packageName, 0
            )
        } catch (e: PackageManager.NameNotFoundException) {
            Log.log(Log.Priority.ERROR,
                LOG_TAG,
                message = "Could not retrieve package info for appBuild and appVersion"
            )
            null
        }

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            AppContext(
                appId = context.packageName,
                androidSdkVersion = Build.VERSION.SDK_INT.toString(),
                appBuild = packageInfo?.longVersionCode.toString(),
                appVersion = packageInfo?.versionName,
                architecture = Build.SUPPORTED_ABIS[0],
                debugTag = null,
                deviceManufacturer = Build.MANUFACTURER,
                deviceModel = Build.MODEL,
                locale = Locale.getDefault().getLocaleTag(),
                os = "Android",
                osVersion = Build.VERSION.RELEASE)
        } else {
            // ("VERSION.SDK_INT < P")
            @Suppress("DEPRECATION")
            AppContext(
                appId = context.packageName,
                androidSdkVersion = Build.VERSION.SDK_INT.toString(),
                appBuild = packageInfo?.versionCode.toString(),
                appVersion = packageInfo?.versionName,
                architecture = Build.SUPPORTED_ABIS[0],
                debugTag = null,
                deviceManufacturer = Build.MANUFACTURER,
                deviceModel = Build.MODEL,
                locale = Locale.getDefault().getLocaleTag(),
                os = "Android",
                osVersion = Build.VERSION.RELEASE)
        }
    }
}
