package tech.toparvion.util.jcudos.subcommand;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.util.*;

import static java.lang.System.Logger.Level.*;
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
  private static final System.Logger log = System.getLogger(Estimate.class.getSimpleName());

  private enum SourceType {
    SHARED,
    FILE,
    JAR,
    JRT,
    OTHER
  } 
  private static Map<String, SourceType> SOURCE_TYPE_MARKERS = Map.of(
          "source: shared", SourceType.SHARED, 
          "source: file:", SourceType.FILE,
          "source: jar:", SourceType.JAR,
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
      log.log(INFO, "Estimating AppCDS efficiency by Glob pattern ''{0}'' in directory ''{1}''...",
          classLoadLogGlob, root);
      PathMatcher pathMatcher = FileSystems.getDefault().getPathMatcher("glob:" + classLoadLogGlob);
      IntSummaryStatistics stats = Files.walk(root)
              .filter(pathMatcher::matches)
              .mapToInt(this::estimate)
              .filter(percent -> percent != 0)
              .summaryStatistics();
      log.log(INFO,"Stats: min={0}%, max={1}%, cnt={2}", stats.getMin(), stats.getMax(), stats.getCount());
      log.log(INFO,"Estimating took {0} ms.", ManagementFactory.getRuntimeMXBean().getUptime());
      log.log(INFO,"Average shared part: {0}%", stats.getAverage());
  
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private int estimate(Path classLoadLogPath) {
    try {
      log.log(INFO, "File: {0}", classLoadLogPath);
      TreeMap<SourceType, Integer> localStats = Files.readAllLines(classLoadLogPath)
              .stream()
              .filter(this::relevantLinesOnly)
              .collect(groupingBy(this::detectSourceType,
                      () -> new TreeMap<>(Comparator.comparingInt(SourceType::ordinal)),
                      collectingAndThen(toList(), List::size)));
      int sharedCount = localStats.getOrDefault(SourceType.SHARED, 0);
      int totalCount = localStats.values().stream().mapToInt(Integer::intValue).sum();
      int sharedPart = Math.round(((float)sharedCount / totalCount) * 100f);
      var sb = new StringBuilder("Class sources distribution:\n");
      localStats.forEach((type, count) -> sb.append(type).append(" -> ").append(count).append('\n'));
      log.log(INFO, "{0}\nShared part: {1}%", sb.toString(), sharedPart);
      return sharedPart;

    } catch (Exception e) {
      e.printStackTrace();
      return 0;
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
