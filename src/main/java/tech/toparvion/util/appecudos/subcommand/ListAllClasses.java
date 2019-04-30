package tech.toparvion.util.appecudos.subcommand;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static java.lang.String.format;
import static picocli.CommandLine.Command;
import static picocli.CommandLine.Help.Visibility.ALWAYS;
import static picocli.CommandLine.Parameters;

/**
 * @author Toparvion
 */
@Command(name = "list-all-classes",
        mixinStandardHelpOptions = true,
        description = "Composes a list with all the classes found in specified JARs"
)
public class ListAllClasses implements Runnable {
  private static final String DEFAULT_OUTPUT = "<stdout>";
  private static final String DEFAULT_SEARCH_GLOB = "*.{jar,JAR,zip,ZIP}";
  private static final String CLASS_EXT = ".class";

  @Parameters(index = "0", paramLabel = "SOURCE",
          description = "A path either to a single JAR or to a directory with JAR files")
  private Path sourcePath;
  
  @Parameters(index = "1", defaultValue = DEFAULT_OUTPUT, showDefaultValue = ALWAYS, paramLabel = "TARGET",
          description = "Destination for list output, e.g. /tmp/classes.list")
  private String targetPath;
  
  @Override
  public void run()  {
    System.out.println(format("Source JAR: %s, target file: %s", sourcePath, targetPath));
    try {
      Set<String> classes = listClasses(sourcePath);
      if (targetPath.equals(DEFAULT_OUTPUT)) {
        classes.forEach(System.out::println);
      } else {
        Files.write(Paths.get(targetPath), classes);
        System.out.printf("Written classes to '%s'.", targetPath);
      }

    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  public Set<String> listClasses(Path sourcePath) throws IOException {
    List<Path> pathsToInspect;
    if (sourcePath.getFileName().toString().toLowerCase().endsWith(".jar") && !Files.isDirectory(sourcePath)) {
      pathsToInspect = List.of(sourcePath);
    
    } else {
      pathsToInspect = new ArrayList<>();
      assert Files.isDirectory(sourcePath) : format("%s is neither JAR file nor a directory.", sourcePath);
      try (DirectoryStream<Path> stream = Files.newDirectoryStream(sourcePath, DEFAULT_SEARCH_GLOB)) {
        stream.forEach(pathsToInspect::add);
      }
      System.out.printf("Found %d JAR files in directory %s\n", pathsToInspect.size(), sourcePath);
    }
    
    // iterate through detected files and extract class names from every one of them 
    Set<String> classes = new TreeSet<>();    // a Set<> collection is used to avoid duplicates in result list
//    Set<String> classes = new LinkedHashSet<>(8192);    // a Set<> collection is used to avoid duplicates in result list
    for (Path jarPath : pathsToInspect) {
      var filesCount = 0;
      try (InputStream fis = Files.newInputStream(jarPath)) {
        ZipInputStream zis = new ZipInputStream(fis);
        ZipEntry nextEntry;
        while ((nextEntry = zis.getNextEntry()) != null) {
          String archivedEntryPath = nextEntry.getName();
          int pathLength = archivedEntryPath.length();
          int extensionIdx = archivedEntryPath.toLowerCase().indexOf(CLASS_EXT);
          if (extensionIdx < 0 
                  || extensionIdx != (pathLength - CLASS_EXT.length()) 
                  || archivedEntryPath.contains("-")) {
            continue;
          }
          String extLessPath = archivedEntryPath.substring(0, extensionIdx);
          classes.add(extLessPath);
          filesCount++;
        }
        zis.closeEntry();
      }
      System.out.printf("Found %d classes in JAR %s\n", filesCount, jarPath);
    }
    System.out.printf("Extracted %d class names from path %s\n", classes.size(), sourcePath);
    return classes;
  }
}
