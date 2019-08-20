package tech.toparvion.util.jcudos.subcommand;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static picocli.CommandLine.Command;
import static picocli.CommandLine.Parameters;

/**
 * @author Toparvion
 */
@Command(name = "list-merged-classes",
        mixinStandardHelpOptions = true,
        description = "Composes a list with compile & runtime classes found in specified JARs"
)
public class ListMergedClasses implements Runnable {
  
  private static final Pattern DEPENDENCY_LINE = Pattern.compile(" +\\p{Graph}+ +-> (\\p{Graph}+) +([\\p{Graph} ]+)");
  
  @Parameters(index = "0", paramLabel = "SOURCE",
        description = "A path to list to be analyzed")
  private Path listToAnalyze;
  
  @Parameters(index = "1", paramLabel = "TARGET",
          description = "Destination for list output, e.g. /tmp/classes.list")
  private Path targetPath;  
  
  @Override
  public void run() {
    try {
      Map<String, Integer> compileClasses = readJDepsList("C:\\Users\\plizga\\AppData\\Local\\Temp\\scp01737\\pub\\home\\upc\\tmp\\appcds\\manual\\eureka\\list\\jdeps.compile.list");
      Map<String, Integer> runtimeClasses = readJDepsList("C:\\Users\\plizga\\AppData\\Local\\Temp\\scp08197\\pub\\home\\upc\\tmp\\appcds\\manual\\eureka\\list\\jdeps.runtime.list");
      Set<String> allClasses = new HashSet<>(compileClasses.keySet());
      allClasses.addAll(runtimeClasses.keySet());
      int sameClassesCount = (compileClasses.size() + runtimeClasses.size()) - allClasses.size();
      System.out.printf("%d compile classes + %d runtime classes = %d unique classes (%d same classes)\n", 
              compileClasses.size(), runtimeClasses.size(), allClasses.size(), sameClassesCount);
      //.sorted(comparing(Map.Entry::getKey))
      //.map(entry -> entry.getKey() + " -> " + entry.getValue())
      List<String> resultLines = new ArrayList<>(allClasses);
      Collections.sort(resultLines);
      // store the result to output file
      Files.write(targetPath, resultLines);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private Map<String, Integer> readJDepsList(String jdepsFile) throws IOException {
    Map<String, Integer> classes = new HashMap<>(4096);
    int notFoundClassesCount = 0;
    try (BufferedReader reader = Files.newBufferedReader(Paths.get(jdepsFile))) {
      String line;
      while ((line = reader.readLine()) != null) {
        Matcher lineMatcher = DEPENDENCY_LINE.matcher(line);
        if (!lineMatcher.matches()) {
          continue;
        }
        String clazz = lineMatcher.group(1).replace('.', '/');
        String module = lineMatcher.group(2);
        if (module.equals("not found")) {
          notFoundClassesCount++;
          continue;
        }
        classes.merge(clazz, 1, Integer::sum);
      }
      System.out.printf("Found %d unique classes and %d classes not found in file %s.\n", classes.size(),
              notFoundClassesCount, jdepsFile);
    } 
    return classes;
  }
}
