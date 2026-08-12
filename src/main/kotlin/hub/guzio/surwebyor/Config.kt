package hub.guzio.surwebyor

import kotlinx.serialization.Serializable

@Serializable
data class Config(val port: Int, val defaultX: Int, val defaultZ: Int, val title: String, val prefix: String, val biomes: Array<BiomeColorEntry>){
    fun applyOntoSite(site: String): String = site.replace("\$PAGETITLE", title).replace("\$POSX", defaultX.toString()).replace("\$POSZ", defaultZ.toString()).replace("\$PREFIX", prefix)
}

@Serializable
data class BiomeColorEntry(val id: String, val colors: BiomeColor)

@Serializable
data class BiomeColor(val grass: RGB, val plants: RGB)