package hub.guzio.surwebyor

import folk.sisby.surveyor.WorldSummary
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.jetty.jakarta.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.nayuki.png.ImageEncoder
import io.nayuki.png.chunk.Ihdr
import kotlinx.serialization.json.Json
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.event.lifecycle.v1.*
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.util.*


object Main : ModInitializer {
	const val MOD_ID: String = "surwebyor"
	private const val CONFIGNAME = "surwebyor.json"
	private val LOGGER = LoggerFactory.getLogger(MOD_ID)
	private val LEVELS = HashMap<ResourceKey<Level>, Level>()
	private val JSON = Json { ignoreUnknownKeys = true }
	private val SITE = String(javaClass.classLoader.getResourceAsStream("assets/surwebyor/index.html")?.readAllBytes() ?: "There must've been an error when loading Surwebyor and the default index.html couldn't be extracted from its JAR. Please contact the server admin if you're seeing this error while viewing the map of some server, or (if this is singleplayer / you're the admin and are sure you didn't mess anything up) contact Surwebyor devs on GitHub.".encodeToByteArray())
	private var SERVER: EmbeddedServer<JettyApplicationEngine, JettyApplicationEngineBase.Configuration>? = null
	private var CONFIG = Config(8080, 0, 0, "Surwebyor World Map", "/",arrayOf())

	override fun onInitialize() {
		LOGGER.info("Surwebyor is its calling sugar-mommy (Surveyor).  OwO")
		WorldSummary.enableTerrain()

		try {
			CONFIG = JSON.decodeFromString<Config>(FabricLoader.getInstance().configDir.resolve(CONFIGNAME).toFile().readText())
			LOGGER.info("User-provided Surwebyor config loaded successfully!")
		} catch (e: Throwable) {
			LOGGER.error("Writing a new Surwebyor config because your previous one couldn't be read because:", e)
			try {
				val confFile = FabricLoader.getInstance().configDir.resolve(CONFIGNAME).toFile()
				try {
					LOGGER.info("In case you need your old one, it was:\n${confFile.readText()}")
				} catch (e: Throwable) {
					LOGGER.warn("Your previous one also couldn't be backed up (so I hope you didn't have anything important there) because:", e)
				}
				confFile.writeText(JSON.encodeToString(CONFIG))
				LOGGER.info("Using the default Surwebyor config, that was just written to ${confFile.path}")
			} catch (e: Throwable) {
				LOGGER.error("Using the default Surwebyor config in a runtime-only mode because a new one couldn't be written because:", e)
			}
		}

		LOGGER.info("Surwebyor is registering events...")
		ServerWorldEvents.LOAD.register { _, level ->
			LOGGER.info("Surwebyor detected a new ${level.dimension()}")
			LEVELS[level.dimension()] = level
		}
		ServerWorldEvents.UNLOAD.register { _, level ->
			LOGGER.info("Surwebyor is unloading ${level.dimension()}")
			LEVELS.remove(level.dimension())
		}
        ServerLifecycleEvents.SERVER_STARTED.register { _ ->
			LOGGER.info("Booting up the Surweb(-server)yor...")
			SERVER?.stop() //Just in case it was running for any reason...
			SERVER = embeddedServer(Jetty, CONFIG.port, "0.0.0.0", module = Application::rootModule).start()
			LOGGER.info("Surwebyor is LIVE!")
		}
		ServerLifecycleEvents.SERVER_STOPPING.register { _ ->
			LOGGER.info("Terminating Surwebyor session...")
			SERVER?.stop()
			SERVER = null
			LOGGER.info("Surwebyor is DONE with you! >:(   (jk, it's still friendly - the current session isn't tho)")
		}

		LOGGER.info("Surwebyor is done starting.")
	}

	fun getLevel(id: ResourceKey<Level>?): Level? {
		return if (Objects.isNull(id)) null
		else LEVELS[id]
	}

	fun getLevelKey(namespace: String?, id: String?): ResourceKey<Level>? {
		for (key in LEVELS.keys) {
			if (key.location().path.equals(id) && key.location().namespace.equals(namespace)) return key
		}
		return null
	}

	val site get() = CONFIG.applyOntoSite(SITE)
}

fun Application.rootModule() {
	configureRouting()
}

fun Application.configureRouting() {
	routing {
		get("/") {
			call.respondText(Main.site, contentType = if (Main.site.startsWith("<!DOCTYPE html>")) ContentType.Text.Html else ContentType.Text.Plain)
		}
		get("/index.html") {
			call.respondText(Main.site, contentType = if (Main.site.startsWith("<!DOCTYPE html>")) ContentType.Text.Html else ContentType.Text.Plain)
		}
		get("/mapdata/{namespace}/{dimension}/{x}/{z}/{zoom}/tile.png") {
			val params = call.parameters
			val lvl = Main.getLevel(Main.getLevelKey(params["namespace"], params["dimension"]))
			if (Objects.isNull(lvl)) {
				call.respond(HttpStatusCode.NotFound)
				return@get
			}
			val x = Integer.parseInt(params["x"])
			val z = Integer.parseInt(params["z"])
			val zoom = Integer.parseInt(params["zoom"])

			val img = DataGetter.getImgOfChunk(lvl!!, ChunkPos(x, z), zoom)
			if (Objects.isNull(img)) {
				call.respond(HttpStatusCode.NotFound)
				return@get
			}
			val png = ImageEncoder.toPng(img!!, Ihdr.InterlaceMethod.NONE)
			val os = ByteArrayOutputStream()
			png.write(os)
			call.respondBytes(os.toByteArray(), contentType = ContentType.Image.PNG)
		}
	}
}