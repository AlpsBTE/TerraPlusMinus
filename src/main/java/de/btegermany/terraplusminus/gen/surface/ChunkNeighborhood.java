package de.btegermany.terraplusminus.gen.surface;

import lombok.NonNull;
import net.buildtheearth.terraminusminus.generator.CachedChunkData;

import java.util.function.BiFunction;

import static net.buildtheearth.terraminusminus.substitutes.ChunkPos.blockToCube;
import static net.buildtheearth.terraminusminus.substitutes.ChunkPos.cubeToMinBlock;

/**
 * A lazily loaded 3x3 chunk data neighborhood around a center chunk.
 * This makes slope computation near chunk borders as accurate as in the chunk interior.
 */
public class ChunkNeighborhood implements Slope.HeightField {
    private static final int SIZE = 3;

    private final int centerChunkX;
    private final int centerChunkZ;
    private final BiFunction<Integer, Integer, CachedChunkData> chunkLoader;
    private final CachedChunkData[][] chunks = new CachedChunkData[SIZE][SIZE];

    public ChunkNeighborhood(int centerChunkX, int centerChunkZ, @NonNull CachedChunkData centerChunk, @NonNull BiFunction<Integer, Integer, CachedChunkData> chunkLoader) {
        this.centerChunkX = centerChunkX;
        this.centerChunkZ = centerChunkZ;
        this.chunkLoader = chunkLoader;
        this.chunks[1][1] = centerChunk;
    }

    @Override
    public int height(int blockX, int blockZ) {
        int chunkX = blockToCube(blockX);
        int chunkZ = blockToCube(blockZ);
        int indexX = chunkX - this.centerChunkX + 1;
        int indexZ = chunkZ - this.centerChunkZ + 1;
        if (indexX < 0 || indexX >= SIZE || indexZ < 0 || indexZ >= SIZE) {
            throw new IndexOutOfBoundsException("block " + blockX + "/" + blockZ + " is outside of the 3x3 chunk neighborhood around chunk " + this.centerChunkX + "/" + this.centerChunkZ);
        }
        CachedChunkData chunk = this.chunks[indexX][indexZ];
        if (chunk == null) {
            chunk = this.chunkLoader.apply(chunkX, chunkZ);
            this.chunks[indexX][indexZ] = chunk;
        }
        return chunk.groundHeight(blockX - cubeToMinBlock(chunkX), blockZ - cubeToMinBlock(chunkZ));
    }
}
