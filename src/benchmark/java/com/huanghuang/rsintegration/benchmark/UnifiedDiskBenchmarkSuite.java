package com.huanghuang.rsintegration.benchmark;

import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.profile.GCProfiler;
import org.openjdk.jmh.results.format.ResultFormatType;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;

import java.nio.file.Files;
import java.nio.file.Path;

/** 按顺序执行，避免多个基准进程争用 CPU；每个组合仍在两个独立 JVM 中预热。 */
public final class UnifiedDiskBenchmarkSuite {
    private UnifiedDiskBenchmarkSuite() {}

    private static void run(Path directory, String file, String method, String[] entries, String[] kinds,
                            String profile, int payload, String[] implementations, Mode mode) throws Exception {
        var options = new OptionsBuilder().include("UnifiedDiskBenchmark\\." + method + "$")
                .param("entries", entries).param("kind", kinds).param("profile", profile)
                .param("payloadBytes", Integer.toString(payload)).param("implementation", implementations)
                .forks(2).warmupIterations(3).warmupTime(TimeValue.milliseconds(500))
                .measurementIterations(5).measurementTime(TimeValue.milliseconds(500))
                .mode(mode).threads(1).shouldFailOnError(true).addProfiler(GCProfiler.class)
                .resultFormat(ResultFormatType.JSON).result(directory.resolve(file).toString()).build();
        System.out.println("SUITE START " + file);
        new Runner(options).run();
        System.out.println("SUITE COMPLETE " + file);
    }

    private static void workload(Path directory, String file, String method, int entries, String[] kinds,
                                 String profile, String access, String[] amounts, int initial,
                                 String[] implementations, Mode mode) throws Exception {
        var options = new OptionsBuilder().include("UnifiedDiskWorkloadBenchmark\\." + method + "$")
                .param("entries", Integer.toString(entries)).param("kind", kinds).param("profile", profile)
                .param("payloadBytes", "0").param("implementation", implementations).param("access", access)
                .param("initialAmount", Integer.toString(initial))
                .forks(2).warmupIterations(3).warmupTime(TimeValue.milliseconds(500))
                .measurementIterations(5).measurementTime(TimeValue.milliseconds(500))
                .mode(mode).threads(1).shouldFailOnError(true).addProfiler(GCProfiler.class)
                .resultFormat(ResultFormatType.JSON).result(directory.resolve(file).toString());
        if (amounts != null) options.param("batchAmount", amounts);
        System.out.println("SUITE START " + file);
        new Runner(options.build()).run();
        System.out.println("SUITE COMPLETE " + file);
    }

    public static void main(String[] args) throws Exception {
        Path directory = Path.of(args[0]); Files.createDirectories(directory);
        String[] compare = {"unifiedKeyPath", "rs"};
        if (args.length > 1 && (args[1].equals("optimized") || args[1].equals("comprehensive"))) {
            run(directory, "plain.json", "existing.*", new String[] {"1"}, new String[] {"item", "fluid"},
                    "plain", 0, compare, Mode.AverageTime);
            run(directory, "item-variants.json", "existing.*", new String[] {"50000"}, new String[] {"item"},
                    "variants", 0, compare, Mode.AverageTime);
            run(directory, "fluid-variants.json", "existing.*", new String[] {"262144"}, new String[] {"fluid"},
                    "variants", 0, compare, Mode.AverageTime);
            run(directory, "mixed.json", "existing.*", new String[] {"50000"}, new String[] {"item"},
                    "mixed", 0, compare, Mode.AverageTime);
            run(directory, "large-nbt.json", "existing.*", new String[] {"10000"}, new String[] {"item"},
                    "variants", 4096, compare, Mode.AverageTime);
            run(directory, "new-entry.json", "newEntryInsert", new String[] {"50000"}, new String[] {"item", "fluid"},
                    "variants", 0, compare, Mode.AverageTime);
            if (args[1].equals("comprehensive")) {
                workload(directory, "batch-item.json", "transferPair", 1, new String[] {"item"}, "plain", "hot256",
                        new String[] {"1", "64", "4096"}, 500000000, compare, Mode.AverageTime);
                workload(directory, "batch-fluid.json", "transferPair", 1, new String[] {"fluid"}, "plain", "hot256",
                        new String[] {"1", "1000", "64000"}, 500000000, compare, Mode.AverageTime);
                workload(directory, "uniform-variants.json", "transferPair", 50000, new String[] {"item", "fluid"}, "variants", "full",
                        new String[] {"64"}, 500000000, compare, Mode.AverageTime);
                workload(directory, "uniform-low-total.json", "transferPair", 50000, new String[] {"item"}, "mixed", "full",
                        new String[] {"64", "4096"}, 10000, compare, Mode.AverageTime);
                workload(directory, "materials.json", "transferPair", 512, new String[] {"item"}, "materials", "full",
                        new String[] {"64"}, 10000, compare, Mode.AverageTime);
                workload(directory, "simulation.json", "simulated.*", 50000, new String[] {"item"}, "mixed", "full",
                        new String[] {"64"}, 10000, compare, Mode.AverageTime);
                workload(directory, "miss.json", "missingExtract", 50000, new String[] {"item", "fluid"}, "variants", "hot256",
                        new String[] {"64"}, 10000, compare, Mode.AverageTime);
                workload(directory, "full-reject.json", "rejectedNewKey", 262144, new String[] {"item", "fluid"}, "variants", "hot256",
                        new String[] {"64"}, 500000000, new String[] {"unifiedKeyPath"}, Mode.AverageTime);
                run(directory, "new-plain.json", "newEntryInsert", new String[] {"1"}, new String[] {"item", "fluid"},
                        "plain", 0, compare, Mode.AverageTime);
                run(directory, "new-material.json", "newEntryInsert", new String[] {"511"}, new String[] {"item"},
                        "materials", 0, compare, Mode.AverageTime);
                workload(directory, "cache-item.json", "cacheUpdatePair", 50000, new String[] {"item"}, "mixed", "hot256",
                        null, 10000, compare, Mode.AverageTime);
                workload(directory, "cache-fluid.json", "cacheUpdatePair", 1000, new String[] {"fluid"}, "variants", "hot256",
                        null, 10000, compare, Mode.AverageTime);
                workload(directory, "uniform-percentiles.json", "transferPair", 50000, new String[] {"item"}, "mixed", "full",
                        new String[] {"64"}, 10000, compare, Mode.SampleTime);
                UnifiedDiskGrowthCheck.main(new String[] {directory.resolve("growth.json").toString()});
            }
            UnifiedDiskLimitCheck.main(new String[] {directory.resolve("limits").toString()});
            return;
        }
        run(directory, "plain.json", "existing.*", new String[] {"1"}, new String[] {"item", "fluid"},
                "plain", 0, compare, Mode.AverageTime);
        run(directory, "fluid-variants.json", "existing.*", new String[] {"1000", "262144"}, new String[] {"fluid"},
                "variants", 0, compare, Mode.AverageTime);
        run(directory, "mixed.json", "existing.*", new String[] {"50000"}, new String[] {"item"},
                "mixed", 0, compare, Mode.AverageTime);
        run(directory, "large-nbt.json", "existing.*", new String[] {"1000", "10000"}, new String[] {"item"},
                "variants", 4096, compare, Mode.AverageTime);
        run(directory, "new-entry.json", "newEntryInsert", new String[] {"50000"}, new String[] {"item", "fluid"},
                "variants", 0, compare, Mode.AverageTime);
        run(directory, "core.json", "existing.*", new String[] {"262144"}, new String[] {"item"},
                "variants", 0, new String[] {"unifiedCore"}, Mode.AverageTime);
        run(directory, "insert-percentiles.json", "existingInsert", new String[] {"50000"}, new String[] {"item"},
                "variants", 0, compare, Mode.SampleTime);
        UnifiedDiskLimitCheck.main(new String[] {directory.resolve("limits").toString()});
    }
}
