package dev.neurostenografist.inference

import android.content.Context
import dev.neurostenografist.core.Progress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Asset opener for the real Android runtime; missing asset members surface as `null`. */
private fun assetOpener(context: Context): (String) -> InputStream? = { path -> runCatching { context.assets.open(path) }.getOrNull() }

/** One fetched HTTP body together with its declared length (null when the server omits it). */
class HttpContent(
    val stream: InputStream,
    val contentLength: Long?,
)

/** Non-200 HTTP status from a model URL; 429/5xx are treated as transient and retried. */
class HttpStatusException(
    val code: Int,
    url: String,
) : IOException("HTTP $code for $url")

/** Opens a pinned model URL (following redirects) and validates the response. */
fun interface HttpFileSource {
    /** @throws IOException on network failure or non-200 status. */
    fun open(url: String): HttpContent
}

/**
 * Production source over [HttpURLConnection]: HTTPS only (a redirect that leaves
 * HTTPS is refused), bounded connect/read timeouts, single status check.
 */
class HttpURLConnectionFileSource(
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 30_000,
) : HttpFileSource {
    override fun open(url: String): HttpContent {
        val conn = openConnection(url)
        try {
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) throw HttpStatusException(code, url)
            if (conn.url.protocol != "https") throw IOException("redirect left HTTPS for $url (now ${conn.url})")
            // contentLengthLong is -1 when unknown; treat that as null and let the
            // streamed size/sum check catch truncation.
            val len = conn.contentLengthLong
            val stream =
                object : FilterInputStream(conn.inputStream) {
                    override fun close() {
                        try {
                            super.close()
                        } finally {
                            conn.disconnect()
                        }
                    }
                }
            return HttpContent(stream, if (len >= 0) len else null)
        } catch (e: IOException) {
            conn.disconnect()
            throw e
        }
    }

    private fun openConnection(url: String): HttpURLConnection {
        val parsed = URL(url)
        if (parsed.protocol != "https") throw IOException("model URLs must be HTTPS: $url")
        val conn = parsed.openConnection() as HttpURLConnection
        conn.connectTimeout = connectTimeoutMs
        conn.readTimeout = readTimeoutMs
        // HttpURLConnection follows only same-protocol redirects; an https->http
        // downgrade therefore surfaces as the redirect response status, never as
        // plain http content, and the protocol re-check above closes even that.
        conn.instanceFollowRedirects = true
        return conn
    }
}

/**
 * Installs catalog bundles into app-private storage with atomic promotion and
 * SHA-256 verification of every file.
 *
 * Weights are no longer packaged in the APK: bundles with pinned public sources are
 * downloaded over HTTPS by [install] (which is what the app calls); the asset-based
 * [installBundle] seam remains for JVM tests and old APK copies of the legacy bundle.
 *
 * Safety invariants:
 *  - every member lands in `<id>.installing`, is checked against its pinned size
 *    (declared length up front, streamed byte count while copying) and SHA-256, and
 *    only then is the whole dir renamed into place; the previously installed good
 *    bundle is staged aside to `<id>.old` and restored unchanged if promotion fails —
 *    a failed download can never destroy a good bundle;
 *  - installs are serialized process-wide through [INSTALL_LOCK];
 *  - deletion is refused while a processing job holds the bundles ([isBusy]);
 *  - readiness ([isReady]) means the verification marker content plus every member at
 *    its exact catalog size — not a bare filename, and not a stale marker;
 *  - only plain ids and plain member names ever reach the file system.
 *
 * The `openMember`/`roots` constructor seam exists for JVM unit tests; the app always
 * constructs through the [Context] constructor.
 */
class ModelStore internal constructor(
    private val roots: File,
    private val openMember: (assetPath: String) -> InputStream?,
    private val isBusy: () -> Boolean = { false },
    private val urlSource: HttpFileSource = HttpURLConnectionFileSource(),
) {
    constructor(
        context: Context,
        isBusy: () -> Boolean = { false },
    ) : this(
        File(context.filesDir, "models"),
        assetOpener(context),
        isBusy,
    )

    fun bundleDir(bundleId: String): File = File(roots, ModelCatalog.byId(bundleId).id)

    /** True only after full hash verification at install time plus exact member sizes now.
     *  Unknown/unsafe IDs are simply not ready; for non-catalog (test-seam) bundles the
     *  verified-record marker alone stands in for the spec. */
    fun isReady(bundleId: String): Boolean {
        requireSafeId(bundleId) ?: return false
        val dir = File(roots, bundleId)
        val marker = File(dir, READY_MARKER)
        if (!marker.isFile || marker.readText() != markerContent(bundleId)) return false
        val bundle = ModelCatalog.bundles.firstOrNull { it.id == bundleId } ?: return dir.isDirectory
        return bundle.files.all { spec ->
            val f = File(dir, spec.name)
            f.isFile && f.length() == spec.bytes
        }
    }

    /**
     * Downloads the bundle from its pinned public HTTPS sources into a temp dir while
     * hashing every chunk, verifies all files, then renames the dir into place. Never
     * destroys an existing good copy on any failure (network, HTTP status, size,
     * checksum, rename); the temp dir is always removed. Each member gets up to
     * [DOWNLOAD_ATTEMPTS] attempts with backoff for transient network errors, transient
     * HTTP statuses and truncated transfers. Concurrent calls are serialized.
     */
    suspend fun install(
        bundleId: String,
        onProgress: (Progress) -> Unit,
    ) {
        val bundle = ModelCatalog.byId(bundleId) // unknown/unsafe IDs never reach the file system
        if (bundle.id in ModelCatalog.bundledIds) {
            withContext(Dispatchers.IO) {
                if (!isReady(bundle.id)) installBundle(bundle, onProgress)
            }
            return
        }
        if (!bundle.downloadable) {
            throw ModelBundleException(
                "bundle '${bundle.id}' has no pinned download source — it was shipped only inside old " +
                    "APK assets, so only an already-installed private-storage copy can serve it",
            )
        }
        installFromNetwork(bundle, onProgress)
    }

    private suspend fun installFromNetwork(
        bundle: ModelBundle,
        onProgress: (Progress) -> Unit,
    ) = withContext(Dispatchers.IO) {
        installLocked(bundle, onProgress, "downloading") { spec, target, onBytes ->
            fetchMemberWithRetries(bundle, spec, target, onBytes)
        }
    }

    /** Asset-based seam with the same guarantees; kept for tests and preinstalled copies. */
    internal suspend fun installBundle(
        bundle: ModelBundle,
        onProgress: (Progress) -> Unit,
    ) = installLocked(bundle, onProgress, "installing") { spec, target, onBytes ->
        val input =
            openMember("${bundle.assetDir}/${spec.name}")
                ?: throw ModelBundleException(
                    "bundle '${bundle.id}' member '${spec.name}' is missing from the APK assets",
                )
        input.use { copyVerified(bundle.id, spec, target, it, onBytes) }
    }

    private suspend fun installLocked(
        bundle: ModelBundle,
        onProgress: (Progress) -> Unit,
        stage: String,
        fetchMember: (spec: BundleFile, target: File, onBytes: (Long) -> Unit) -> Unit,
    ) {
        synchronized(INSTALL_LOCK) {
            require(requireSafeId(bundle.id) != null) { "unsafe bundle id '${bundle.id}'" }
            roots.mkdirs()
            val needed = bundle.totalBytes * 5 / 4 // enough for extraction + promotion headroom
            if (roots.usableSpace in 0 until needed) {
                throw ModelBundleException(
                    "insufficient free space for '${bundle.id}': need ~${needed}B, have ${roots.usableSpace}B",
                )
            }
            val tmp = File(roots, "${bundle.id}.installing")
            val backup = File(roots, "${bundle.id}.old")
            val final = File(roots, bundle.id)
            tmp.deleteRecursively()
            try {
                tmp.mkdirs()
                var done = 0L
                val total = bundle.totalBytes
                for (spec in bundle.files) {
                    val safeName =
                        requireSafeFileName(spec.name)
                            ?: throw ModelBundleException("unsafe bundle file name '${spec.name}'")
                    val target = File(tmp, safeName)
                    fetchMember(spec, target) { fileDone ->
                        onProgress(memberProgress(stage, done + fileDone, total))
                    }
                    done += spec.bytes
                }
                // Marker travels inside the temp dir, so promotion makes it visible atomically.
                File(tmp, READY_MARKER).writeText(markerContent(bundle.id))
                backup.deleteRecursively()
                if (final.exists() && !final.renameTo(backup)) {
                    throw ModelBundleException("cannot stage existing bundle '${bundle.id}' for replacement")
                }
                if (!tmp.renameTo(final)) {
                    backup.renameTo(final) // put the still-good old bundle back
                    throw ModelBundleException("cannot promote bundle '${bundle.id}' into place")
                }
                backup.deleteRecursively() // promotion succeeded; old copy is now removable
                onProgress(Progress("installing", 1f))
            } finally {
                if (tmp.exists()) tmp.deleteRecursively()
            }
        }
    }

    /** Copy one member with hashing and the exact-size contract; throws on any deviation. */
    private fun copyVerified(
        bundleId: String,
        spec: BundleFile,
        target: File,
        input: InputStream,
        onBytes: (Long) -> Unit,
    ) {
        val digest = MessageDigest.getInstance("SHA-256")
        input.use { stream ->
            target.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                var n = stream.read(buf)
                var written = 0L
                while (n >= 0) {
                    if (written + n > spec.bytes) {
                        throw ModelBundleException(
                            "size mismatch for '$bundleId/${spec.name}': more than the expected ${spec.bytes} bytes",
                        )
                    }
                    digest.update(buf, 0, n)
                    out.write(buf, 0, n)
                    written += n
                    onBytes(written)
                    n = stream.read(buf)
                }
            }
        }
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        if (hash != spec.sha256) {
            throw ModelBundleException(
                "checksum mismatch for '$bundleId/${spec.name}': got $hash expected ${spec.sha256}",
            )
        }
        if (target.length() != spec.bytes) {
            throw ModelBundleException(
                "size mismatch for '$bundleId/${spec.name}': got ${target.length()} expected ${spec.bytes}",
            )
        }
    }

    private fun fetchMemberWithRetries(
        bundle: ModelBundle,
        spec: BundleFile,
        target: File,
        onBytes: (Long) -> Unit,
    ) {
        val url = bundle.memberUrl(spec)
        if (url.isEmpty()) {
            throw ModelBundleException("bundle '${bundle.id}' member '${spec.name}' has no download source")
        }
        if (!url.startsWith("https://")) {
            throw ModelBundleException("model URLs must be HTTPS, refusing '$url'")
        }
        var attempt = 1
        while (true) {
            try {
                val content = urlSource.open(url)
                if (content.contentLength != null && content.contentLength != spec.bytes) {
                    content.stream.close()
                    // Declared length off in either direction means wrong content at a
                    // pinned URL; retrying the same immutable revision cannot fix it.
                    throw ContentShapeException(
                        "size mismatch for '${bundle.id}/${spec.name}': source declares ${content.contentLength} bytes, expected ${spec.bytes}",
                    )
                }
                content.stream.use { copyVerified(bundle.id, spec, target, it, onBytes) }
                return
            } catch (e: ContentShapeException) {
                throw e
            } catch (e: HttpStatusException) {
                if (e.code !in TRANSIENT_HTTP) {
                    throw ModelBundleException("download of '${bundle.id}/${spec.name}' failed: ${e.message}")
                }
            } catch (e: IOException) {
                // transient network failure (the external network intermittently drops
                // connections with SSL EOF) — retry below
            } catch (e: ModelBundleException) {
                // checksum/size mismatch, possibly a truncated transfer: a bounded retry
                // can recover truncation, and cannot help beyond that
            }
            if (attempt >= DOWNLOAD_ATTEMPTS) {
                throw ModelBundleException("download failed for '${bundle.id}/${spec.name}' after $DOWNLOAD_ATTEMPTS attempts")
            }
            Thread.sleep(RETRY_BACKOFF_MS * attempt)
            attempt++
        }
    }

    fun delete(bundleId: String) {
        // Path-safety without catalog dependency: only plain names reach the file system.
        require(requireSafeId(bundleId) != null) { "unsafe bundle id '$bundleId'" }
        check(!isBusy()) { "a processing job is using the models; cancel it first" }
        synchronized(INSTALL_LOCK) {
            File(roots, bundleId).deleteRecursively()
            File(roots, "$bundleId.installing").deleteRecursively()
            File(roots, "$bundleId.old").deleteRecursively()
        }
    }

    companion object {
        private val INSTALL_LOCK = Any()

        private const val DOWNLOAD_ATTEMPTS = 3
        private const val RETRY_BACKOFF_MS = 250L

        private val TRANSIENT_HTTP = setOf(429, 500, 502, 503, 504)

        /** Declared response shape that can never match the pinned spec; never retried. */
        private class ContentShapeException(
            message: String,
        ) : IOException(message)

        /** Returns the id itself when it is a plain directory name, or null otherwise.
         *  Guards every id-driven path against traversal without needing the catalog. */
        fun requireSafeId(bundleId: String): String? =
            bundleId.takeIf {
                it.isNotBlank() && it !in setOf(".", "..") && !it.contains('/') && !it.contains('\\') && !it.contains("\u0000")
            }

        /** Returns the name when it is a plain single path segment, or null otherwise.
         *  Guards member paths (which come from the catalog, but the catalog is data). */
        fun requireSafeFileName(name: String): String? = name.takeIf { it.matches(SAFE_FILE_NAME) }

        private val SAFE_FILE_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")

        private const val READY_MARKER = ".ready-hash-verified"

        private fun markerContent(bundleId: String) = "verified:$bundleId"
    }
}
