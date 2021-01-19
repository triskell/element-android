/*
 * Copyright (c) 2021 The Matrix.org Foundation C.I.C.
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

package org.matrix.android.sdk.internal.session.sync.model

import com.squareup.moshi.FromJson
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import com.squareup.moshi.ToJson
import org.matrix.android.sdk.internal.di.MoshiProvider
import timber.log.Timber
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

internal class LazyRoomSyncJsonAdapter : JsonAdapter<LazyRoomSync>() {

    @FromJson
    override fun fromJson(reader: JsonReader): LazyRoomSync {
        Timber.w("INIT_SYNC deal with room ${reader.path}")

        return if (workingDirectory != null) {
            // Copy the source to a file
            val file = createFile()
            file.outputStream().use { outputStream ->
                reader.nextSource().inputStream().use {
                    it.copyTo(outputStream)
                }
            }
            LazyRoomSync.Stored(file)
        } else {
            // Parse it now
            val roomSync = MoshiProvider.providesMoshi().adapter(RoomSync::class.java).fromJson(reader)!!
            LazyRoomSync.Parsed(roomSync)
        }
    }

    @ToJson
    override fun toJson(writer: JsonWriter, value: LazyRoomSync?) {
        // This Adapter is not supposed to serialize object
        throw UnsupportedOperationException()
    }

    companion object {
        fun initWith(file: File) {
            workingDirectory = file.parentFile
            atomicInteger.set(0)
        }

        fun reset() {
            workingDirectory = null
        }

        private fun createFile(): File {
            val parent = workingDirectory ?: error("workingDirectory is not initialized")
            val index = atomicInteger.getAndIncrement()

            return File(parent, "room_$index.json")
        }

        private var workingDirectory: File? = null
        private val atomicInteger = AtomicInteger(0)
    }
}
