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
import android.util.AtomicFile
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
                putString("engine", "yt-dlp/${BuildConfig.YTDLP_VERSION}")
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
                val link = url?.let(MediaLink::parse)
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
                prepareBundledExtractor()
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
                val formats = "bv[ext=mp4][vcodec^=avc1]+ba[ext=m4a]/b[ext=mp4]/bv[ext=mp4]+ba[ext=m4a]"
                addOption("-f", when (link.site) {
                    Site.XIAOHONGSHU -> "$formats/bv[ext=mp4]"
                    Site.YOUTUBE -> "$formats/bv+ba/b"
                    else -> formats
                })
                addOption("--merge-output-format", "mp4")
                if (link.site == Site.YOUTUBE) {
                    addOption("--match-filters", YOUTUBE_REPLAY_FILTER)
                    addOption("--remux-video", "mp4")
                }
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
                throw engineFailure(link.site, error.message.orEmpty())
            }
            val stdout = response.out.orEmpty()
            val stderr = response.err.orEmpty()
            if (link.site == Site.YOUTUBE && (isFilteredLive(stdout) || isFilteredLive(stderr))) {
                throw DownloadFailure("LIVE_NOT_READY")
            }
            if (response.exitCode != 0) throw engineFailure(link.site, stderr)

            val rootPath = bucket.canonicalPath
            val artifacts = stdout.lineSequence()
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

    private fun prepareBundledExtractor() {
        val directory = File(noBackupFilesDir, "${YoutubeDL.baseName}/${YoutubeDL.ytdlpDirName}")
        val executable = File(directory, YoutubeDL.ytdlpBin)
        val prefs = getSharedPreferences("bundled_extractor", MODE_PRIVATE)
        if (executable.length() > 0 && prefs.getString("version", null) == BuildConfig.YTDLP_VERSION) return
        check(directory.mkdirs() || directory.isDirectory)
        val target = AtomicFile(executable)
        val output = target.startWrite()
        try {
            val bytes = resources.openRawResource(R.raw.ytdlp).use { it.copyTo(output) }
            target.finishWrite(output)
            check(bytes > 0 && executable.length() == bytes)
            check(prefs.edit().putString("version", BuildConfig.YTDLP_VERSION).commit())
        } catch (error: Exception) {
            target.failWrite(output)
            throw error
        }
    }

    private fun state(job: Job, value: String, details: Bundle = Bundle()) {
        runCatching { job.callback.onState(job.taskId, job.attemptId, value, details) }
    }

    private fun engineFailure(site: Site, message: String): DownloadFailure {
        val text = message.lowercase(Locale.ROOT)
        val requiresSession = listOf("fresh cookies", "login required", "log in", "sign in",
            "cookies are needed", "cookies are required", "requires authentication").any(text::contains)
        return DownloadFailure(when {
            site == Site.YOUTUBE && isFilteredLive(message) -> "LIVE_NOT_READY"
            requiresSession || site == Site.XIAOHONGSHU && listOf(
                "xiaohongshu.com/login", "登录后", "请登录",
            ).any(text::contains) -> "SESSION_REQUIRED"
            site == Site.XIAOHONGSHU && "no video formats found" in text -> "NO_VIDEO"
            else -> "ENGINE_FAILED"
        })
    }

    private class DownloadFailure(val code: String) : Exception()

    private companion object {
        const val YOUTUBE_REPLAY_FILTER = "live_status !=? is_live & live_status !=? is_upcoming & !is_live"

        fun isFilteredLive(output: String): Boolean = output.lineSequence().any { line ->
            line.startsWith("[download] ") && "does not pass filter" in line && YOUTUBE_REPLAY_FILTER in line
        }

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
