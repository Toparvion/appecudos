package tech.toparvion.util.jcudos.subcommand;

import tech.toparvion.util.jcudos.Constants;
import tech.toparvion.util.jcudos.infra.JCudosVersionProvider;
import tech.toparvion.util.jcudos.model.collate.CollationResult;
import tech.toparvion.util.jcudos.model.collate.entry.NestedJarEntry;
import tech.toparvion.util.jcudos.model.collate.entry.PathEntry;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import static java.lang.System.Logger.Level.*;
import static java.util.stream.Collectors.toList;
import static picocli.CommandLine.*;

/**
 * @author Toparvion
 */
@Command(name = "collate",
        mixinStandardHelpOptions = true,
        versionProvider = JCudosVersionProvider.class,
        description = "Collates given lists irrespective to their elements order")
public class Collate implements Callable<CollationResult> {
  private static final System.Logger log = System.getLogger(Collate.class.toString());

  @Parameters(paramLabel = "LISTS", description = "Paths to lists to be analyzed. Can be concrete paths of glob " +
          "patterns pointing to either list files or directories (including fat JARs)")
  private List<String> args;
  
  @Option(names = {"--work-dir", "-w"})
  private Path root = Paths.get(System.getProperty("user.dir"));
  
  @Option(names = {"--exclusion", "-e"})
  private Set<String> exclusionGlobs = new HashSet<>();
  
  @Option(names = {"--merging-out", "-m"})
  private Path mergingOutPath;
  
  @Option(names = {"--intersection-out", "-i"})
  private Path intersectionOutPath;

  /**
   * @apiNote when using it from command line, the option must be set as '--precise-compare' 
   * (not as '--precise-compare true' i.e. no explicit 'true' or 'false' word required)
   */
  @Option(names = {"-p", "--precise-compare"})
  private boolean preciseFileComparisonMode = false;
  
  private Set<PathMatcher> exclusionMatchers = new HashSet<>();

  @Override
  public CollationResult call() {
    long startTime = setup();
    Map<String, List<?>> allEntries = new HashMap<>();
    for (String arg : args) {
      try {
        if (arg.contains("*")) {
          log.log(DEBUG, "Path ''{0}'' contains wildcard(s), will be processed as Glob pattern...", arg);
          PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + arg);
          List<Path> matchedPaths = Files.walk(root)
//                  .filter(path -> matcher.matches(root.relativize(path)))
                  .filter(matcher::matches)
                  .filter(this::filterOutExclusions)
                  .collect(toList());
          for (Path matchedPath : matchedPaths) {
            if (Files.isDirectory(matchedPath)) {
              log.log(DEBUG, "Processing path ''{0}'' as directory...", arg);
              List<PathEntry> dirEntries = getDirFileNames(matchedPath);
              allEntries.put(matchedPath.toString(), dirEntries);
              log.log(INFO, "{0} entries have been put under ''{1}'' dir name", dirEntries.size(), matchedPath);

            } else {
              if (Files.isReadable(matchedPath)) {
                String matchedPathString = matchedPath.toString().toLowerCase();
                if (matchedPathString.endsWith(".jar")) {
                  processFatJar(allEntries, matchedPathString);

                } else {
                  log.log(DEBUG, "Processing path ''{0}'' as plain list file...", matchedPath);
                  List<String> lines = Files.readAllLines(matchedPath);
                  allEntries.put(matchedPath.toString(), lines);
                  log.log(INFO, "{0} lines have been put under ''{1}'' matched file name", lines.size(), matchedPath);
                } 

              } else {
                log.log(WARNING, "Path ''{0}'' doesn't point to existing and readable file. Skipped.", matchedPath);
                allEntries.put(matchedPath.toString(), List.of());
              }
            }
          }

        } else if (arg.toLowerCase().endsWith(".jar")) {
          processFatJar(allEntries, arg);

        } else {
          var concretePath = absolutify(Paths.get(arg));
          if (Files.isDirectory(concretePath)) {
            log.log(DEBUG, "Processing path ''{0}'' as directory...", arg);
            List<PathEntry> dirEntries = getDirFileNames(concretePath);
            allEntries.put(concretePath.toString(), dirEntries);
            log.log(INFO, "{0} entries have been put under ''{1}'' dir concrete name", dirEntries.size(), concretePath);
            
          } else {
            if (Files.isReadable(concretePath)) {
              log.log(DEBUG, "Processing path ''{0}'' as plain list file...", concretePath);
              List<String> lines = Files.readAllLines(concretePath);
              allEntries.put(arg, lines);
              log.log(INFO, "{0} lines have been put under ''{1}'' concrete file name", lines.size(), concretePath);
              
            } else {
              log.log(WARNING,"Path ''{0}'' doesn't point to existing and readable file. Skipped.", concretePath);
              allEntries.put(concretePath.toString(), List.of());
            } 
          } 
        }
        
      } catch (IOException e) {
        log.log(ERROR, "Failed to process argument ''{0}''. Skipped.", arg);
        e.printStackTrace();
      }
    }
    log.log(INFO, "Loaded {0} lists", allEntries.size());
    if (allEntries.isEmpty()) {
      return null;
    }
    
    // do the collation itself
    CollationResult collationResult = collate(allEntries);

    // merging output
    if (mergingOutPath != null) {
      mergingOutPath = absolutify(mergingOutPath);
      try {
        Set<String> merging = collationResult.getMerging();
        Files.write(mergingOutPath, merging);
        log.log(INFO, "Merging result ({0} items) has been written to ''{1}''", merging.size(), mergingOutPath);
        
      } catch (IOException e) {
        e.printStackTrace();
      }
    }

    // intersection output
    if (intersectionOutPath != null) {
      intersectionOutPath = absolutify(intersectionOutPath);
      try {
        Set<String> intersection = collationResult.getIntersection();
        Files.write(intersectionOutPath, intersection);
        log.log(INFO, "Intersection result ({0} items) has been written to ''{1}''", intersection.size(), intersectionOutPath);
        
      } catch (IOException e) {
        e.printStackTrace();
      }
    }
    long execTime = System.currentTimeMillis() - startTime;
    log.log(INFO, "Task execution took {0} ms.", execTime);
    return collationResult;
  }

  private long setup() {
    // first, remember current time to compute overall task execution time
    long startTime = System.currentTimeMillis();
    if (exclusionMatchers.isEmpty()) {
      exclusionGlobs.forEach(exclusionGlob -> 
              exclusionMatchers.add(FileSystems.getDefault().getPathMatcher("glob:" + exclusionGlob)));
    }
    // then store selected (or default) comparison mode in global value to make it accessible from anywhere 
    Constants.PRECISE_FILE_COMPARISON_MODE = preciseFileComparisonMode;
    log.log(INFO, "File comparison mode: {0}", preciseFileComparisonMode ? "precise" : "rough");
    
    return startTime;
  }

  private boolean filterOutExclusions(Path path) {
    return exclusionMatchers.stream()
            .filter(matcher -> matcher.matches(path))
            .findAny()
            .isEmpty();
  }

  private void processFatJar(Map<String, List<?>> allEntries, String arg) throws IOException {
    log.log(INFO, "Processing path ''{0}'' as Spring Boot ''fat'' JAR...", arg);
    var fatJarPath = absolutify(Paths.get(arg));
    try (JarFile jarFile = new JarFile(fatJarPath.toString())) {
      String startClass = jarFile.getManifest().getMainAttributes().getValue("Start-Class");
      if (startClass == null) {
        log.log(WARNING, "File ''{0}'' is not Spring Boot ''fat'' JAR or is malformed.", arg);
        return;
      }
      log.log(DEBUG, "For JAR ''{0}'' start class detected as: {1}", fatJarPath, startClass);
      List<NestedJarEntry> jars = jarFile.stream()
              .filter(this::nestedJarFilter)
              .map(NestedJarEntry::new)
              //.peek(System.out::println)
              .collect(toList());
      allEntries.put(fatJarPath.toString(), jars);
      log.log(INFO, "{0} lines have been put under ''{1}'' fat JAR path", jars.size(), fatJarPath);
    }
  }

  private boolean nestedJarFilter(JarEntry jarEntry) {
    String entryName = jarEntry.getName();
    return (entryName.startsWith("BOOT-INF/") 
            || entryName.startsWith("WEB-INF/"))
            && entryName.toLowerCase().endsWith(".jar");
  }

  private Path absolutify(Path path) {
    if (!path.isAbsolute()) {
      path = root.resolve(path);
    }
    return path;
  }

  private static List<PathEntry> getDirFileNames(Path dirPath) throws IOException {
    List<PathEntry> dirEntries = new ArrayList<>();
    try (DirectoryStream<Path> dirStream = Files.newDirectoryStream(dirPath)) {
      for (Path dirEntry : dirStream) {
        dirEntries.add(new PathEntry(dirEntry));
      }
    }
    return dirEntries;
  }

  private CollationResult collate(Map<String, List<?>> allEntries) {
    // intersection (entries that present in every list)
    Iterator<List<?>> topLevelIterator = allEntries.values().iterator();
    Set<?> intersection = new HashSet<>(topLevelIterator.next());
    while (topLevelIterator.hasNext()) {
      List<?> currentEntries = topLevelIterator.next();
      intersection.retainAll(currentEntries);   // this call heavily relies on equals method of the entries
    }
    // merging (a combination of all entries from all lists without duplicates)
    Set<Object> merging = new HashSet<>();
    for (List<?> objects : allEntries.values()) {
      merging.addAll(objects);                  // this call also relies on equals method of the entries
    }
    // owns (entries which are specific to each list of entries)
    Map<String, List<?>> owns = new HashMap<>(allEntries.size());
    allEntries.forEach((name, lines) -> owns.put(name, new ArrayList<>(lines)));
    owns.values().forEach(list -> list.removeAll(intersection));
    
    // statistics
    LongSummaryStatistics interStats = allEntries.values()
            .stream()
            .mapToDouble(lines -> (double) intersection.size() / (double) lines.size())
            .mapToLong(value -> Math.round(value * 100.0))
            .summaryStatistics();
    IntSummaryStatistics sizeStats = allEntries.values().stream()
            .mapToInt(List::size)
            .summaryStatistics();

    // output
    String logMessage = 
        "\n=================================================\n" +
        String.format("List sizes: min=%d, avg=%.0f, max=%d, cnt=%d\n",
            sizeStats.getMin(), sizeStats.getAverage(), sizeStats.getMax(), sizeStats.getCount()) +
        String.format("Merged list size:  %d\n", merging.size()) +
        String.format("Intersection size: %d\n", intersection.size()) +
        String.format("Intersection stats: min=%d%%, avg=%.0f%%, max=%d%%\n",
            interStats.getMin(), interStats.getAverage(), interStats.getMax()) +
        "=================================================";
    log.log(INFO, logMessage);
//    for (Map.Entry<String, List<String>> listEntry : allEntries.entrySet()) {
//      int entrySize = listEntry.getValue().size();
//      int ownElements = entrySize - intersection.size();
//      double ownElementsShare = ((double) ownElements / (double) entrySize) * 100.0;
//      System.out.printf("List '%s'\t contains %d own elements of %d (%.0f%%)\n", listEntry.getKey(), ownElements,
//              entrySize, ownElementsShare);
//    }
    return new CollationResult(merging, intersection, owns);
  }

  public void setArgs(List<String> args) {
    this.args = args;
  }

  public void setRoot(Path root) {
    this.root = root;
  }

  public void setExclusionGlobs(Set<String> exclusionGlobs) {
    this.exclusionGlobs = exclusionGlobs;
  }
}
