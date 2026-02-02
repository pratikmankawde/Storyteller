package com.dramebaz.app.audio

import com.dramebaz.app.utils.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * AUG-043: Stitches multiple WAV audio files into a single combined file.
 * Used for combining individual segment audio files into a single page audio for playback.
 */
object AudioStitcher {
    private val tag = "AudioStitcher"

    /**
     * Combine multiple WAV files into a single WAV file.
     * All input files must have the same audio format (sample rate, channels, bit depth).
     *
     * @param inputFiles List of WAV files to combine (in order)
     * @param outputFile Output file to write combined audio to
     * @return The output file if successful, null otherwise
     */
    suspend fun stitchWavFiles(
        inputFiles: List<File>,
        outputFile: File
    ): File? = withContext(Dispatchers.IO) {
        if (inputFiles.isEmpty()) {
            AppLogger.w(tag, "No input files to stitch")
            return@withContext null
        }

        if (inputFiles.size == 1) {
            // Single file - just copy it
            inputFiles[0].copyTo(outputFile, overwrite = true)
            return@withContext outputFile
        }

        try {
            val startTime = System.currentTimeMillis()

            // Read header from first file to get audio format
            val firstHeader = readWavHeader(inputFiles[0])
            if (firstHeader == null) {
                AppLogger.e(tag, "Failed to read WAV header from first file")
                return@withContext null
            }

            // Collect all audio data
            val allAudioData = mutableListOf<ByteArray>()
            var totalDataSize = 0

            for (file in inputFiles) {
                val audioData = extractAudioData(file)
                if (audioData != null) {
                    allAudioData.add(audioData)
                    totalDataSize += audioData.size
                } else {
                    AppLogger.w(tag, "Failed to extract audio data from ${file.name}")
                }
            }

            if (allAudioData.isEmpty()) {
                AppLogger.e(tag, "No audio data extracted from input files")
                return@withContext null
            }

            // Write combined WAV file
            outputFile.parentFile?.mkdirs()
            FileOutputStream(outputFile).use { fos ->
                // Write WAV header with total data size
                writeWavHeader(fos, firstHeader, totalDataSize)

                // Write all audio data
                for (data in allAudioData) {
                    fos.write(data)
                }
            }

            AppLogger.logPerformance(tag, "Stitched ${inputFiles.size} files (${totalDataSize / 1024} KB)",
                System.currentTimeMillis() - startTime)
            AppLogger.i(tag, "Created combined audio: ${outputFile.absolutePath}")

            outputFile
        } catch (e: Exception) {
            AppLogger.e(tag, "Failed to stitch audio files", e)
            null
        }
    }

    /**
     * WAV header data structure.
     */
    private data class WavHeader(
        val sampleRate: Int,
        val numChannels: Int,
        val bitsPerSample: Int,
        val byteRate: Int,
        val blockAlign: Int,
        val dataOffset: Int  // Position where audio data starts
    )

    /**
     * Read WAV header from file.
     */
    private fun readWavHeader(file: File): WavHeader? {
        return try {
            RandomAccessFile(file, "r").use { raf ->
                // RIFF header
                val riff = ByteArray(4)
                raf.read(riff)
                if (String(riff) != "RIFF") return null

                raf.skipBytes(4) // File size

                val wave = ByteArray(4)
                raf.read(wave)
                if (String(wave) != "WAVE") return null

                // Find fmt chunk
                var foundFmt = false
                var numChannels = 0
                var sampleRate = 0
                var byteRate = 0
                var blockAlign = 0
                var bitsPerSample = 0

                while (!foundFmt && raf.filePointer < raf.length()) {
                    val chunkId = ByteArray(4)
                    raf.read(chunkId)
                    val chunkSizeBytes = ByteArray(4)
                    raf.read(chunkSizeBytes)
                    val chunkSize = ByteBuffer.wrap(chunkSizeBytes).order(ByteOrder.LITTLE_ENDIAN).int

                    if (String(chunkId) == "fmt ") {
                        raf.skipBytes(2) // Audio format
                        numChannels = raf.readShortLE()
                        sampleRate = raf.readIntLE()
                        byteRate = raf.readIntLE()
                        blockAlign = raf.readShortLE()
                        bitsPerSample = raf.readShortLE()
                        foundFmt = true
                        raf.skipBytes(chunkSize - 16) // Skip rest of fmt chunk
                    } else {
                        raf.skipBytes(chunkSize)
                    }
                }

                if (!foundFmt) return null

                // Find data chunk
                while (raf.filePointer < raf.length()) {
                    val chunkId = ByteArray(4)
                    raf.read(chunkId)
                    val chunkSizeBytes = ByteArray(4)
                    raf.read(chunkSizeBytes)

                    if (String(chunkId) == "data") {
                        return WavHeader(
                            sampleRate = sampleRate,
                            numChannels = numChannels,
                            bitsPerSample = bitsPerSample,
                            byteRate = byteRate,
                            blockAlign = blockAlign,
                            dataOffset = raf.filePointer.toInt()
                        )
                    }
                }
                null
            }
        } catch (e: Exception) {
            AppLogger.e(tag, "Error reading WAV header", e)
            null
        }
    }

    /**
     * Extract raw audio data from WAV file (without header).
     */
    private fun extractAudioData(file: File): ByteArray? {
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val header = readWavHeader(file) ?: return null
                raf.seek(header.dataOffset.toLong())
                val dataSize = (raf.length() - header.dataOffset).toInt()
                val data = ByteArray(dataSize)
                raf.readFully(data)
                data
            }
        } catch (e: Exception) {
            AppLogger.e(tag, "Error extracting audio data", e)
            null
        }
    }

    /**
     * Write WAV header to output stream.
     */
    private fun writeWavHeader(
        fos: FileOutputStream,
        header: WavHeader,
        totalDataSize: Int
    ) {
        // RIFF header
        fos.write("RIFF".toByteArray())
        fos.writeIntLE(36 + totalDataSize)  // File size - 8
        fos.write("WAVE".toByteArray())

        // fmt chunk
        fos.write("fmt ".toByteArray())
        fos.writeIntLE(16)  // fmt chunk size
        fos.writeShortLE(1)  // Audio format (1 = PCM)
        fos.writeShortLE(header.numChannels)
        fos.writeIntLE(header.sampleRate)
        fos.writeIntLE(header.byteRate)
        fos.writeShortLE(header.blockAlign)
        fos.writeShortLE(header.bitsPerSample)

        // data chunk
        fos.write("data".toByteArray())
        fos.writeIntLE(totalDataSize)
    }

    // Helper extension methods for little-endian I/O
    private fun RandomAccessFile.readShortLE(): Int {
        val b1 = read()
        val b2 = read()
        return (b2 shl 8) or b1
    }

    private fun RandomAccessFile.readIntLE(): Int {
        val b1 = read()
        val b2 = read()
        val b3 = read()
        val b4 = read()
        return (b4 shl 24) or (b3 shl 16) or (b2 shl 8) or b1
    }

    private fun FileOutputStream.writeShortLE(value: Int) {
        write(value and 0xFF)
        write((value shr 8) and 0xFF)
    }

    private fun FileOutputStream.writeIntLE(value: Int) {
        write(value and 0xFF)
        write((value shr 8) and 0xFF)
        write((value shr 16) and 0xFF)
        write((value shr 24) and 0xFF)
    }
}
