package com.inkwell.net

import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Retrofit
import java.util.concurrent.atomic.AtomicReference

/**
 * Stage 29 reproduction of the 2026-09-21 silent failure, at the JVM level with the REAL
 * Retrofit [DeviceApi] (no emulator, no MockWebServer — an OkHttp application interceptor
 * captures the request URL and serves canned bytes, so it runs in the `android-test` gate).
 *
 * The "prime suspect" was that the server (stage 21 `signed_url`) returns a RELATIVE signed
 * blob link (`/v1/blobs/{key}?sig=&exp=`, no scheme/host) and that Retrofit's `@Url` throws
 * on it "before the first request". This proves that is NOT what happens: the relative link
 * resolves to the correct absolute URL and the blob GET is issued — both through
 * [DeviceRepository]'s explicit resolution (Stage 29 fix) and through Retrofit's own `@Url`
 * base resolution. The real defect was the swallow in `LibraryViewModel.pollPushInboxSuspending`
 * (fixed separately), not the URL.
 */
class BlobUrlReproductionTest {

    private val relativeUrl = "/v1/blobs/push/abc-123.pdf?sig=deadbeefcafe&exp=1790000000"
    private val base = "https://mini-hp01.taile0ffc4.ts.net:8444/"
    private val pdf = "%PDF-1.4\n%stub\n".toByteArray()

    /** Real [DeviceApi] whose only network call is short-circuited; captures the request URL. */
    private fun apiCapturing(captured: AtomicReference<String>): DeviceApi {
        val client = OkHttpClient.Builder()
            .addInterceptor(
                Interceptor { chain ->
                    captured.set(chain.request().url.toString())
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(pdf.toResponseBody("application/pdf".toMediaType()))
                        .build()
                },
            )
            .build()
        return Retrofit.Builder()
            .baseUrl(base)
            .client(client)
            .build()
            .create(DeviceApi::class.java)
    }

    @Test
    fun relative_signed_url_resolves_and_downloads_via_repository_resolution() = runTest {
        val captured = AtomicReference<String>()
        // Stage 29: DeviceRepository resolves the relative link against the paired base URL.
        val repo = DeviceRepository(apiCapturing(captured), baseUrl = base)

        val bytes = repo.downloadBlob(relativeUrl)

        assertTrue("the blob GET actually happened and returned bytes", bytes.isNotEmpty())
        assertEquals(
            "the relative link resolved to the correct absolute blob URL",
            "https://mini-hp01.taile0ffc4.ts.net:8444/v1/blobs/push/abc-123.pdf?sig=deadbeefcafe&exp=1790000000",
            captured.get(),
        )
    }

    @Test
    fun relative_signed_url_also_resolves_through_retrofit_at_url() = runTest {
        val captured = AtomicReference<String>()
        // With no base in the repository, the relative string is passed straight to @Url and
        // Retrofit resolves it against its own base — also fine (documents the prime suspect
        // is not the cause; the request is still issued at the right absolute URL).
        val repo = DeviceRepository(apiCapturing(captured), baseUrl = null)

        val bytes = repo.downloadBlob(relativeUrl)

        assertTrue(bytes.isNotEmpty())
        assertEquals(
            "https://mini-hp01.taile0ffc4.ts.net:8444/v1/blobs/push/abc-123.pdf?sig=deadbeefcafe&exp=1790000000",
            captured.get(),
        )
    }
}
