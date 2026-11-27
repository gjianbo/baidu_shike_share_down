package com.hnwd.bdplayer.extension

import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.StatFs
import com.hnwd.bdplayer.extension.api.IDownloadCallback
import com.hnwd.bdplayer.extension.api.IDownloadService
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import java.nio.file.Files
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class DownloadService : Service() {
    private class Job(
        val taskId: String,
        val attemptId: String,
        val callback: IDownloadCallback,
    ) {
        val processId = UUID.randomUUID().toString()
        @Volatile var cancelled = false
        @Volatile var finished = false
    }

    private val lock = Any()
    private val main = Handler(Looper.getMainLooper())
    private var active: Job? = null

    private val binder = object : IDownloadService.Stub() {
        override fun capabilities(): Bundle {
            verifyCaller()
            return Bundle().apply {
                putInt("protocol", 1)
                putString("engine", "youtubedl-android/0.18.1")
                putStringArray("sites", Site.entries.map { it.name }.toTypedArray())
            }
        }

        override fun start(
            taskId: String?, attemptId: String?, url: String?, cookies: String?,
            output: ParcelFileDescriptor?, callback: IDownloadCallback?,
        ): Boolean {
            var accepted = false
            try {
                verifyCaller()
                val link = url?.takeIf { it.length <= 2048 }?.let(MediaLink::parse)
                if (link == null || output == null || callback == null ||
                    taskId.isNullOrBlank() || taskId.length > 80 ||
                    attemptId.isNullOrBlank() || attemptId.length > 80 ||
                    !validCookies(link.site, cookies.orEmpty())) return false

                synchronized(lock) {
                    if (active != null) return false
                    val job = Job(taskId, attemptId, callback)
                    val death = IBinder.DeathRecipient { stop(job) }
                    callback.asBinder().linkToDeath(death, 0)
                    active = job
                    worker.execute { download(job, link, cookies.orEmpty(), output, death) }
                    accepted = true
                }
                return true
            } finally {
                // The worker owns the received descriptor once the request is accepted.
                if (!accepted) {
                    runCatching { output?.close() }
                }
            }
        }

        override fun cancel(taskId: String?, attemptId: String?) {
            verifyCaller()
            synchronized(lock) { active?.takeIf { it.taskId == taskId && it.attemptId == attemptId } }?.let(::stop)
        }
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onUnbind(intent: Intent): Boolean {
        synchronized(lock) { active }?.let(::stop)
        return false
    }

    override fun onDestroy() {
        synchronized(lock) { active }?.let(::stop)
        super.onDestroy()
    }

    private fun verifyCaller() {
        val uid = Binder.getCallingUid()
        val hostUid = runCatching { packageManager.getApplicationInfo(HOST_PACKAGE, 0).uid }.getOrNull()
        if (uid != hostUid || packageManager.checkSignatures(uid, applicationInfo.uid) != PackageManager.SIGNATURE_MATCH) {
            throw SecurityException("Unrecognized download client")
        }
    }

    private fun stop(job: Job) {
        synchronized(job) {
            if (job.cancelled || job.finished) return
            job.cancelled = true
        }
        main.post(object : Runnable {
            override fun run() {
                if (job.finished) return
                // The wrapper can register or remove the process concurrently with cancellation.
                val stopped = runCatching { YoutubeDL.getInstance().destroyProcessById(job.processId) }.getOrDefault(false)
                if (!stopped) main.postDelayed(this, 200)
            }
        })
    }

    private fun download(
        job: Job, link: MediaLink, cookies: String, output: ParcelFileDescriptor, death: IBinder.DeathRecipient,
    ) {
        var directory: File? = null
        var terminal = "FAILED"
        val result = Bundle()
        try {
            check(!job.cancelled)
            val root = File(cacheDir, "downloads")
            if (!cleaned) {
                root.deleteRecursively()
                cleaned = true
            }
            check(root.mkdirs() || root.isDirectory)
            if (StatFs(root.path).availableBytes < 128L * 1024 * 1024) {
                throw DownloadFailure("LOW_SPACE")
            }
            val bucket = Files.createTempDirectory(root.toPath(), "job-").toFile().canonicalFile
            directory = bucket
            state(job, "PARSING")
            val engine = YoutubeDL.getInstance()
            try {
                engine.init(applicationContext)
                FFmpeg.getInstance().init(applicationContext)
            } catch (_: Exception) {
                throw DownloadFailure("ENGINE_UNAVAILABLE")
            }
            val request = YoutubeDLRequest(link.url).apply {
                addOption("--ignore-config")
                addOption("--no-update")
                addOption("--no-playlist")
                addOption("--no-simulate")
                addOption("--newline")
                addOption("--no-quiet")
                addOption("--progress")
                addOption("--socket-timeout", "20")
                addOption("-f", "bv[ext=mp4][vcodec^=avc1]+ba[ext=m4a]/b[ext=mp4]/bv[ext=mp4]+ba[ext=m4a]")
                addOption("--merge-output-format", "mp4")
                addOption("-o", File(bucket, "%(title).120B [%(id)s].%(ext)s").path)
                addOption("--print", "after_move:filepath")
                if (cookies.isNotEmpty()) {
                    val cookieFile = File(bucket, "session.txt")
                    cookieFile.writeText(cookies)
                    addOption("--cookies", cookieFile.path)
                }
            }
            var lastPercent = -1
            var merging = false
            check(!job.cancelled)
            val response = try {
                engine.execute(request, job.processId) { progress, _, line ->
                    if (job.cancelled) return@execute
                    if (line?.startsWith("[Merger]") == true && !merging) {
                        merging = true
                        state(job, "MERGING")
                    } else if (!merging && progress.isFinite() && progress >= 0) {
                        val percent = progress.roundToInt().coerceIn(0, 100)
                        if (percent != lastPercent) {
                            lastPercent = percent
                            state(job, "DOWNLOADING", Bundle().apply { putInt("percent", percent) })
                        }
                    }
                }
            } catch (error: Exception) {
                throw engineFailure(error.message.orEmpty())
            }
            if (response.exitCode != 0) throw engineFailure(response.err.orEmpty())

            val rootPath = bucket.canonicalPath
            val artifacts = response.out.orEmpty().lineSequence()
                .filter { it.startsWith("$rootPath/") && it.endsWith(".mp4", ignoreCase = true) }
                .map { File(it).canonicalFile }
                .filter { it.parent == rootPath && it.isFile }
                .distinct().toList()
            val video = artifacts.singleOrNull()?.takeIf { it.length() > 0 }
                ?: throw DownloadFailure("INVALID_ARTIFACT")
            state(job, "COPYING")
            val byteCount = ParcelFileDescriptor.AutoCloseOutputStream(output).use { file ->
                video.inputStream().use { input ->
                    val buffer = ByteArray(128 * 1024)
                    var total = 0L
                    while (true) {
                        check(!job.cancelled)
                        val count = input.read(buffer)
                        if (count == -1) break
                        file.write(buffer, 0, count)
                        total += count
                    }
                    file.flush()
                    total
                }
            }
            check(byteCount == video.length())
            result.putString("name", video.name)
            result.putString("format", "mp4")
            result.putLong("bytes", byteCount)
            terminal = "READY"
        } catch (error: DownloadFailure) {
            result.putString("code", error.code)
            if (error.code == "SESSION_REQUIRED") terminal = "LOGIN_REQUIRED"
        } catch (_: Exception) {
            result.putString("code", "DOWNLOAD_FAILED")
        } finally {
            runCatching { output.close() }
            directory?.deleteRecursively()
            runCatching { job.callback.asBinder().unlinkToDeath(death, 0) }
            synchronized(job) {
                job.finished = true
                if (job.cancelled) {
                    terminal = "CANCELLED"
                    result.clear()
                }
            }
            synchronized(lock) { if (active === job) active = null }
            state(job, terminal, result)
        }
    }

    private fun state(job: Job, value: String, details: Bundle = Bundle()) {
        runCatching { job.callback.onState(job.taskId, job.attemptId, value, details) }
    }

    private fun engineFailure(message: String): DownloadFailure {
        val text = message.lowercase(Locale.ROOT)
        val requiresSession = listOf("fresh cookies", "login required", "log in", "sign in",
            "cookies are needed", "cookies are required", "requires authentication").any(text::contains)
        return DownloadFailure(if (requiresSession) "SESSION_REQUIRED" else "ENGINE_FAILED")
    }

    private class DownloadFailure(val code: String) : Exception()

    private companion object {
        val worker = Executors.newSingleThreadExecutor()
        var cleaned = false

        fun validCookies(site: Site, cookies: String): Boolean {
            if (cookies.isEmpty()) return true
            if (cookies.length > 32_768 || '\u0000' in cookies) return false
            return cookies.lineSequence().all { line ->
                val row = line.removePrefix("#HttpOnly_")
                if (line.isBlank() || (row == line && line.startsWith("#"))) true
                else {
                    val fields = row.split('\t')
                    fields.size == 7 && site.acceptsCookieDomain(fields[0].removePrefix(".").lowercase(Locale.ROOT))
                }
            }
        }
    }
}
