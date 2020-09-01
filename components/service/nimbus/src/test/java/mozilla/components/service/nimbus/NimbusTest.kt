/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package mozilla.components.service.nimbus

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NimbusTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `test Nimbus initialize`() {
        Nimbus.initialize(context) { experiments ->
            assertTrue(experiments.count() > 0)
        }
    }
}
