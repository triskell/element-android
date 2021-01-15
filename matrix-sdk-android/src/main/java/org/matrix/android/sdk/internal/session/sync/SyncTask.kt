/*
 * Copyright 2020 The Matrix.org Foundation C.I.C.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.matrix.android.sdk.internal.session.sync

import org.matrix.android.sdk.R
import org.matrix.android.sdk.internal.di.MoshiProvider
import org.matrix.android.sdk.internal.di.SessionFilesDirectory
import org.matrix.android.sdk.internal.di.UserId
import org.matrix.android.sdk.internal.network.GlobalErrorReceiver
import org.matrix.android.sdk.internal.network.TimeOutInterceptor
import org.matrix.android.sdk.internal.network.executeRequest
import org.matrix.android.sdk.internal.network.toFailure
import org.matrix.android.sdk.internal.session.DefaultInitialSyncProgressService
import org.matrix.android.sdk.internal.session.filter.FilterRepository
import org.matrix.android.sdk.internal.session.homeserver.GetHomeServerCapabilitiesTask
import org.matrix.android.sdk.internal.session.sync.model.SyncResponse
import org.matrix.android.sdk.internal.session.user.UserStore
import org.matrix.android.sdk.internal.task.Task
import org.matrix.android.sdk.internal.util.firstIndexOf
import retrofit2.awaitResponse
import timber.log.Timber
import java.io.File
import java.io.RandomAccessFile
import javax.inject.Inject
import kotlin.math.min

internal interface SyncTask : Task<SyncTask.Params, Unit> {

    data class Params(
            val timeout: Long,
            val presence: SyncPresence?
    )
}

internal class DefaultSyncTask @Inject constructor(
        private val syncAPI: SyncAPI,
        @UserId private val userId: String,
        private val filterRepository: FilterRepository,
        private val syncResponseHandler: SyncResponseHandler,
        private val initialSyncProgressService: DefaultInitialSyncProgressService,
        private val syncTokenStore: SyncTokenStore,
        private val getHomeServerCapabilitiesTask: GetHomeServerCapabilitiesTask,
        private val userStore: UserStore,
        private val syncTaskSequencer: SyncTaskSequencer,
        private val globalErrorReceiver: GlobalErrorReceiver,
        @SessionFilesDirectory
        private val fileDirectory: File
) : SyncTask {

    override suspend fun execute(params: SyncTask.Params) = syncTaskSequencer.post {
        doSync(params)
    }

    private suspend fun doSync(params: SyncTask.Params) {
        Timber.v("Sync task started on Thread: ${Thread.currentThread().name}")

        val requestParams = HashMap<String, String>()
        var timeout = 0L
        val token = syncTokenStore.getLastToken()
        if (token != null) {
            requestParams["since"] = token
            timeout = params.timeout
        }
        requestParams["timeout"] = timeout.toString()
        requestParams["filter"] = filterRepository.getFilter()
        params.presence?.let { requestParams["set_presence"] = it.value }

        val isInitialSync = token == null
        if (isInitialSync) {
            // We might want to get the user information in parallel too
            userStore.createOrUpdate(userId)
            initialSyncProgressService.endAll()
            initialSyncProgressService.startTask(R.string.initial_sync_start_importing_account, 100)
        }
        // Maybe refresh the home server capabilities data we know
        getHomeServerCapabilitiesTask.execute(Unit)

        val readTimeOut = (params.timeout + TIMEOUT_MARGIN).coerceAtLeast(TimeOutInterceptor.DEFAULT_LONG_TIMEOUT)

        if (isInitialSync) {
            safeInitialSync(requestParams)
            initialSyncProgressService.endAll()
        } else {
            val syncResponse = executeRequest<SyncResponse>(globalErrorReceiver) {
                apiCall = syncAPI.sync(
                        params = requestParams,
                        readTimeOut = readTimeOut
                )
            }
            syncResponseHandler.handleResponse(syncResponse, token, null)
        }
        Timber.v("Sync task finished on Thread: ${Thread.currentThread().name}")
    }

    private val workingDir = File(fileDirectory, "is")

    private suspend fun safeInitialSync(requestParams: Map<String, String>) {
        Timber.v("INIT_SYNC safeInitialSync()")
        workingDir.mkdirs()
        val workingFile = File(workingDir, "initSync.json")

        if (workingFile.exists()) {
            // Go directly to the parse step
            Timber.v("INIT_SYNC file is already here")
        } else {
            val syncResponse = syncAPI.syncStream(
                    params = requestParams
            ).awaitResponse()

            if (syncResponse.isSuccessful) {
                Timber.v("INIT_SYNC request successful, download and save to file")
                syncResponse.body()?.byteStream()?.use { inputStream ->
                    workingFile.outputStream().use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
                Timber.v("INIT_SYNC safeInitialSync() end copy")
            } else {
                // Loop for timeout will be handled by the caller
                throw syncResponse.toFailure(globalErrorReceiver)
            }
        }
        handleSyncFile(workingFile)
    }

    private suspend fun handleSyncFile(workingFile: File) {
        val syncResponseLength = workingFile.length().toInt()

        Timber.v("INIT_SYNC handleSyncFile() file size $syncResponseLength bytes")

        if (syncResponseLength < MAX_SYNC_FILE_SIZE) {
            // OK, no need to split just handle as a regular sync response
            Timber.v("INIT_SYNC no need to split")
            handleTheWholeResponse(workingFile)
        } else {
            Timber.v("INIT_SYNC Split into several smaller files")
            // This file will contain the initial sync without the joined rooms
            val everythingElseFile = File(workingDir, "initSyncSimple.json")
            // Those files will contain the joined rooms
            val joinedRoomsFiles = RandomAccessFile(workingFile, "r").use { raf ->
                // Search beginning of join room
                val buffer = ByteArray(BUFFER_SIZE)

                // Create at least 2 new files, some with the joined rooms and one with everything else
                val joinRoomsBoundaries = searchJoinRoomsBoundaries(raf, syncResponseLength, buffer)

                if (joinRoomsBoundaries.isValid()) {
                    createFirstFile(raf, joinRoomsBoundaries, syncResponseLength, everythingElseFile, buffer)

                    // Joined rooms files
                    val joinRoomContentIndexes = Range(
                            joinRoomsBoundaries.indexStart + """"join":{""".length,
                            joinRoomsBoundaries.indexEnd - 1
                    )
                    // Search for indexes to split in smaller files
                    val indexes = getRoomIndexes(raf, joinRoomContentIndexes, buffer)

                    // OK We now have an list of separator indexes, map that to a list of files, and copy content to the file
                    indexes
                            .take(indexes.size - 1)
                            .mapIndexed { index, startPosition ->
                                val endPosition = indexes[index + 1] - 1
                                File(workingDir, "rooms_$index.json")
                                        .also { copyPartialJoinRoomsToFile(raf, startPosition, endPosition, it, buffer) }
                            }
                            .onEach { Timber.v("INIT_SYNC file ${it.name}, size ${it.length()} bytes") }
                } else {
                    // Should not happen, handle the whole file...
                    handleTheWholeResponse(workingFile)
                    // Delete all files
                    workingDir.deleteRecursively()
                    return
                }
            }

            // Check that we haven't lost anything during the split
            checkFileSizes(everythingElseFile, joinedRoomsFiles, syncResponseLength)

            // We can delete the first file now
            workingFile.delete()

            // OK, lets read and parse the main file
            Timber.v("INIT_SYNC handle main file")
            val syncResponse = everythingElseFile.readText(Charsets.UTF_8)
                    .let { MoshiProvider.providesMoshi().adapter(SyncResponse::class.java).fromJson(it)!! }

            // Handle the split sync response
            syncResponseHandler.handleResponse(syncResponse, null, joinedRoomsFiles)

            // Delete all files
            workingDir.deleteRecursively()
        }
    }

    private fun checkFileSizes(everythingElseFile: File, joinedRoomsFiles: List<File>, syncResponseLength: Int) {
        val splitFilesLength = everythingElseFile.length().toInt() + """"join":{}""".length +
                joinedRoomsFiles.sumBy { it.length().toInt() - SUB_FILE_HEADER.length - SUB_FILE_FOOTER.length + 1 }

        if (splitFilesLength != syncResponseLength) {
            Timber.w("INIT_SYNC something is wrong. Expecting $syncResponseLength cumulated bytes and get $splitFilesLength bytes")
        } else {
            Timber.v("INIT_SYNC original file size and sum of split files sizes are equal!")
        }
    }

    private fun createFirstFile(raf: RandomAccessFile,
                                range: Range,
                                syncResponseLength: Int,
                                everythingElseFile: File,
                                buffer: ByteArray) {
        var cumul = 0
        // First file
        Timber.v("INIT_SYNC write first file")
        raf.seek(0)
        everythingElseFile.outputStream().use { everythingElseOutput ->
            // Copy first part
            Timber.v("INIT_SYNC copy first part")
            while (cumul < range.indexStart) {
                val read = raf.read(buffer, 0, min(buffer.size, range.indexStart - cumul))
                everythingElseOutput.write(buffer, 0, read)
                cumul += read
            }
            // Copy second part
            Timber.v("INIT_SYNC copy second part")
            raf.seek(range.indexEnd.toLong())
            cumul = range.indexEnd
            var read = 1
            while (read > 0) {
                read = raf.read(buffer, 0, min(buffer.size, syncResponseLength - cumul))
                everythingElseOutput.write(buffer, 0, read)
                cumul += read
            }
        }
    }

    private suspend fun handleTheWholeResponse(workingFile: File) {
        val json = workingFile.readText(Charsets.UTF_8)
        val syncResponse = MoshiProvider.providesMoshi().adapter(SyncResponse::class.java).fromJson(json)!!
        syncResponseHandler.handleResponse(syncResponse, null, null)
    }

    private fun searchJoinRoomsBoundaries(raf: RandomAccessFile,
                                          syncResponseLength: Int,
                                          buffer: ByteArray): Range {
        // First try without offset
        val firstTry = searchJoinRoomsBoundariesWithOffset(raf, syncResponseLength, 0, buffer)
        if (firstTry.isValid()) {
            return firstTry
        }

        Timber.v("INIT_SYNC searchJoinRoomsBoundaries with an offset")

        // Try again with an offset, it can happen if the search pattern is between two read buffers
        val secondTry = searchJoinRoomsBoundariesWithOffset(raf, syncResponseLength, 50, buffer)

        // secondTry can be worst than first try, ensure we always take the valid value (we should not have both result with not found index)
        return Range(
                secondTry.indexStart.takeIf { it != -1 } ?: firstTry.indexStart,
                secondTry.indexEnd.takeIf { it != -1 } ?: firstTry.indexEnd
        )
    }

    private fun searchJoinRoomsBoundariesWithOffset(raf: RandomAccessFile,
                                                    syncResponseLength: Int,
                                                    offset: Int,
                                                    buffer: ByteArray): Range {
        var roomBeginIndexStart = -1
        // Index of `,"invite":"`
        var roomEndIndexStart = -1

        var cumul = offset
        var read = 1

        // Move to beginning
        raf.seek(offset.toLong())

        while (read > 0) {
            read = raf.read(buffer, 0, min(buffer.size, syncResponseLength - cumul))

            // Search beginning of joined room section
            val roomBeginIndex = buffer.firstIndexOf(ROOM_BEGIN, read)

            if (roomBeginIndex != -1 && roomBeginIndexStart == -1) {
                roomBeginIndexStart = cumul + roomBeginIndex
                Timber.v("INIT_SYNC room beginIndex $roomBeginIndexStart")
            }

            if (roomEndIndexStart == -1) {
                // Search end of joined room section
                val roomEndIndex = buffer.firstIndexOf(ROOM_END, read)
                if (roomEndIndex != -1) {
                    roomEndIndexStart = cumul + roomEndIndex
                    Timber.v("INIT_SYNC room endIndex $roomEndIndexStart")
                    break
                }
            }

            cumul += read
        }

        return Range(roomBeginIndexStart, roomEndIndexStart)
    }

    private fun getRoomIndexes(raf: RandomAccessFile, joinRoomContentIndexes: Range, buffer: ByteArray): List<Int> {
        val indexes = mutableListOf(joinRoomContentIndexes.indexStart)
        var position = joinRoomContentIndexes.indexStart
        while (position + MAX_ROOM_FILE_SIZE < joinRoomContentIndexes.indexEnd) {
            // Search for the next room separator
            position += MAX_ROOM_FILE_SIZE
            raf.seek(position.toLong())

            var read = raf.read(buffer, 0, min(buffer.size, joinRoomContentIndexes.indexEnd - position))
            var index = buffer.firstIndexOf(ROOM_SPLIT, read)
            while (index == -1 && read > 0) {
                // Keep reading
                position += read
                read = raf.read(buffer, 0, min(buffer.size, joinRoomContentIndexes.indexEnd - position))
                index = buffer.firstIndexOf(ROOM_SPLIT, read)
            }
            if (index == -1) {
                // End of section
                Timber.v("INIT_SYNC room separator not found")
            } else {
                // + 2 to skip "}," from ROOM_SPLIT
                position += index + 2
                Timber.v("INIT_SYNC room separator found at index $position")
                indexes.add(position)
            }
        }
        indexes.add(joinRoomContentIndexes.indexEnd)

        return indexes
    }

    private fun copyPartialJoinRoomsToFile(raf: RandomAccessFile, startIndex: Int, endIndex: Int, target: File, buffer: ByteArray) {
        var cumul = 0
        val size = endIndex - startIndex
        raf.seek(startIndex.toLong())
        target.outputStream().use { output ->
            output.write(SUB_FILE_HEADER.toByteArray())
            while (cumul < size) {
                val read = raf.read(buffer, 0, min(buffer.size, size - cumul))
                output.write(buffer, 0, read)
                cumul += read
            }
            output.write(SUB_FILE_FOOTER.toByteArray())
        }
    }

    companion object {
        private const val TIMEOUT_MARGIN: Long = 10_000
        private const val BUFFER_SIZE = 100 * 1024

        private const val MAX_SYNC_FILE_SIZE = 1024 * 1024

        // This is actually a min size, since we will try to found the next room separator.
        // Rooms can be big, so keep a good margin
        private const val MAX_ROOM_FILE_SIZE = 400 * 1024

        private val ROOM_BEGIN = """"join":{"!""".toByteArray()
        private val ROOM_END = """"invite":{""".toByteArray()
        private val ROOM_SPLIT = """},"!""".toByteArray()

        private const val SUB_FILE_HEADER = """{"join":{"""
        private const val SUB_FILE_FOOTER = """}}"""
    }
}
