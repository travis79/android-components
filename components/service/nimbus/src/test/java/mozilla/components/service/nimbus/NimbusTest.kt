/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package mozilla.components.service.nimbus

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import mozilla.components.concept.fetch.Client
import mozilla.components.concept.fetch.Response
import mozilla.components.service.glean.Glean
import mozilla.components.service.glean.config.Configuration
import mozilla.components.service.glean.net.ConceptFetchHttpUploader
import mozilla.components.service.glean.testing.GleanTestRule
import mozilla.components.service.nimbus.NimbusInternalAPI.Companion.getLanguageFromLocale
import mozilla.components.service.nimbus.NimbusInternalAPI.Companion.getLocaleTag
import mozilla.components.support.test.any
import mozilla.components.support.test.mock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.`when`
import uniffi.nimbus.EnrolledExperiment
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class NimbusTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @get:Rule
    val gleanRule = GleanTestRule(context)

    @Test
    fun `getLanguageTag() reports the tag for the default locale`() {
        val defaultLanguageTag = getLocaleTag()

        assertNotNull(defaultLanguageTag)
        assertFalse(defaultLanguageTag.isEmpty())
        assertEquals("en-US", defaultLanguageTag)
    }

    @Test
    fun `getLanguageTag reports the correct tag for a non-default language`() {
        val defaultLocale = Locale.getDefault()

        try {
            Locale.setDefault(Locale("fy", "NL"))

            val languageTag = getLocaleTag()

            assertNotNull(languageTag)
            assertFalse(languageTag.isEmpty())
            assertEquals("fy-NL", languageTag)
        } finally {
            Locale.setDefault(defaultLocale)
        }
    }

    @Test
    fun `getLanguage reports the modern translation for some languages`() {
        assertEquals("he", getLanguageFromLocale(Locale("iw", "IL")))
        assertEquals("id", getLanguageFromLocale(Locale("in", "ID")))
        assertEquals("yi", getLanguageFromLocale(Locale("ji", "ID")))
    }

    @Test
    fun `recordExperimentTelemetry correctly records the experiment and branch`() {
        // Glean needs to be initialized for the experiments API to accept enrollment events, so we
        // init it with a mock client so we don't upload anything.
        val mockClient: Client = mock()
        `when`(mockClient.fetch(any())).thenReturn(
            Response("URL", 200, mock(), mock()))
        Glean.initialize(
            context,
            true,
            Configuration(
                httpClient = ConceptFetchHttpUploader(lazy { mockClient })
            )
        )

        // Create a list of experiments to test the telemetry enrollment recording
        val enrolledExperiments = listOf(EnrolledExperiment(
            slug = "test-experiment",
            branchSlug = "test-branch",
            userFacingDescription = "A test experiment for testing experiments",
            userFacingName = "Test Experiment"))

        Nimbus.recordExperimentTelemetry(experiments = enrolledExperiments)
        assertTrue(Glean.testIsExperimentActive("test-experiment"))
        val experimentData = Glean.testGetExperimentData("test-experiment")
        assertEquals("test-branch", experimentData.branch)
    }
}
