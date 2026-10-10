package ac.grim.grimac.benchmark.chunks;

import ac.grim.grimac.utils.chunks.ChunkSectionCache;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.world.chunk.BaseChunk;
import com.github.retrooper.packetevents.protocol.world.chunk.impl.v_1_18.Chunk_v1_18;
import com.github.retrooper.packetevents.protocol.world.chunk.palette.PaletteType;

import java.util.Random;

/**
 * Micro-benchmarks for single-section operations of {@link ChunkSectionCache}.
 *
 * <p>Sections are filled with raw palette writes so no PacketEvents server mappings are
 * needed; the read path being measured ({@code getBlockId}) is identical to production.
 * Run with {@code ./gradlew :common:runSectionBench}.
 */
public class SectionBench {
    static final ClientVersion VERSION = ClientVersion.V_1_21;
    static final int WARMUP = 300;
    static final int ITERS = 2000;

    static BaseChunk newSection() {
        return new Chunk_v1_18(VERSION, 0, 0, PaletteType.CHUNK.create(), PaletteType.BIOME.create());
    }

    static void rawSet(BaseChunk section, int x, int y, int z, int id) {
        ((Chunk_v1_18) section).getChunkData().set(x, y, z, id);
    }

    static void realisticBlockCounts(BaseChunk... sections) {
        // Raw palette writes do not maintain blockCount; restore plausible values
        // so isEmpty() behaves like packet-decoded sections.
        for (BaseChunk section : sections) {
            int count = 0;
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        if (section.getBlockId(x, y, z) != 0) count++;
                    }
                }
            }
            ((Chunk_v1_18) section).setBlockCount(count);
        }
    }

    static BaseChunk airSection() {
        return newSection();
    }

    static BaseChunk stoneSection() {
        BaseChunk section = newSection();
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    rawSet(section, x, y, z, 1);
                }
            }
        }
        return section;
    }

    static BaseChunk overworldMixed(Random random) {
        BaseChunk section = newSection();
        int[] ids = {1, 1, 1, 1, 2, 3, 4, 5};
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    int id = ids[random.nextInt(ids.length)];
                    if (random.nextDouble() < 0.08) id = 0; // caves
                    rawSet(section, x, y, z, id);
                }
            }
        }
        return section;
    }

    static BaseChunk surfaceSection(Random random) {
        BaseChunk section = newSection();
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    int id;
                    if (y > 10) id = 0;
                    else if (y == 10) id = 6; // grass
                    else if (y >= 7) id = 2; // dirt
                    else id = random.nextDouble() < 0.02 ? 4 : 1; // stone + ore
                    rawSet(section, x, y, z, id);
                }
            }
        }
        return section;
    }

    static BaseChunk copyVia(BaseChunk source) {
        // Local copy through the same raw path; production copySection() additionally
        // resolves block states, so this is a lower bound of its cost.
        BaseChunk empty = newSection();
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    rawSet(empty, x, y, z, source.getBlockId(x, y, z));
                }
            }
        }
        return empty;
    }

    static void bench(String name, Runnable fn) {
        for (int i = 0; i < WARMUP; i++) fn.run();
        long start = System.nanoTime();
        for (int i = 0; i < ITERS; i++) fn.run();
        System.out.printf("[BENCH] %-26s %8.2f us/op%n", name, (System.nanoTime() - start) / 1000.0 / ITERS);
    }

    public static void main(String[] args) {
        Random random = new Random(42);
        BaseChunk air = airSection();
        BaseChunk stone = stoneSection();
        BaseChunk mixed = overworldMixed(random);
        BaseChunk surface = surfaceSection(random);
        BaseChunk mixed2 = overworldMixed(new Random(43));
        realisticBlockCounts(stone, mixed, surface, mixed2);
        BaseChunk mixedCopy = copyVia(mixed);
        realisticBlockCounts(mixedCopy);

        bench("hash/air", () -> ChunkSectionCache.hashSection(air));
        bench("hash/stone", () -> ChunkSectionCache.hashSection(stone));
        bench("hash/mixed", () -> ChunkSectionCache.hashSection(mixed));
        bench("hash/surface", () -> ChunkSectionCache.hashSection(surface));
        bench("rawhash/mixed", () -> ChunkSectionCache.optimizedHashSection(mixed));
        bench("rawhash/surface", () -> ChunkSectionCache.optimizedHashSection(surface));
        bench("equal/same-ref", () -> ChunkSectionCache.sectionsEqual(mixed, mixed));
        bench("equal/same-content", () -> ChunkSectionCache.sectionsEqual(mixed, mixedCopy));
        bench("equal/different", () -> ChunkSectionCache.sectionsEqual(mixed, mixed2));
        bench("copy/mixed", () -> copyVia(mixed));

        BaseChunk[] column = new BaseChunk[24];
        column[0] = airSection();
        column[1] = stoneSection();
        column[2] = surfaceSection(new Random(1));
        for (int i = 3; i < 24; i++) column[i] = overworldMixed(new Random(i));
        realisticBlockCounts(column);
        bench("column-24x/hashAll", () -> {
            for (BaseChunk section : column) ChunkSectionCache.hashSection(section);
        });
        bench("column-24x/rawHashAll", () -> {
            for (BaseChunk section : column) ChunkSectionCache.optimizedHashSection(section);
        });
    }
}
