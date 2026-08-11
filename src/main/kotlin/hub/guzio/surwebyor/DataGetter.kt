package hub.guzio.surwebyor

import folk.sisby.surveyor.WorldSummary
import io.nayuki.png.image.BufferedRgbaImage
import net.fabricmc.api.EnvType
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.material.MapColor
import java.util.Objects


object DataGetter {
    const val FIXED_POINT_PRECISION = 100

    fun getImgOfChunk(dim: Level, coords: ChunkPos, zoom: Int): BufferedRgbaImage? {
        if (zoom > 4) return null
        if (zoom < 4){
            val topLeftCorner = getImgOfChunk(dim, ChunkPos(2*coords.x, 2*coords.z), zoom+1)
            val topRightCorner = getImgOfChunk(dim, ChunkPos(2*coords.x+1, 2*coords.z), zoom+1)
            val bottomLeftCorner = getImgOfChunk(dim, ChunkPos(2*coords.x, 2*coords.z+1), zoom+1)
            val bottomRightCorner = getImgOfChunk(dim, ChunkPos(2*coords.x+1, 2*coords.z+1), zoom+1)

            if (Objects.isNull(topLeftCorner) && Objects.isNull(topRightCorner) && Objects.isNull(bottomLeftCorner) && Objects.isNull(bottomRightCorner)) return null

            val img = squishImgOntoImg(topLeftCorner, BufferedRgbaImage(16, 16, arrayOf(8, 8, 8, 0).toIntArray()), 0, 0)
            squishImgOntoImg(topRightCorner, img, 8, 0)
            squishImgOntoImg(bottomLeftCorner, img, 0, 8)
            return squishImgOntoImg(bottomRightCorner, img, 8, 8);
        }

        val map = WorldSummary.of(dim).terrain()
        val min = dim.minBuildHeight
        val max = dim.maxBuildHeight
        val terrainMap = map?.get(coords)?.toSingleLayer(min, max, max)

        if (Objects.isNull(terrainMap)) return null
        val depthMap = terrainMap!!.depths
        val biomeMap = terrainMap.biomes
        val blockMap = terrainMap.blocks
        val waterMap = terrainMap.waterDepths
        val existenceMap = terrainMap.exists
        val biomePalette = map.getBiomePalette(coords)
        val blockPalette = map.getBlockPalette(coords)

        val scalingFactor = 255*FIXED_POINT_PRECISION / (max-min)
        val img = BufferedRgbaImage(16, 16, arrayOf(8, 8, 8, 0).toIntArray())

        for ((index, depth) in depthMap.withIndex()) {
            if (!existenceMap[index]) continue
            val colorBase = if (waterMap[index] > 0) RGB.of(biomePalette.byId(biomeMap[index])!!.waterColor, false) //We can't just rely on MapColor.WATER.id check below because Surveyor will cut straight through simple water blocks and return the seabed instead.
            else {
                val mapColor = blockPalette.byId(blockMap[index])!!.defaultMapColor()
                when (mapColor.id) {
                    MapColor.WATER.id -> RGB.of(biomePalette.byId(biomeMap[index])!!.waterColor, false)
                    MapColor.GRASS.id -> getBiomeColor(biomePalette.byId(biomeMap[index])!!).grass
                    MapColor.PLANT.id -> getBiomeColor(biomePalette.byId(biomeMap[index])!!).plants
                    MapColor.NONE.id  -> RGB.of()
                    else -> RGB.of(mapColor.calculateRGBColor(MapColor.Brightness.HIGH), true)
                }
            }
            val yLevel = max - depth
            val yFromBottom = yLevel-min
            val yScaled = yFromBottom*scalingFactor/FIXED_POINT_PRECISION
            img.setPixel(index/16, index%16, colorBase.tint(yScaled.toUByte()).toPng())
        }

        return img
    }

    fun squishImgOntoImg(source: BufferedRgbaImage?, target: BufferedRgbaImage, xShit: Int, yShift: Int): BufferedRgbaImage {
        var x = 0
        while (x<8) {
            if (Objects.isNull(source)) break
            var y = 0
            while (y<8) {
                val topLeftPixel = RGB.of(source!!.getPixel(x*2, y*2))
                val topRightPixel = RGB.of(source.getPixel(x*2+1, y*2))
                val bottomLeftPixel = RGB.of(source.getPixel(x*2, y*2+1))
                val bottomRightPixel = RGB.of(source.getPixel(x*2+1, y*2+1))
                target.setPixel(x+xShit, y+yShift, RGB.of(arrayOf(topLeftPixel, topRightPixel, bottomLeftPixel, bottomRightPixel)).toPng())
                y++
            }
            x++
        }
        return target
    }

    fun getBiomeColor(biome: Biome): BiomeEntryProcessed {
        return if (FabricLoader.getInstance().environmentType == EnvType.CLIENT) BiomeEntryProcessed(
            RGB.of(biome.getGrassColor(1.0, 1.0), false), //„And though we're not sure what that data means...” ...We know it's multiplied by 0.0225 each (see: Go to Definition). So small values will probably be fine. I hope so.
            RGB.of(biome.foliageColor, false)
        )
        else BiomeEntryProcessed(
            RGB.of(MapColor.GRASS.calculateRGBColor(MapColor.Brightness.HIGH), true),
            RGB.of(MapColor.PLANT.calculateRGBColor(MapColor.Brightness.HIGH), true)
        )
    }
}