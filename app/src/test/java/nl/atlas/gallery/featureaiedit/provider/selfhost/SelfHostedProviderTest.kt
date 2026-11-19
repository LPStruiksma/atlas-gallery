package nl.atlas.gallery.featureaiedit.provider.selfhost

import nl.atlas.gallery.featureaiedit.network.EditHttpAuth
import nl.atlas.gallery.featureaiedit.network.EditHttpConfig
import nl.atlas.gallery.featureaiedit.provider.*
import nl.atlas.gallery.featureaiedit.provider.hosted.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

/** All wire responses, images and credentials here are synthetic. No external network. */
class AtlasProtocolProviderContractTest : ImageEditProviderContract() {
    private val servers = mutableListOf<FakeProviderServer>()
    private val folders = mutableListOf<File>()
    @After fun clean() { servers.forEach { it.close() }; folders.forEach { it.deleteRecursively() } }

    override fun providerFor(fixture: Fixture): ImageEditProvider {
        val polls = AtomicInteger()
        val png = SyntheticPng.encode(8, 8)
        val server = FakeProviderServer { req ->
            when {
                req.method == "GET" && req.path == "/v1/capabilities" ->
                    FakeResponse.json(CAPS)
                req.method == "POST" && req.path == "/v1/edits" ->
                    if (fixture == Fixture.FAILURE) FakeResponse(200, dropConnection = true)
                    else FakeResponse.json("""{"id":"0123456789abcdef0123456789abcdef","status":"queued"}""", 202)
                req.method == "GET" && req.path.endsWith("/result") ->
                    FakeResponse(200, png, contentType = "image/png")
                req.method == "GET" && req.path == "/v1/edits/0123456789abcdef0123456789abcdef" -> {
                    val count = polls.incrementAndGet()
                    val status = when (fixture) {
                        Fixture.REFUSAL -> "refused"
                        Fixture.SLOW_ASYNC -> "running"
                        Fixture.CANCELLABLE_ASYNC -> if (count <= 2) "running" else "succeeded"
                        else -> "succeeded"
                    }
                    FakeResponse.json("""{"status":"$status","progress":40,"refusal":{"code":"SYNTHETIC_REFUSAL","message":"never echo"}}""")
                }
                req.method == "DELETE" -> FakeResponse.json("""{"status":"cancelled"}""")
                else -> FakeResponse.json("{}", 404)
            }
        }
        servers.add(server)
        val folder = Files.createTempDirectory("atlas-protocol-test").toFile()
        folders.add(folder)
        return AtlasProtocolProvider(
            EditHttpConfig("https://atlas.test/v1/", setOf("atlas.test"), maxAttempts = 1),
            auth = { EditHttpAuth.Bearer("synthetic-key") },
            images = SyntheticImageLoader(png),
            outputDirectory = folder,
            transport = server.transport(),
            pollWaitMs = 30,
            pollIntervalMs = 1
        )
    }

    @Test fun rejectsUnknownProtocolVersionsAndNeverUploads() {
        val server = FakeProviderServer {
            FakeResponse.json(CAPS.replace(""""protocol_version":"1.0"""", """"protocol_version":"2.0""""))
        }
        servers.add(server)
        val folder = Files.createTempDirectory("atlas-protocol-bad").toFile()
        folders.add(folder)
        val provider = AtlasProtocolProvider(
            EditHttpConfig("https://atlas.test/v1/", setOf("atlas.test")),
            { EditHttpAuth.Bearer("synthetic") }, SyntheticImageLoader(), folder, server.transport(), 10, 1
        )
        assertFalse(provider.capabilities().supportsInstructionEdit)
        assertEquals(FailureKind.INVALID_REQUEST,
            (provider.submit(request()).await() as EditOutcome.Failed).kind)
        assertFalse(server.requests.any { it.method == "POST" })
    }

    companion object {
        private const val CAPS = """{"schema_version":1,"protocol_version":"1.0",
"provider_id":"fake","display_name":"Fake",
"features":{"instruction_edit":true,"mask_edit":false,"reference_images":{"max_count":0},
"candidates":{"max_count":1},"multi_turn":false,"seed":false,"async_jobs":true,
"streaming":false,"input_formats":["image/png"],"output_formats":["image/png"],
"max_input_pixels":16777216,"max_output_pixels":16777216,"aspect_constraints":null,
"cost_estimate":null},"advanced_parameters":[]}"""
    }
}

class ComfyUiProviderContractTest : ImageEditProviderContract() {
    private val servers = mutableListOf<FakeProviderServer>()
    private val folders = mutableListOf<File>()
    @After fun clean() { servers.forEach { it.close() }; folders.forEach { it.deleteRecursively() } }

    override fun providerFor(fixture: Fixture): ImageEditProvider {
        val polls = AtomicInteger()
        val image = SyntheticPng.encode(8, 8)
        val server = FakeProviderServer { req ->
            when {
                req.path == "/upload/image" && req.method == "POST" ->
                    FakeResponse.json("""{"name":"upload.png"}""")
                req.path == "/prompt" && req.method == "POST" ->
                    if (fixture == Fixture.FAILURE) FakeResponse(200, dropConnection = true)
                    else FakeResponse.json("""{"prompt_id":"abcdef0123456789abcdef0123456789"}""")
                req.path == "/history/abcdef0123456789abcdef0123456789" -> {
                    val n = polls.incrementAndGet()
                    val entry = when {
                        fixture == Fixture.REFUSAL -> """"atlas_status":"refused","atlas_code":"SYNTHETIC_REFUSAL""""
                        fixture == Fixture.SLOW_ASYNC || (fixture == Fixture.CANCELLABLE_ASYNC && n <= 2) -> ""
                        else -> """"outputs":{"9":{"images":[{"filename":"synthetic.png","subfolder":"","type":"output"}]}}"""
                    }
                    if (entry.isEmpty()) FakeResponse.json("{}")
                    else FakeResponse.json("""{"abcdef0123456789abcdef0123456789":{$entry}}""")
                }
                req.path.startsWith("/view?") -> FakeResponse(200, image, contentType = "image/png")
                req.path == "/queue" -> FakeResponse.json("{}")
                else -> FakeResponse.json("{}", 404)
            }
        }
        servers.add(server)
        val folder = Files.createTempDirectory("atlas-comfy-test").toFile()
        folders.add(folder)
        return ComfyUiProvider(
            config = EditHttpConfig("https://comfy.test/", setOf("comfy.test"), maxAttempts = 1),
            auth = { EditHttpAuth.None },
            workflowJson = """{"1":{"class_type":"LoadImage","inputs":{"image":"{{image}}"}},
"2":{"class_type":"CLIPTextEncode","inputs":{"text":"{{prompt}}"}},
"9":{"class_type":"SaveImage","inputs":{"filename_prefix":"atlas"}}}""",
            images = SyntheticImageLoader(image),
            outputDirectory = folder,
            transport = server.transport(),
            viewConnectionFactory = { url: URL ->
                URL("http://127.0.0.1:${server.port}${url.file}").openConnection() as HttpURLConnection
            },
            pollWaitMs = 30,
            pollIntervalMs = 1
        )
    }

    @Test fun placeholderBindingDoesNotSubstituteArbitrarySubstrings() {
        val workflow = """{"1":{"class_type":"Test","inputs":{"image":"{{image}}",
"prompt":"{{prompt}}","untouched":"prefix-{{prompt}}","seed":"{{seed}}"}}}"""
        val parsed = ComfyUiProvider.bindWorkflow(workflow, mapOf(
            "{{image}}" to "uploaded.png", "{{prompt}}" to "synthetic instruction", "{{seed}}" to 19L
        ))
        val inputs = ((parsed["1"] as Map<*, *>)["inputs"] as Map<*, *>)
        assertEquals("prefix-{{prompt}}", inputs["untouched"])
        assertEquals("synthetic instruction", inputs["prompt"])
        assertEquals(19L, inputs["seed"])
    }
}
