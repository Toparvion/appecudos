package tech.toparvion.util.appecudos.subcommand;

import tech.toparvion.util.appecudos.Util;
import tech.toparvion.util.appecudos.model.collate.CollationResult;
import tech.toparvion.util.appecudos.model.collate.entry.NestedJarEntry;
import tech.toparvion.util.appecudos.model.collate.entry.PathEntry;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import static java.util.stream.Collectors.toList;
import static picocli.CommandLine.*;

/**
 * @author Toparvion
 */
@Command(name = "collate",
        mixinStandardHelpOptions = true,
        description = "Collates given lists irrespective to their elements order")
public class Collate implements Callable<CollationResult> {

  @Parameters(paramLabel = "LISTS", description = "Paths to lists to be analyzed. Can be concrete paths of glob " +
          "patterns pointing to either list files or directories (including fat JARs)")
  private List<String> args;
  
  @Option(names = {"-r", "--root"})
  private Path root = Paths.get(System.getProperty("user.dir"));
  
  @Option(names = {"--exclusion", "-e"})
  private Set<String> exclusionGlobs = new HashSet<>();
  
  @Option(names = {"-m", "--merging-out"})
  private Path mergingOutPath;
  
  @Option(names = {"-i", "--intersection-out"})
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
          System.out.printf("Path '%s' contains wildcard(s), will be processed as Glob pattern...\n", arg);
          PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + arg);
          List<Path> matchedPaths = Files.walk(root)
//                  .filter(path -> matcher.matches(root.relativize(path)))
                  .filter(matcher::matches)
                  .filter(this::filterOutExclusions)
                  .collect(toList());
          for (Path matchedPath : matchedPaths) {
            if (Files.isDirectory(matchedPath)) {
              System.out.printf("Processing path '%s' as directory...\n", arg);
              List<PathEntry> dirEntries = getDirFileNames(matchedPath);
              allEntries.put(matchedPath.toString(), dirEntries);
              System.out.printf("%d entries have been put under '%s' dir name\n", dirEntries.size(), matchedPath);

            } else {
              if (Files.isReadable(matchedPath)) {
                String matchedPathString = matchedPath.toString().toLowerCase();
                if (matchedPathString.endsWith(".jar")) {
                  processFatJar(allEntries, matchedPathString);

                } else {
                  System.out.printf("Processing path '%s' as plain list file...\n", matchedPath);
                  List<String> lines = Files.readAllLines(matchedPath);
                  allEntries.put(matchedPath.toString(), lines);
                  System.out.printf("%d lines have been put under '%s' matched file name\n", lines.size(), matchedPath);
                } 

              } else {
                System.err.printf("Path '%s' doesn't point to existing and readable file. Skipped.", matchedPath);
                allEntries.put(matchedPath.toString(), List.of());
              }
            }
          }

        } else if (arg.toLowerCase().endsWith(".jar")) {
          processFatJar(allEntries, arg);

        } else {
          var concretePath = absolutify(Paths.get(arg));
          if (Files.isDirectory(concretePath)) {
            System.out.printf("Processing path '%s' as directory...\n", arg);
            List<PathEntry> dirEntries = getDirFileNames(concretePath);
            allEntries.put(concretePath.toString(), dirEntries);
            System.out.printf("%d entries have been put under '%s' dir concrete name\n", dirEntries.size(), concretePath);
            
          } else {
            if (Files.isReadable(concretePath)) {
              System.out.printf("Processing path '%s' as plain list file...\n", concretePath);
              List<String> lines = Files.readAllLines(concretePath);
              allEntries.put(arg, lines);
              System.out.printf("%d lines have been put under '%s' concrete file name\n", lines.size(), concretePath);
              
            } else {
              System.err.printf("Path '%s' doesn't point to existing and readable file. Skipped.", concretePath);
              allEntries.put(concretePath.toString(), List.of());
            } 
          } 
        }
        
      } catch (IOException e) {
        System.err.printf("Failed to process argument '%s'. Skipped.\n", arg);
        e.printStackTrace();
      }
    }
    System.out.printf("Loaded %d lists\n", allEntries.size());
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
        System.out.printf("Merging result (%d items) has been written to %s\n", merging.size(), mergingOutPath);
        
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
        System.out.printf("Intersection result (%d items) has been written to %s\n", intersection.size(), intersectionOutPath);
        
      } catch (IOException e) {
        e.printStackTrace();
      }
    }
    long execTime = System.currentTimeMillis() - startTime;
    System.out.printf("Collate task took %d ms.\n", execTime);
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
    Util.PRECISE_FILE_COMPARISON_MODE = preciseFileComparisonMode;
    System.out.printf("File comparison mode: %s\n", preciseFileComparisonMode ? "precise" : "rough");
    
    return startTime;
  }

  private boolean filterOutExclusions(Path path) {
    return exclusionMatchers.stream()
            .filter(matcher -> matcher.matches(path))
            .findAny()
            .isEmpty();
  }

  private void processFatJar(Map<String, List<?>> allEntries, String arg) throws IOException {
    System.out.printf("Processing path '%s' as Spring Boot 'fat' JAR...\n", arg);
    var fatJarPath = absolutify(Paths.get(arg));
    try (JarFile jarFile = new JarFile(fatJarPath.toString())) {
      String startClass = jarFile.getManifest().getMainAttributes().getValue("Start-Class");
      if (startClass == null) {
        System.err.printf("File '%s' is not Spring Boot 'fat' JAR or is malformed.\n", arg);
        return;
      }
      System.out.printf("For JAR '%s' start class detected as: %s\n", fatJarPath, startClass);
      List<NestedJarEntry> jars = jarFile.stream()
              .filter(this::nestedJarFilter)
              .map(NestedJarEntry::new)
              //.peek(System.out::println)
              .collect(toList());
      allEntries.put(fatJarPath.toString(), jars);
      System.out.printf("%d lines have been put under '%s' fat JAR path\n", jars.size(), fatJarPath);
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
    System.out.println("====================================================");
    System.out.printf("List sizes: min=%d, avg=%.0f, max=%d, cnt=%d\n", sizeStats.getMin(), sizeStats.getAverage(),
            sizeStats.getMax(), sizeStats.getCount());
    System.out.printf("Merged list size:  %d\n", merging.size());
    System.out.printf("Intersection size: %d\n", intersection.size());
    System.out.printf("Intersection stats: min=%d%%, avg=%.0f%%, max=%d%%\n", interStats.getMin(),
        interStats.getAverage(), interStats.getMax());
    System.out.println("====================================================");
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
