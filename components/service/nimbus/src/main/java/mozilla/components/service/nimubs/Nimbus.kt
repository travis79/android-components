/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package mozilla.components.service.nimubs

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import mozilla.components.support.base.coroutines.Dispatchers

/**
 * This is the main experiments API, which is exposed through the global [Nimbus] object.
 */
open class NimbusInternalAPI internal constructor() {
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Cached)

    fun initialize(onExperimentUpdated: (() -> Unit)? = null) {
        // Do initialization off of the main thread
        scope.launch {
            // TODO build Nimbus AppContext
            // TODO init Nimbus
            // TODO wait for experiments to update
            // TODO get experiments
            onExperimentUpdated?.invoke(/* TODO call with experiments list */)
            // TODO Glean.setExperimentActive() for each active experiment
            // Note, we cannot call setExperimentInactive unless we track the state of the
            // experiments in this component and persist the info, since Glean doesn't persist
            // experiment info, we would only have to do this in the case where we unenrolled from
            // an experiment during application runtime.
        }
    }
}

/**
 * The main Nimbus object.
 */
object Nimbus : NimbusInternalAPI() {

}
