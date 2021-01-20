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

import com.squareup.moshi.JsonClass
import com.squareup.moshi.JsonReader
import okio.buffer
import okio.source
import org.matrix.android.sdk.internal.di.MoshiProvider
import java.io.File

@JsonClass(generateAdapter = false)
internal sealed class LazyRoomSync {
    data class Parsed(val _roomSync: RoomSync) : LazyRoomSync()
    data class Stored(val file: File) : LazyRoomSync()

    val roomSync: RoomSync
        get() {
            return when (this) {
                is Parsed -> _roomSync
                is Stored -> {
                    // Parse the file now
                    file.inputStream().use { pos ->
                        MoshiProvider.providesMoshi().adapter(RoomSync::class.java)
                                .fromJson(JsonReader.of(pos.source().buffer()))!!
                    }
                }
            }
        }
}
