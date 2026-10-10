package ac.grim.grimac.benchmark.chunks;

import ac.grim.grimac.utils.chunks.ChunkSectionCache;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.world.chunk.BaseChunk;
import com.github.retrooper.packetevents.protocol.world.chunk.impl.v_1_18.Chunk_v1_18;
import com.github.retrooper.packetevents.protocol.world.chunk.palette.PaletteType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.ToLongFunction;

/**
 * Baseline ({@link BaselineCache}) vs live {@link ChunkSectionCache} on identical
 * workloads: 17x17 columns x 24 sections, uniform and reshuffled encodings.
 *
 * <p>Both sides go through their real {@code internRef}/{@code release} methods.
 * Best of several interleaved passes. Run with {@code ./gradlew :common:runCompareBench}.
 */
public class CompareBench {
    static final ClientVersion VERSION = ClientVersion.V_1_21;
    static final int SIZE = 17;
    static final int SECTIONS = 24;
    static final int PASSES = 5;

    record Interned(BaseChunk section, long key) {
    }

    interface Impl {
        String name();

        ToLongFunction<BaseChunk> hasher();

        BiPredicate<BaseChunk, BaseChunk> equalsFn();

        Interned intern(BaseChunk section);

        void release(Interned ref);
    }

    static final Impl BASELINE = new Impl() {
        final BaselineCache cache = BaselineCache.getInstance();

        public String name() {
            return "baseline";
        }

        public ToLongFunction<BaseChunk> hasher() {
            return BaselineCache::hashSection;
        }

        public BiPredicate<BaseChunk, BaseChunk> equalsFn() {
            return BaselineCache::sectionsEqual;
        }

        public Interned intern(BaseChunk section) {
            BaselineCache.SharedRef ref = cache.internRef(section);
            return new Interned(ref.section(), ref.key());
        }

        public void release(Interned ref) {
            cache.release(ref.key(), ref.section());
        }
    };

    static final Impl LIVE = new Impl() {
        public String name() {
            return "live    ";
        }

        public ToLongFunction<BaseChunk> hasher() {
            // internRef() itself uses the optimized hash; audit with the same function.
            return ChunkSectionCache::optimizedHashSection;
        }

        public BiPredicate<BaseChunk, BaseChunk> equalsFn() {
            return ChunkSectionCache::sectionsEqual;
        }

        public Interned intern(BaseChunk section) {
            ChunkSectionCache.SharedRef ref = ChunkSectionCache.internRef(section);
            return new Interned(ref.section(), ref.key());
        }

        public void release(Interned ref) {
            ChunkSectionCache.release(ref.key(), ref.section());
        }
    };

    static BaseChunk newSection() {
        return new Chunk_v1_18(VERSION, 0, 0, PaletteType.CHUNK.create(), PaletteType.BIOME.create());
    }

    static int blockAt(int x, int y, int z, int h, Random random) {
        if (y == -64) return 7;
        if (y > h) return 0;
        if (y == h) return 6;
        if (y > h - 4) return 2;
        double value = random.nextDouble();
        if (value < 0.05) return 0;
        if (value < 0.065) return 4;
        return 1;
    }

    static int heightAt(int x, int z) {
        return 64 + (int) (12 * Math.sin(x * 0.15) * Math.cos(z * 0.13))
                + (int) (6 * Math.sin(x * 0.05 + 2) * Math.cos(z * 0.07));
    }

    static List<BaseChunk> build(long seed, boolean reverse) {
        Random random = new Random(seed);
        int total = SIZE * SIZE * SECTIONS;
        int[][][] ids = new int[total][16][256];
        int c = 0;
        for (int cx = 0; cx < SIZE; cx++) {
            for (int cz = 0; cz < SIZE; cz++) {
                for (int s = 0; s < SECTIONS; s++) {
                    int baseY = -64 + s * 16;
                    for (int y = 0; y < 16; y++) {
                        for (int z = 0; z < 16; z++) {
                            for (int x = 0; x < 16; x++) {
                                ids[c][y][z * 16 + x] = blockAt(cx * 16 + x, baseY + y, cz * 16 + z,
                                        heightAt(cx * 16 + x, cz * 16 + z), random);
                            }
                        }
                    }
                    c++;
                }
            }
        }
        List<BaseChunk> out = new ArrayList<>(total);
        for (int i = 0; i < total; i++) {
            BaseChunk section = newSection();
            int count = 0;
            if (!reverse) {
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        for (int x = 0; x < 16; x++) {
                            int id = ids[i][y][z * 16 + x];
                            if (id != 0) count++;
                            ((Chunk_v1_18) section).getChunkData().set(x, y, z, id);
                        }
                    }
                }
            } else {
                for (int y = 15; y >= 0; y--) {
                    for (int z = 15; z >= 0; z--) {
                        for (int x = 15; x >= 0; x--) {
                            int id = ids[i][y][z * 16 + x];
                            if (id != 0) count++;
                            ((Chunk_v1_18) section).getChunkData().set(x, y, z, id);
                        }
                    }
                }
            }
            ((Chunk_v1_18) section).setBlockCount(count);
            out.add(section);
        }
        return out;
    }

    static long bestNs(Runnable runnable) {
        for (int i = 0; i < 500; i++) runnable.run();
        long best = Long.MAX_VALUE;
        for (int i = 0; i < 2000; i++) {
            long start = System.nanoTime();
            runnable.run();
            best = Math.min(best, System.nanoTime() - start);
        }
        return best;
    }

    public static void main(String[] args) {
        Impl[] impls = {BASELINE, LIVE};
        List<BaseChunk> player1 = build(1000, false);
        List<BaseChunk> player2uniform = build(1000, false);
        List<BaseChunk> player2mixed = build(1000, true);
        System.out.printf("sections per corpus: %d (%dx%dx%d)%n", player1.size(), SIZE, SIZE, SECTIONS);

        BaseChunk air = null;
        BaseChunk solid = null;
        BaseChunk solidCopy = null;
        for (BaseChunk section : player1) {
            if (air == null && section.isEmpty()) air = section;
            if (solid == null && !section.isEmpty()) {
                solid = section;
                solidCopy = copyOf(section);
            }
            if (air != null && solid != null) break;
        }
        final BaseChunk finalAir = air;
        final BaseChunk finalSolid = solid;
        final BaseChunk finalCopy = solidCopy;
        BaseChunk other = null;
        for (BaseChunk section : player1) {
            if (section != solid && !section.isEmpty()) {
                other = section;
                break;
            }
        }
        final BaseChunk finalOther = other;

        System.out.println("== per-section (ns/op, best) ==");
        for (Impl impl : impls) {
            System.out.printf("%s hash/air %6d | hash/solid %6d | equal/same %6d | equal/different %6d%n",
                    impl.name(), bestNs(() -> impl.hasher().applyAsLong(finalAir)),
                    bestNs(() -> impl.hasher().applyAsLong(finalSolid)),
                    bestNs(() -> impl.equalsFn().test(finalSolid, finalCopy)),
                    bestNs(() -> impl.equalsFn().test(finalSolid, finalOther)));
        }

        runScenario("uniform/uniform", impls, player1, player2uniform, true);
        runScenario("uniform/mixed    ", impls, player1, player2mixed, false);
    }

    static void runScenario(String label, Impl[] impls, List<BaseChunk> player1, List<BaseChunk> player2,
                            boolean assertSameDecisions) {
        System.out.println("== " + label + " (best of " + PASSES + ") ==");
        for (Impl impl : impls) {
            List<Interned> warm = new ArrayList<>(player1.size());
            for (BaseChunk section : player1) warm.add(impl.intern(section));
            for (Interned ref : warm) impl.release(ref);
        }
        Map<String, long[]> stats = new HashMap<>();
        Map<String, boolean[]> decisions = new HashMap<>();
        Map<String, Long> combined = new HashMap<>();
        for (int i = 0; i < PASSES; i++) {
            for (Impl impl : impls) {
                List<Interned> refs = new ArrayList<>(player1.size());
                long start = System.nanoTime();
                for (BaseChunk section : player1) refs.add(impl.intern(section));
                long cold = System.nanoTime() - start;
                Set<BaseChunk> entries = identitySet(refs);
                for (Interned ref : refs) impl.release(ref);

                List<Interned> refs2 = new ArrayList<>(player2.size());
                // Repopulate, then measure the hot pass so every pass starts equal.
                List<Interned> held = new ArrayList<>(player1.size());
                for (BaseChunk section : player1) held.add(impl.intern(section));
                start = System.nanoTime();
                for (BaseChunk section : player2) refs2.add(impl.intern(section));
                long hot = System.nanoTime() - start;
                if (i == 0) {
                    boolean[] shared = new boolean[player2.size()];
                    for (int j = 0; j < player2.size(); j++) {
                        shared[j] = refs2.get(j).section() != player2.get(j);
                    }
                    decisions.put(impl.name(), shared);
                    List<Interned> both = new ArrayList<>(held.size() + refs2.size());
                    both.addAll(held);
                    both.addAll(refs2);
                    combined.put(impl.name(), (long) identitySet(both).size());
                }
                for (Interned ref : refs2) impl.release(ref);
                for (Interned ref : held) impl.release(ref);

                stats.compute(impl.name(), (k, v) -> {
                    if (v == null) return new long[]{cold, hot, entries.size()};
                    v[0] = Math.min(v[0], cold);
                    v[1] = Math.min(v[1], hot);
                    return v;
                });
            }
        }
        int chunks = SIZE * SIZE;
        for (Impl impl : impls) {
            long[] stat = stats.get(impl.name());
            System.out.printf("%s cold %7.1f ms (%5.2f us/section, %6.1f us/chunk)"
                            + " | hot %7.1f ms (%5.2f us/section, %6.1f us/chunk) | entries=%d combined=%d%n",
                    impl.name(), stat[0] / 1e6, stat[0] / 1000.0 / player1.size(), stat[0] / 1000.0 / chunks,
                    stat[1] / 1e6, stat[1] / 1000.0 / player1.size(), stat[1] / 1000.0 / chunks, stat[2],
                    combined.get(impl.name()));
        }
        if (assertSameDecisions) {
            boolean[] a = decisions.get(impls[0].name());
            boolean[] b = decisions.get(impls[1].name());
            for (int j = 0; j < a.length; j++) {
                if (a[j] != b[j]) throw new AssertionError("diverging share decisions at " + j);
            }
            System.out.println("identical share/private decisions: OK");
        }
    }

    static Set<BaseChunk> identitySet(List<Interned> refs) {
        Set<BaseChunk> set = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        for (Interned ref : refs) set.add(ref.section());
        return set;
    }

    static BaseChunk copyOf(BaseChunk source) {
        BaseChunk section = newSection();
        int count = 0;
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    int id = source.getBlockId(x, y, z);
                    if (id != 0) count++;
                    ((Chunk_v1_18) section).getChunkData().set(x, y, z, id);
                }
            }
        }
        ((Chunk_v1_18) section).setBlockCount(count);
        return section;
    }
}
