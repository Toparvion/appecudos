package tech.toparvion.util.jcudos.subcommand;

import io.simonis.cl4cds;
import tech.toparvion.util.jcudos.Constants;
import tech.toparvion.util.jcudos.Constants.ListConversion;
import tech.toparvion.util.jcudos.infra.JCudosVersionProvider;
import tech.toparvion.util.jcudos.model.collate.CollationResult;
import tech.toparvion.util.jcudos.model.collate.entry.NestedJarEntry;
import tech.toparvion.util.jcudos.model.collate.entry.PathEntry;
import tech.toparvion.util.jcudos.util.PathUtils;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import static java.lang.System.Logger.Level.*;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.stream.Collectors.toList;
import static picocli.CommandLine.*;
import static picocli.CommandLine.Help.Visibility.ALWAYS;
import static tech.toparvion.util.jcudos.Constants.ListConversion.AUTO;
import static tech.toparvion.util.jcudos.Constants.ListConversion.ON;

/**
 * @author Toparvion
 */
@Command(name = "collate",
        mixinStandardHelpOptions = true,
        versionProvider = JCudosVersionProvider.class,
        description = "Collates given lists irrespective to their elements order")
public class Collate implements Callable<CollationResult> {
  private static final System.Logger log = System.getLogger(Collate.class.getSimpleName());

  @Parameters(paramLabel = "LISTS", description = "Paths to lists to be analyzed. Can be concrete paths of glob " +
          "patterns pointing to either list files or directories (including fat JARs)")
  private List<String> args;
  
  @Option(names = {"--work-dir", "-w"}, paramLabel = "<workDir>", showDefaultValue = ALWAYS)
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
  @Option(names = {"--precise-compare", "-p"}, description = "Should files be compared byte-by-byte?", 
          showDefaultValue = ALWAYS)
  private boolean preciseFileComparisonMode = false;
  
  @Option(names = {"--convert-lists", "-c"}, 
          description = "Conversion from -Xlog to plain class list file format: ${COMPLETION-CANDIDATES}.",
          showDefaultValue = ALWAYS)
  private ListConversion listConversion = AUTO;
  
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
                  log.log(DEBUG, "Processing path ''{0}'' as list file...", matchedPath);
                  List<String> lines = readClassNames(matchedPath);
                  allEntries.put(matchedPath.toString(), lines);
                  log.log(INFO, "{0} lines have been put under ''{1}'' matched file name", lines.size(), matchedPath);
                } 

              } else {
                log.log(WARNING, "Path ''{0}'' doesn''t point to existing and readable file. Skipped.", matchedPath);
                allEntries.put(matchedPath.toString(), List.of());
              }
            }
          }

        } else if (arg.toLowerCase().endsWith(".jar")) {
          processFatJar(allEntries, arg);

        } else {
          var concretePath = PathUtils.absolutify(Paths.get(arg), root);
          if (Files.isDirectory(concretePath)) {
            log.log(DEBUG, "Processing path ''{0}'' as directory...", arg);
            List<PathEntry> dirEntries = getDirFileNames(concretePath);
            allEntries.put(concretePath.toString(), dirEntries);
            log.log(INFO, "{0} entries have been put under ''{1}'' dir concrete name", dirEntries.size(), concretePath);
            
          } else {
            if (Files.isReadable(concretePath)) {
              log.log(DEBUG, "Processing path ''{0}'' as list file...", concretePath);
              List<String> lines = readClassNames(concretePath);
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
      mergingOutPath = PathUtils.absolutify(mergingOutPath, root);
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
      intersectionOutPath = PathUtils.absolutify(intersectionOutPath, root);
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

  private List<String> readClassNames(Path matchedPath) throws IOException {
    if (listConversion == AUTO) {     // try to auto detect the type of the file
      listConversion = PathUtils.detectClassListType(matchedPath);
    }
    return (listConversion == ON)        // here only ENABLED and DISABLED values are possible
            ? convertList(matchedPath)
            : Files.readAllLines(matchedPath);
  }

  private List<String> convertList(Path matchedPath) throws IOException {
    log.log(DEBUG, "Converting ''{0}'' log into plain class list...", matchedPath);
    long startTime = System.currentTimeMillis();
    byte[] convertedBytes = invokeCl4cds(matchedPath);
    List<String> lines = new ArrayList<>(10_000);
    try (var inReader = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(convertedBytes), UTF_8))) {
      String line;
      while ((line = inReader.readLine()) != null) {
        if (!line.contains("$$FastClassBySpringCGLIB$$")) {
          lines.add(line);
        }
      }
    }
    long took = (System.currentTimeMillis() - startTime);
    log.log(INFO, "Conversion of ''{0}'' log into plain class list took {1} ms and resulted in {2} records.",
            matchedPath, took, lines.size());
    return lines;
  }

  private byte[] invokeCl4cds(Path matchedPath) throws IOException {
    // prepare a buffer to store raw result of cl4cds
    var outStream = new ByteArrayOutputStream(0xffff);    // 65K to begin with
    // call the tool to parse given file 
    cl4cds.ClassesOnly = true;
    cl4cds.CompactIDs = false;    // to avoid excess work as we don't need IDs at all 
    cl4cds.DBG = log.isLoggable(DEBUG);
    Path fatJarTmpDir = Paths.get(System.getProperty("java.io.tmpdir"), "cl4cds");
    cl4cds.FatJarTmp = fatJarTmpDir.toString();
    try (var bufReader = Files.newBufferedReader(matchedPath, UTF_8);
         var outPrintStream = new PrintStream(outStream)) {
      cl4cds.convert(bufReader, outPrintStream);                  // the single call to cl4cds itself
    } finally {
      PathUtils.cleanOutDir(fatJarTmpDir);
      Files.delete(fatJarTmpDir);
    }
    // store the output into byte array for further reading
    return outStream.toByteArray();
    // outStream.close();   // has no effect, see javadoc
  }

  /**
   * Treats given file as Spring Boot 'fat' JAR and collects all its nested JARs into given {@code allEntries} map.  
   * @param allEntries map to append new entries into
   * @param fatJarPathStr string representation of a path to 'fat' JAR file
   * @throws IOException in case of any IO error
   */
  private void processFatJar(Map<String, List<?>> allEntries, String fatJarPathStr) throws IOException {
    log.log(INFO, "Processing path ''{0}'' as Spring Boot ''fat'' JAR...", fatJarPathStr);
    var fatJarPath = PathUtils.absolutify(Paths.get(fatJarPathStr), root);
    try (JarFile jarFile = new JarFile(fatJarPath.toString())) {
      String startClass = jarFile.getManifest().getMainAttributes().getValue("Start-Class");
      if (startClass == null) {
        log.log(WARNING, "File ''{0}'' is not Spring Boot ''fat'' JAR or is malformed.", fatJarPathStr);
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
