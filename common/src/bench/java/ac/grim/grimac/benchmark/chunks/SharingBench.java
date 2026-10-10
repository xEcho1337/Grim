package ac.grim.grimac.benchmark.chunks;

import ac.grim.grimac.utils.chunks.ChunkSectionCache;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.world.chunk.BaseChunk;
import com.github.retrooper.packetevents.protocol.world.chunk.impl.v_1_18.Chunk_v1_18;
import com.github.retrooper.packetevents.protocol.world.chunk.palette.PaletteType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * End-to-end benchmark of the live {@link ChunkSectionCache} on realistic terrain:
 * 16x16 columns x 24 sections of layered terrain, several players loading identical
 * bytes as fresh objects (like the server sends them), plus integrity audits
 * (no hash collisions, no false sharing, refcount eviction).
 *
 * <p>Run with {@code ./gradlew :common:runSharingBench}.
 */
public class SharingBench {
    static final ClientVersion VERSION = ClientVersion.V_1_21;
    static final int CHUNKS = 16;
    static final int SECTIONS = 24;
    static final int PASSES = 5;

    static BaseChunk newSection() {
        return new Chunk_v1_18(VERSION, 0, 0, PaletteType.CHUNK.create(), PaletteType.BIOME.create());
    }

    // Realistic ids: 0 air, 1 stone, 2 dirt, 6 grass, 4 ore, 7 bedrock.
    static int blockAt(int x, int y, int z, int h, Random random) {
        if (y == -64) return 7;
        if (y > h) return 0;
        if (y == h) return 6;
        if (y > h - 4) return 2;
        double value = random.nextDouble();
        if (value < 0.05) return 0; // caves
        if (value < 0.065) return 4; // ores
        return 1;
    }

    static int heightAt(int x, int z) {
        return 64
                + (int) (12 * Math.sin(x * 0.15) * Math.cos(z * 0.13))
                + (int) (6 * Math.sin(x * 0.05 + 2) * Math.cos(z * 0.07));
    }

    static List<BaseChunk> buildCorpus(long seed, int[] nonAirOut) {
        Random random = new Random(seed);
        List<BaseChunk> out = new ArrayList<>(CHUNKS * CHUNKS * SECTIONS);
        int nonAir = 0;
        for (int cx = 0; cx < CHUNKS; cx++) {
            for (int cz = 0; cz < CHUNKS; cz++) {
                for (int s = 0; s < SECTIONS; s++) {
                    int baseY = -64 + s * 16;
                    BaseChunk section = newSection();
                    int count = 0;
                    for (int y = 0; y < 16; y++) {
                        for (int z = 0; z < 16; z++) {
                            for (int x = 0; x < 16; x++) {
                                int gx = cx * 16 + x;
                                int gz = cz * 16 + z;
                                int id = blockAt(gx, baseY + y, gz, heightAt(gx, gz), random);
                                if (id != 0) count++;
                                ((Chunk_v1_18) section).getChunkData().set(x, y, z, id);
                            }
                        }
                    }
                    ((Chunk_v1_18) section).setBlockCount(count);
                    if (count > 0) nonAir++;
                    out.add(section);
                }
            }
        }
        nonAirOut[0] = nonAir;
        return out;
    }

    public static void main(String[] args) {
        System.out.println("== SharingBench: full player view (16x16 chunks) through the live cache ==");
        System.out.println("== build corpora (2 players, same bytes, distinct objects) ==");
        int[] nonAir = new int[1];
        List<BaseChunk> player1 = buildCorpus(1000, nonAir);
        List<BaseChunk> player2 = buildCorpus(1000, new int[1]);
        System.out.printf("sections=%d nonAir=%d (air=%.1f%%)%n",
                player1.size(), nonAir[0], 100.0 * (player1.size() - nonAir[0]) / player1.size());

        int unequal = 0;
        for (int i = 0; i < player1.size(); i++) {
            if (!ChunkSectionCache.sectionsEqual(player1.get(i), player2.get(i))) unequal++;
        }
        System.out.println("p1/p2 pairs with different content: " + unequal + " (expected 0)");
        if (unequal != 0) throw new AssertionError("corpora diverged!");

        for (int w = 0; w < 2; w++) {
            List<ChunkSectionCache.SharedRef> refs = new ArrayList<>(player1.size());
            for (BaseChunk section : player1) refs.add(ChunkSectionCache.internRef(section));
            for (ChunkSectionCache.SharedRef ref : refs) ChunkSectionCache.release(ref.key(), ref.section());
        }

        long bestCold = Long.MAX_VALUE;
        for (int i = 0; i < PASSES; i++) {
            List<ChunkSectionCache.SharedRef> refs = new ArrayList<>(player1.size());
            long start = System.nanoTime();
            for (BaseChunk section : player1) refs.add(ChunkSectionCache.internRef(section));
            bestCold = Math.min(bestCold, System.nanoTime() - start);
            for (ChunkSectionCache.SharedRef ref : refs) ChunkSectionCache.release(ref.key(), ref.section());
        }

        Map<Long, BaseChunk> firstByHash = new HashMap<>();
        Map<Long, BaseChunk> canonicalByHash = new HashMap<>();
        List<ChunkSectionCache.SharedRef> held = new ArrayList<>(player1.size());
        int collisions = 0;
        int falseShared = 0;
        int airShared = 0;
        BaseChunk airOne = null;
        for (BaseChunk section : player1) {
            long hash = ChunkSectionCache.optimizedHashSection(section);
            BaseChunk prev = firstByHash.get(hash);
            if (prev != null && prev != section && !ChunkSectionCache.sectionsEqual(prev, section)) {
                collisions++;
                System.out.println("FNV COLLISION on hash " + Long.toHexString(hash));
            }
            firstByHash.putIfAbsent(hash, section);
            ChunkSectionCache.SharedRef ref = ChunkSectionCache.internRef(section);
            held.add(ref);
            BaseChunk canon = canonicalByHash.get(hash);
            if (canon == null) {
                canonicalByHash.put(hash, ref.section());
            } else if (canon != ref.section()
                    && ref.key() != 0L
                    && ChunkSectionCache.sectionsEqual(canon, ref.section())) {
                falseShared++;
                System.out.println("FALSE SHARING on hash " + Long.toHexString(hash));
            }
            if (section.isEmpty()) {
                if (airOne == null) {
                    airOne = ref.section();
                } else if (airOne != ref.section()) {
                    throw new AssertionError("air sections not shared!");
                } else {
                    airShared++;
                }
            }
        }
        System.out.printf("audit: distinctHashes=%d collisions=%d falseSharing=%d airAliased=%d%n",
                firstByHash.size(), collisions, falseShared, airShared);
        if (collisions != 0 || falseShared != 0) throw new AssertionError("integrity violated!");

        long bestHot = Long.MAX_VALUE;
        for (int i = 0; i < PASSES; i++) {
            List<ChunkSectionCache.SharedRef> refs = new ArrayList<>(player2.size());
            long start = System.nanoTime();
            for (BaseChunk section : player2) refs.add(ChunkSectionCache.internRef(section));
            bestHot = Math.min(bestHot, System.nanoTime() - start);
            int aliased = 0;
            for (int j = 0; j < player2.size(); j++) {
                long hash = ChunkSectionCache.optimizedHashSection(player2.get(j));
                if (refs.get(j).section() == canonicalByHash.get(hash)) aliased++;
            }
            if (aliased != player2.size()) throw new AssertionError("hits not aliased: " + aliased);
            for (ChunkSectionCache.SharedRef ref : refs) ChunkSectionCache.release(ref.key(), ref.section());
        }

        for (ChunkSectionCache.SharedRef ref : held) ChunkSectionCache.release(ref.key(), ref.section());
        held.clear();
        BaseChunk probe = player1.get(3000);
        if (probe.isEmpty()) probe = player1.get(1000); // a non-air section
        ChunkSectionCache.SharedRef first = ChunkSectionCache.internRef(probe);
        ChunkSectionCache.release(first.key(), first.section());
        ChunkSectionCache.SharedRef second = ChunkSectionCache.internRef(probe);
        boolean evicted = second.section() == probe; // entry gone => fresh object re-interned
        ChunkSectionCache.release(second.key(), second.section());
        System.out.println("eviction after full release: " + (evicted ? "OK (re-inserted)" : "FAILED (stale!)"));
        if (!evicted) throw new AssertionError("stale entry!");

        System.out.println("== RESULTS (best of " + PASSES + ") ==");
        System.out.printf("cold 16x16x24: %8.1f ms total | %6.2f us/section | %6.1f us/chunk%n",
                bestCold / 1e6, bestCold / 1000.0 / player1.size(), bestCold / 1000.0 / 256);
        System.out.printf("hot  16x16x24: %8.1f ms total | %6.2f us/section | %6.1f us/chunk%n",
                bestHot / 1e6, bestHot / 1000.0 / player2.size(), bestHot / 1000.0 / 256);
        System.out.println("== VERDICT: what does a player join cost? ==");
        System.out.printf("first player loading 256 chunks : %.1f ms spread over 256 packets (%.1f us each)%n",
                bestCold / 1e6, bestCold / 1000.0 / 256);
        System.out.printf("next player, same area         : %.1f ms spread over 256 packets (%.1f us each)%n",
                bestHot / 1e6, bestHot / 1000.0 / 256);
        System.out.printf("safety: %d collisions, %d false shares, air shared x%d, eviction %s%n",
                collisions, falseShared, airShared, evicted ? "OK" : "BROKEN");
    }
}
