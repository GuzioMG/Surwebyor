package hub.guzio.surwebyor

import com.mojang.brigadier.Command
import com.mojang.serialization.Lifecycle
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
import net.fabricmc.api.EnvType
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.event.lifecycle.v1.*
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.commands.Commands
import net.minecraft.core.MappedRegistry
import net.minecraft.core.registries.Registries
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.material.MapColor
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.util.*
import kotlin.jvm.optionals.getOrElse


object Main : ModInitializer {
	const val MOD_ID: String = "surwebyor"
	private const val CONFIGNAME = "surwebyor.json"
	private val LOGGER = LoggerFactory.getLogger(MOD_ID)
	private val LEVELS = HashMap<ResourceKey<Level>, Level>()
	private val BIOMES = HashMap<Biome, BiomeColor>()
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
			fillBiomes(level
				.registryAccess()
				.registry(Registries.BIOME)
				.getOrElse {
					LOGGER.warn("Aforementioned dimension doesn't seem to have a biome registry accessor. Biome coloring may fail.")
					return@getOrElse MappedRegistry(ResourceKey.createRegistryKey(ResourceLocation.fromNamespaceAndPath("surwebyor", "nobiomesfound")), Lifecycle.stable())
				}
				.entrySet()
			)
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
		CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
			dispatcher.register(Commands.literal("surwebyor-dump-biomes").executes {
				context -> val sender = context.source
				if (FabricLoader.getInstance().environmentType == EnvType.SERVER) {
					sender.sendFailure(Component.literal("You must be in singleplayer for this to make any sense."))
					return@executes 0
				}
				try {
					sender.sendSystemMessage(Component.literal("Trying to save a new config, with all biomes of this world..."))
					FabricLoader.getInstance().configDir.resolve(CONFIGNAME).toFile().writeText(JSON.encodeToString(CONFIG))
					sender.sendSuccess({
						return@sendSuccess Component.literal("New Surwebyor config saved!")
					}, true)
					return@executes 1
				} catch (_: Throwable) {
					sender.sendFailure(Component.literal("Something went wrong when saving the file! Make sure you have correct permissions (in your filesystem, not in-game), that the config folder exists, that there's enough space on your drive, etc."))
					return@executes 0
				}
			})
		}

		LOGGER.info("Surwebyor is done starting.")
	}

	private fun fillBiomes(registryEntries: Set<Map.Entry<ResourceKey<Biome>, Biome>>) {
		val knownBiomes = HashMap<String, BiomeColor>()
		val env = FabricLoader.getInstance().environmentType
		if (env == EnvType.SERVER) for ((id, colors) in CONFIG.biomes) knownBiomes[id] = colors

		for ((key, biome) in registryEntries) {
			BIOMES[biome] = knownBiomes.getOrPut(key.location().toString()) {
				return@getOrPut when(env) {
                    EnvType.CLIENT -> BiomeColor(
						RGB.of(biome.getGrassColor(1.0, 1.0), false),
						RGB.of(biome.foliageColor, false)
					)
                    EnvType.SERVER -> {
						LOGGER.warn("Your Surwebyor config doesn't contain any biome color information for $key and you're on a server, where that information cannot be obtained. Falling back on vanilla green colors for that biome.")
							/*return@when*/ BiomeColor(
							RGB.of(MapColor.GRASS.calculateRGBColor(MapColor.Brightness.HIGH), true),
							RGB.of(MapColor.PLANT.calculateRGBColor(MapColor.Brightness.HIGH), true)
						)
					}
                }
			}
        }

		if (env == EnvType.CLIENT) {
			val discoveredBiomes = Array(knownBiomes.size) {
				return@Array BiomeColorEntry("PLACEHOLDER", BiomeColor(RGB.of(), RGB.of()))
			}

			for ((i, biome) in knownBiomes.entries.withIndex()) {
				discoveredBiomes[i] = BiomeColorEntry(biome.key, biome.value)
			}

			CONFIG = Config(CONFIG.port, CONFIG.defaultX, CONFIG.defaultZ, CONFIG.title, CONFIG.prefix, discoveredBiomes)
		}
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

	fun getBiomeColor(biome: Biome): BiomeColor = BIOMES.getOrPut(biome) {
		LOGGER.error("Surwebyor doesn't have any biome color information for $biome, which means it must somehow not be in the biome registry, or it must've been added to it after the world was already loaded and all registries are supposedly frozen. Some other mod must be doing some serious shenanigans with registration - EXPECT POTENTIAL INSTABILITY! Anyway, falling back on vanilla green colors for that biome.")
		return@getOrPut BiomeColor(
			RGB.of(MapColor.GRASS.calculateRGBColor(MapColor.Brightness.HIGH), true),
			RGB.of(MapColor.PLANT.calculateRGBColor(MapColor.Brightness.HIGH), true)
		)
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