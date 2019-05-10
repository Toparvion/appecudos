package tech.toparvion.util.appecudos.subcommand;

import tech.toparvion.util.appecudos.model.CollationResult;

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
        description = "Collates given lists irrelative to their elements order")
public class Collate implements Callable<CollationResult> {

  @Parameters(paramLabel = "LISTS", description = "Paths to lists to be analyzed. Can be concrete paths of glob " +
          "patterns pointing to either list files or directories")
  private List<String> args;
  
  @Option(names = {"-r", "--root"})
  private Path root;
  
  @Option(names = {"-m", "--merging-out"})
  private Path mergingOutPath;
  
  @Option(names = {"-i", "--intersection-out"})
  private Path intersectionOutPath;

  @Override
  public CollationResult call() {
    Map<String, List<String>> allLines = new HashMap<>();
    if (root == null) {
      root = Paths.get(System.getProperty("user.dir"));
    }
    for (String arg : args) {
      try {
        if (arg.contains("*")) {
          System.out.printf("Path '%s' contains wildcard(s), will process it as Glob pattern...\n", arg);
          PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + arg);
          List<Path> matchedPaths = Files.walk(root)
                  .filter(path -> matcher.matches(root.relativize(path)))
                  //.peek(System.out::println)
                  .collect(toList());
          for (Path matchedPath : matchedPaths) {
            if (Files.isDirectory(matchedPath)) {
              System.out.printf("Processing path '%s' as directory...\n", arg);
              List<String> dirEntries = getDirFileNames(matchedPath);
              allLines.put(matchedPath.toString(), dirEntries);
              System.out.printf("%d entries have been put under '%s' dir name\n", dirEntries.size(), matchedPath);

            } else {
              if (Files.isReadable(matchedPath)) {
                String matchedPathString = matchedPath.toString().toLowerCase();
                if (matchedPathString.endsWith(".jar")) {
                  processFatJar(allLines, matchedPathString);

                } else {
                  System.out.printf("Processing path '%s' as plain list file...\n", arg);
                  List<String> lines = Files.readAllLines(matchedPath);
                  allLines.put(matchedPath.toString(), lines);
                  System.out.printf("%d lines have been put under '%s' matched file name\n", lines.size(), matchedPath);
                } 

              } else {
                System.err.printf("Path '%s' doesn't point to existing and readable file. Skipped.", matchedPath);
                allLines.put(matchedPath.toString(), List.of());
              }
            }
          }

        } else if (arg.toLowerCase().endsWith(".jar")) {
          processFatJar(allLines, arg);

        } else {
          var concretePath = absolutify(Paths.get(arg));
          if (Files.isDirectory(concretePath)) {
            System.out.printf("Processing path '%s' as directory...\n", arg);
            List<String> dirEntries = getDirFileNames(concretePath);
            allLines.put(concretePath.toString(), dirEntries);
            System.out.printf("%d entries have been put under '%s' dir concrete name\n", dirEntries.size(), concretePath);
            
          } else {
            if (Files.isReadable(concretePath)) {
              System.out.printf("Processing path '%s' as plain list file...\n", concretePath);
              List<String> lines = Files.readAllLines(concretePath);
              allLines.put(arg, lines);
              System.out.printf("%d lines have been put under '%s' concrete file name\n", lines.size(), concretePath);
              
            } else {
              System.err.printf("Path '%s' doesn't point to existing and readable file. Skipped.", concretePath);
              allLines.put(concretePath.toString(), List.of());
            } 
          } 
        }
        
      } catch (IOException e) {
        System.err.printf("Failed to process argument '%s'. Skipped.\n", arg);
        e.printStackTrace();
      }
    }
    System.out.printf("Loaded %d lists\n", allLines.size());
    if (allLines.isEmpty()) {
      return null;
    }
    CollationResult collationResult = collate(allLines);

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
    return collationResult;
  }

  private void processFatJar(Map<String, List<String>> allLines, String arg) throws IOException {
    System.out.printf("Processing path '%s' as Spring Boot 'fat' JAR...\n", arg);
    var fatJarPath = absolutify(Paths.get(arg));
    try (JarFile jarFile = new JarFile(fatJarPath.toString())) {
      String startClass = jarFile.getManifest().getMainAttributes().getValue("Start-Class");
      if (startClass == null) {
        System.err.printf("File '%s' is not Spring Boot 'fat' JAR or is malformed.\n", arg);
        return;
      }
      System.out.printf("For JAR '%s' start class detected as: %s\n", fatJarPath, startClass);
      List<String> jars = jarFile.stream()
              .filter(this::nestedJarFilter)
              .map(JarEntry::getName)
//                    .peek(System.out::println)
              .collect(toList());
      allLines.put(fatJarPath.toString(), jars);
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

  private static List<String> getDirFileNames(Path dirPath) throws IOException {
    List<String> dirEntries = new ArrayList<>();
    try (DirectoryStream<Path> dirStream = Files.newDirectoryStream(dirPath)) {
      for (Path dirEntry : dirStream) {
        dirEntries.add(dirEntry.getFileName().toString());
      }
    }
    return dirEntries;
  }


  private CollationResult collate(Map<String, List<String>> allLines) {
    // intersection
    Iterator<List<String>> iterator = allLines.values().iterator();
    Set<String> intersection = new HashSet<>(iterator.next());
    while (iterator.hasNext()) {
      List<String> list = iterator.next();
      intersection.retainAll(list);
    }
    // merging
    Set<String> merging = new HashSet<>();
    allLines.values().forEach(merging::addAll);
    // owns
    Map<String, List<String>> owns = new HashMap<>(allLines.size());
    allLines.forEach((name, lines) -> owns.put(name, new ArrayList<>(lines)));
    owns.values().forEach(list -> list.removeAll(intersection));
    
    // statistics
    var commonStats = allLines.values()
            .stream()
            .mapToDouble(lines -> (double) intersection.size() / (double) lines.size())
            .mapToLong(value -> Math.round(value * 100.0))
            .summaryStatistics();
    double averageOwnShare = owns.values().stream()
            .mapToInt(List::size)
            .average()
            .orElseThrow();

    // output
    System.out.println("=======================================");
    System.out.printf("Intersection size: %d\n", intersection.size());
    System.out.printf("Merged list size:  %d\n", merging.size());
    System.out.printf("Common part stats: min=%d%%, avg=%.0f%%, max=%d%%\n", commonStats.getMin(), commonStats.getAverage(), commonStats.getMax());
    System.out.printf("Average own part size: %.0f\n", averageOwnShare);
    System.out.println("=======================================");
//    for (Map.Entry<String, List<String>> listEntry : allLines.entrySet()) {
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
}
