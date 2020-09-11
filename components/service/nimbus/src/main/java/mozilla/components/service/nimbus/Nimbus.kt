/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package mozilla.components.service.nimbus

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import mozilla.components.service.glean.Glean
import mozilla.components.support.base.coroutines.Dispatchers
import mozilla.components.support.base.log.Log
import uniffi.nimbus.AppContext
import uniffi.nimbus.EnrolledExperiment
import uniffi.nimbus.Experiments
import java.io.File
import java.util.Locale

/**
 * This is the main experiments API, which is exposed through the global [Nimbus] object.
 */
open class NimbusInternalAPI internal constructor() {
    companion object {
        private const val LOG_TAG = "service/Nimbus"
        private const val collectionName = "nimbus-mobile-experiments"
        internal const val NIMBUS_DATA_DIR: String = "nimbus_data"

        /**
         * Gets a gecko-compatible locale string (e.g. "es-ES" instead of Java [Locale]
         * "es_ES") for the default locale.
         * If the locale can't be determined on the system, the value is "und",
         * to indicate "undetermined".
         *
         * This method approximates the API21 method [Locale.toLanguageTag].
         *
         * @return a locale string that supports custom injected locale/languages.
         */
        internal fun getLocaleTag(): String {
            // Thanks to toLanguageTag() being introduced in API21, we could have
            // simple returned `locale.toLanguageTag();` from this function. However
            // what kind of languages the Android build supports is up to the manufacturer
            // and our apps usually support translations for more rare languages, through
            // our custom locale injector. For this reason, we can't use `toLanguageTag`
            // and must try to replicate its logic ourselves.
            val locale = Locale.getDefault()
            val language = getLanguageFromLocale(locale)
            val country = locale.country // Can be an empty string.

            return when {
                language.isEmpty() -> "und"
                country.isEmpty() -> language
                else -> "$language-$country"
            }
        }

        /**
         * Sometimes we want just the language for a locale, not the entire language
         * tag. But Java's .getLanguage method is wrong. A reference to the deprecated
         * ISO language codes and their mapping can be found in [Locale.toLanguageTag] docs.
         *
         * @param locale a [Locale] object to be stringified.
         * @return a language string, such as "he" for the Hebrew locales.
         */
        internal fun getLanguageFromLocale(locale: Locale): String {
            // `locale.language` can, but should never be, an empty string.
            // Modernize certain language codes.
            return when (val language = locale.language) {
                "iw" -> "he"
                "in" -> "id"
                "ji" -> "yi"
                else -> language
            }
        }
    }

    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Cached)

    private lateinit var experiments: Experiments
    private lateinit var dataDir: File
    private var onExperimentUpdated: ((List<EnrolledExperiment>) -> Unit)? = null

    init {
        // Set the name of the native library
        System.setProperty("uniffi.component.nimbus.libraryOverride", "nimbus")
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
     * @return Returns a [Job] so that off-thread initialization can be monitored and interacted
     * with.
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
            experiments = Experiments(collectionName, experimentContext, dataDir.path, null)

            // Get experiments
            val activeExperiments = experiments.getActiveExperiments()

            // Record enrollments in telemetry
            recordExperimentTelemetry(activeExperiments)

            // Invoke the callback with the list of active experiments for the consuming app to
            // process.
            onExperimentUpdated?.invoke(activeExperiments)
        }
    }

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

    internal fun buildExperimentContext(context: Context): AppContext {
        var packageInfo: PackageInfo? = null
        try {
            packageInfo = context.packageManager.getPackageInfo(
                context.packageName, 0
            )
        } catch (e: PackageManager.NameNotFoundException) {
            Log.log(Log.Priority.ERROR,
                LOG_TAG,
                message = "Could not retrieve package info for appBuild and appVersion"
            )
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
                locale = getLocaleTag(),
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
                locale = getLocaleTag(),
                os = "Android",
                osVersion = Build.VERSION.RELEASE)
        }
    }
}

/**
 * The main Nimbus object.
 */
object Nimbus : NimbusInternalAPI()
