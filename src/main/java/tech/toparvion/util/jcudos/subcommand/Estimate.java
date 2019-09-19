package tech.toparvion.util.jcudos.subcommand;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.util.*;

import static java.util.stream.Collectors.*;
import static picocli.CommandLine.*;
import static picocli.CommandLine.Help.Visibility.ALWAYS;

/**
 * @author Toparvion
 */
@Command(name = "estimate",
        mixinStandardHelpOptions = true,
        description = "Estimates class load logs for AppCDS efficiency")
public class Estimate implements Runnable {

  private enum SourceType {
    SHARED,
    FILE,
    JRT,
    OTHER
  } 
  private static Map<String, SourceType> SOURCE_TYPE_MARKERS = Map.of(
          "source: shared", SourceType.SHARED, 
          "source: file:", SourceType.FILE,
          "source: jrt:", SourceType.JRT
  );
  @Option(names = {"--root", "-r"}, paramLabel = "<workDir>", showDefaultValue = ALWAYS)
  private Path root = Paths.get(System.getProperty("user.dir"));

  @Parameters(paramLabel = "GLOB", description = "Glob expression describing paths to class load logs")
  private String classLoadLogGlob;  

  @Override
  public void run() {
    try {
      assert root.isAbsolute() : "--root must be absolute if specified";
      System.out.printf("Estimating AppCDS efficiency by Glob pattern '%s' in directory '%s'...\n", classLoadLogGlob, root);
      PathMatcher pathMatcher = FileSystems.getDefault().getPathMatcher("glob:" + classLoadLogGlob);
      IntSummaryStatistics stats = Files.walk(root)
              .filter(pathMatcher::matches)
              .mapToInt(this::estimate)
              .filter(percent -> percent != 0)
              .summaryStatistics();
      System.out.printf("Stats: min=%d%%, max=%d%%, cnt=%d\n", stats.getMin(), stats.getMax(), stats.getCount());
      System.out.printf("Estimating took %d ms.\n", ManagementFactory.getRuntimeMXBean().getUptime());
      System.out.printf("Average shared part: %.0f%%\n", stats.getAverage());
  
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private int estimate(Path classLoadLogPath) {
    try {
      System.out.printf("File: %s\n", classLoadLogPath);
      TreeMap<SourceType, Integer> localStats = Files.readAllLines(classLoadLogPath)
              .stream()
              .filter(this::relevantLinesOnly)
              .collect(groupingBy(this::detectSourceType,
                      () -> new TreeMap<>(Comparator.comparingInt(SourceType::ordinal)),
                      collectingAndThen(toList(), List::size)));
      int sharedCount = localStats.getOrDefault(SourceType.SHARED, 0);
      int totalCount = localStats.values().stream().mapToInt(Integer::intValue).sum();
      int sharedPart = Math.round(((float)sharedCount / totalCount) * 100f);
      // System.out.println("============================================================");
      localStats.forEach((type, count) -> System.out.printf("%s -> %d\n", type, count));
      System.out.printf("Shared part: %d%%\n", sharedPart);
      return sharedPart;

    } catch (Exception e) {
      e.printStackTrace();
      return 0;
      
    } finally {
      System.out.println("============================================================");       
    }
  }

  private boolean relevantLinesOnly(String line) {
    return line.contains(" source: ");
  }

  private SourceType detectSourceType(String classLoadRecord) {
    return SOURCE_TYPE_MARKERS.entrySet()
            .stream()
            .filter(entry -> classLoadRecord.contains(entry.getKey()))
            .findAny()
            .map(Map.Entry::getValue)
            .orElse(SourceType.OTHER);
  } 
}
