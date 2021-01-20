/*
 * Copyright (c) 2021 New Vector Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.matrix.android.sdk.internal.session.sync.poc2

import com.squareup.moshi.JsonClass
import okio.buffer
import okio.source
import org.matrix.android.sdk.internal.di.MoshiProvider
import java.io.File

@JsonClass(generateAdapter = true)
internal data class InitialSyncStatus(
        val state: Int = STATE_INIT
) {
    companion object {
        const val STATE_INIT = 0
        const val STATE_DOWNLOADING = 1
        const val STATE_DOWNLOADED = 2
        const val STATE_PARSED = 3
        const val STATE_SUCCESS = 4
    }
}

internal interface InitialSyncStatusRepository {
    fun getStatus(): Int

    fun setStatus(status: Int)
}

/**
 * This class handle the current status of an initial sync and persist it on the disk, to be robust against crash
 */
internal class FileInitialSyncStatusRepository(directory: File) : InitialSyncStatusRepository {

    private val file = File(directory, "status.json")
    private val jsonAdapter = MoshiProvider.providesMoshi().adapter(InitialSyncStatus::class.java)

    private var cache: InitialSyncStatus? = null

    override fun getStatus(): Int {
        ensureCache()
        return cache?.state ?: InitialSyncStatus.STATE_INIT
    }

    override fun setStatus(status: Int) {
        cache = (cache ?: InitialSyncStatus()).copy(state = status)
        writeFile()
    }

    private fun ensureCache() {
        if (cache == null) readFile()
    }

    /**
     * File -> Cache
     */
    private fun readFile() {
        cache = file
                .takeIf { it.exists() }
                ?.let { jsonAdapter.fromJson(it.source().buffer()) }
                ?: InitialSyncStatus()
    }

    /**
     * Cache -> File
     */
    private fun writeFile() {
        file.delete()
        cache
                ?.let { jsonAdapter.toJson(it) }
                ?.let { file.writeText(it) }
    }
}
