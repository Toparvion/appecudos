package tech.toparvion.util.appecudos;

import picocli.CommandLine;
import picocli.CommandLine.Option;
import tech.toparvion.util.appecudos.subcommand.*;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Stream;

import static java.lang.System.Logger.Level.*;
import static java.nio.file.StandardCopyOption.COPY_ATTRIBUTES;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toSet;
import static picocli.CommandLine.Command;
import static tech.toparvion.util.appecudos.Constants.*;

/**
 * @author Toparvion
 */
@Command(name = "appcudos", 
        mixinStandardHelpOptions = true, version = "AppCuDoS v1.0",
        subcommands = {
                ListAllClasses.class,
                ListMergedClasses.class,
                Collate.class,
                CopyFilesByList.class,
                ProcessFatJars.class,
                ConvertJar.class
        })
public class AppCuDoS implements Runnable {
  private static final System.Logger log;
  static {
    System.setProperty("java.util.logging.SimpleFormatter.format", "%1$tF %1$tT %4$s %2$s - %5$s%6$s%n");
    log = System.getLogger(AppCuDoS.class.toString());
  }

  @Option(names = {"--class-lists", "-c"})
  private String classListGlob;
  
  @Option(names = {"--fat-jars", "-f"})
  private String fatJarsGlob;
  
  @Option(names = {"--shared-dir", "-o"})
  private Path outDirPath;
  
  @Option(names = {"--exclusion", "-e"})
  private Set<String> exclusionGlobs = new HashSet<>();  
  
  @Option(names = {"--root", "-r"})
  private Path root = Paths.get(System.getProperty("user.dir"));

  public static void main(String[] args) {
    CommandLine.run(new AppCuDoS(), args);
  }

  @Override
  public void run() {
    fixPaths();
    log.log(INFO, "AppCuDoS has been called: classListGlob={0}, fatJarsGlob={1}, commonDirPath={2}, " +
                    "exclusions={3}, root={4}", classListGlob, fatJarsGlob, outDirPath, exclusionGlobs, root);
    try {
      processClassLists(root, classListGlob, exclusionGlobs);
      List<String> libDirs = processFatJars(root, fatJarsGlob, exclusionGlobs);
      List<Path> commonLibPaths = createCommonArchive(libDirs, outDirPath);
      preparePrivateArgFiles(libDirs, commonLibPaths);
      log.log(INFO, "AppCuDoS execution took {0} ms.", ManagementFactory.getRuntimeMXBean().getUptime());
      
    } catch (Exception e) {
      e.printStackTrace();
      System.exit(1);
    }
  }

  /**
   * Stage A - class lists processing
   * @param root root directory of microservices
   * @param classListGlob relative Glob pattern to find out files to process 
   * @param exclusionGlobs a set of exlcuding globs
   * @throws IOException in case of any IO error
   */
  private void processClassLists(Path root, String classListGlob, Set<String> exclusionGlobs) throws IOException {
    // A.1 - find common part among all class lists
    Collate collateCommand = new Collate();
    collateCommand.setArgs(List.of(classListGlob));
    collateCommand.setRoot(root);
    collateCommand.setExclusionGlobs(exclusionGlobs);
    var result = collateCommand.call();
    if (result == null) {
      log.log(ERROR, "No class lists found by Glob pattern ''{0}''. Exiting.", classListGlob);
      System.exit(1);
    }
    
    // A.2 - save the common part as separate list in output directory
    Path commonClassListPath = outDirPath.resolve(COMMON_CLASS_LIST_PATH);
    Files.createDirectories(commonClassListPath.getParent());
    Set<String> intersection = result.getIntersection();
    Files.write(commonClassListPath, intersection);
    
    log.log(INFO, "{0} class names saved into ''{1}''", intersection.size(), commonClassListPath);
  }

  /**
   * Stage B - fat JARs processing
   * @param root root directory of microservices
   * @param fatJarsGlob relative Glob pattern to find out files to process
   * @param exclusionGlobs a set of exlcuding globs
   * @return a list of string paths to {@code lib} directories created next to fat JARs
   */
  private List<String> processFatJars(Path root, String fatJarsGlob, Set<String> exclusionGlobs) {
    // B.(1-4)
    ProcessFatJars extractCommand = new ProcessFatJars();
    extractCommand.setRoot(root);
    extractCommand.setFatJarsGlob(fatJarsGlob);
    extractCommand.setExclusionGlobs(exclusionGlobs);
    List<String> libOutDirPaths = extractCommand.call();
    if (libOutDirPaths.isEmpty()) {
      log.log(ERROR, "No fat JARs were processed by Glob ''{0}'' in directory ''{1}''.", fatJarsGlob, root);
      System.exit(1);
    }
    
    log.log(INFO, "Processed {0} fat JARs.", libOutDirPaths.size());
    return libOutDirPaths;
  }

  /**
   * Stage C - common archive (JSA) creation
   * @param libDirs list of paths to extracted libs
   * @param outDirPath path to common AppCDS out directory
   * @throws IOException in case of any IO error
   * @return paths to common libraries (in AppCDS common directory)
   */
  private List<Path> createCommonArchive(List<String> libDirs, Path outDirPath) throws IOException, InterruptedException {
    // C.1 - find common libs among all extracted libs
    Set<String> intersection = findCommonLibs(libDirs);
    
    // C.2 - copy all common libs from apps' local dirs to common AppCDS directory
    // C.4 - remember the list of common libs with their absolute paths
    List<Path> commonLibPaths = copyCommonLibs(libDirs, outDirPath, intersection);

    // C.3 - compose arg-file from paths of copied common libraries
    createCommonArgFile(outDirPath, commonLibPaths);
    
    // C.5 - execute java -Xshare:dump with all the accumulated data
    executeJavaXShareDump(outDirPath);
    
    return commonLibPaths;
  }

  /**
   * C.1 - find common libs among all extracted libs
   */
  private Set<String> findCommonLibs(List<String> libDirs) {
    Collate collateCommand = new Collate();
    collateCommand.setArgs(libDirs);
    var collationResult = collateCommand.call();
    Set<String> intersection = collationResult.getIntersection();
    log.log(INFO, "There are {0} common libs among all found applications.", intersection.size());
    return intersection;
  }

  /**
   * C.2 - copy all common libs to common AppCDS directory 
   */
  private List<Path> copyCommonLibs(List<String> libDirs, Path outDirPath, Set<String> intersection) throws IOException {
    Path sourceLibDir = Paths.get(libDirs.get(0));  // as common part is the same in all dirs, we can take the first one
    Path targetLibDir = Util.prepareDir(outDirPath.resolve(LIB_DIR_NAME));

    List<Path> commonLibPaths = Files.walk(sourceLibDir)
            .filter(sourceFile -> intersection.contains(sourceFile.getFileName().toString()))
            .map(sourceFile -> copyFile(sourceFile, targetLibDir))
            .filter(Objects::nonNull)
            .collect(toList());
    if (commonLibPaths.size() != intersection.size()) {
      log.log(WARNING, "Only {0} of {1} common libraries were copied. Common archive may be incorrect!", 
              commonLibPaths.size(), intersection.size());
    } else {
      log.log(INFO, "All {0} common libraries were copied from ''{1}'' to ''{2}''.", commonLibPaths.size(), 
              sourceLibDir, targetLibDir);
    }
    return commonLibPaths;
  }

  /**
   * C.3 - compose arg-file from paths of copied common libraries
   */
  private void createCommonArgFile(Path outDirPath, List<Path> commonLibPaths) throws IOException {
    String classpath = commonLibPaths.stream()
            .map(Path::toString)
            .collect(TO_CLASSPATH);
    String argFileContent = COMMON_ARGFILE_INTRO + classpath;
    Path argFilePath = outDirPath.resolve(COMMON_ARGFILE_PATH);
    Files.writeString(argFilePath, argFileContent);
    log.log(INFO, "Common arg-file created: {0}", argFilePath);
  }

  /**
   * C.5 - execute java -Xshare:dump with all the accumulated data
   */
  private void executeJavaXShareDump(Path outDirPath) throws IOException, InterruptedException {
    Util.prepareDir(outDirPath.resolve(COMMON_ARCHIVE_PATH.getParent()));
    var javaExecutable = System.getProperty("os.name").toLowerCase().startsWith("windows")
            ? "java.exe"
            : "java";
    var javaPath = Paths.get(System.getProperty("java.home"))
            .resolve("bin")
            .resolve(javaExecutable)
            .toAbsolutePath()
            .toString();
    var javaCommand = List.of(
            javaPath,
            "-Xshare:dump",
            "-XX:SharedClassListFile=" + COMMON_CLASS_LIST_PATH,
            "-XX:SharedArchiveFile=" + COMMON_ARCHIVE_PATH,
            "@list/classpath.arg"
    );
    log.log(INFO, "Starting Java with ''{0}''...", String.join(" ", javaCommand));
    ProcessBuilder javaLauncher = new ProcessBuilder();
    javaLauncher.command(javaCommand);
    javaLauncher.directory(outDirPath.toFile());
    javaLauncher.inheritIO();
    int javaExitCode = javaLauncher.start().waitFor();
    if (javaExitCode == 0) {
      log.log(INFO, "Shared archive has been created successfully.", javaExitCode);
    } else {
      log.log(ERROR, "Failed to create shared archive (see log). Java process exited with code {0}.", javaExitCode);
      System.exit(1);
    } 
  }
  
  /**
   * Stage D - Preparation of applications' local arg-files
   * @param libDirs list of paths to extracted libs
   * @param commonLibPaths list of paths to common libs
   */
  private void preparePrivateArgFiles(List<String> libDirs, List<Path> commonLibPaths) throws IOException {
    // D.1 - remove all common libs from applications' local directories
    deleteCommonLibs(libDirs, commonLibPaths);
    
    // D.2 - compose app's own argfile
    Path jsaPath = outDirPath.resolve(COMMON_ARCHIVE_PATH);
    for (String libDir : libDirs) {
      Path libDirPath = Paths.get(libDir);
      List<Path> privateLibPaths = getDirListing(libDirPath);
      int commonLibsCount = commonLibPaths.size();
      int privateLibsCount = privateLibPaths.size();
      String classpath = Stream.concat(commonLibPaths.stream(), privateLibPaths.stream())
              .map(Path::toString)
              .collect(TO_CLASSPATH);
      var startClass = Files.readString(libDirPath.resolveSibling(START_CLASS_FILE_NAME));
      String argFileContent = String.format(PRIVATE_ARGFILE_TEMPLATE, jsaPath, commonLibsCount, privateLibsCount,
              (commonLibsCount + 4), classpath, startClass);
      Path privateArgFilePath = libDirPath.resolveSibling(APPCDS_ARGFILE_NAME);
      Files.writeString(privateArgFilePath, argFileContent);
      log.log(INFO, "Written {0} classpath entries to application arg-file ''{1}''.", 
              (commonLibsCount+privateLibsCount), privateArgFilePath);
    }
  }

  /**
   * D.1 - remove all common libs from applications' local directories
   */
  private void deleteCommonLibs(List<String> libDirs, List<Path> commonLibPaths) {
    Set<Path> commonLibNames = commonLibPaths.stream()
            .map(Path::getFileName)
            .collect(toSet());
    DirectoryStream.Filter<Path> libFilter = lib -> commonLibNames.contains(lib.getFileName());
    libDirs.stream()
           .map(Paths::get)
           .forEach(libDir -> deleteFilesByFilter(libDir, libFilter));
    log.log(INFO, "Removed {0} jars from each of {1} directories.", commonLibNames.size(), libDirs.size());
  }
  
  private List<Path> getDirListing(Path dirPath) throws IOException {
    List<Path> listing = new ArrayList<>();
    try (DirectoryStream<Path> dirStream = Files.newDirectoryStream(dirPath)) {
      dirStream.forEach(listing::add);
    }
    return listing;
  }
  

  private void deleteFilesByFilter(Path libDir, DirectoryStream.Filter<Path> libFilter) {
    try (DirectoryStream<Path> dirStream = Files.newDirectoryStream(libDir, libFilter)) {
      for (Path path : dirStream) {
        Files.deleteIfExists(path);
      }

    } catch (IOException e) {
      e.printStackTrace();
    }
  }
  

  private Path copyFile(Path sourceFile, Path targetLibDir) {
    try {
      Path targetFile = targetLibDir.resolve(sourceFile.getFileName());
      Files.copy(sourceFile, targetFile, COPY_ATTRIBUTES);
      return targetFile;
      
    } catch (IOException e) {
      e.printStackTrace();
      return null;
    }
  }

  private void fixPaths() {
    // check root dir path
    if (!root.isAbsolute()) {
      log.log(ERROR, "If ''root'' option is specified, it must be absolute. ''{0}'' is not correct.", root);
      System.exit(1);
    }

    // fix output dir path
    if (!outDirPath.isAbsolute()) {
      outDirPath = root.resolve(outDirPath);
    }
  }
}
