package tech.toparvion.util.jcudos;

import picocli.CommandLine;
import picocli.CommandLine.Option;
import tech.toparvion.util.jcudos.infra.JCudosVersionProvider;
import tech.toparvion.util.jcudos.model.exception.JCudosException;
import tech.toparvion.util.jcudos.subcommand.*;
import tech.toparvion.util.jcudos.util.PathUtils;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

import static java.lang.System.Logger.Level.*;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toSet;
import static picocli.CommandLine.Command;
import static tech.toparvion.util.jcudos.Constants.*;
import static tech.toparvion.util.jcudos.util.GeneralUtils.suppress;

/**
 * @author Toparvion
 */
@Command(name = MY_NAME, 
        mixinStandardHelpOptions = true, 
        versionProvider = JCudosVersionProvider.class,
        subcommands = {
                ListAllClasses.class,
                ListMergedClasses.class,
                Collate.class,
                CopyFilesByList.class,
                ProcessFatJars.class,
                ConvertJar.class,
                Estimate.class
        })
public class JCudos implements Runnable {
  private static final System.Logger log;

  static {
    System.setProperty("java.util.logging.SimpleFormatter.format", "%1$tF %1$tT %4$s %2$s - %5$s%6$s%n");
    log = System.getLogger(JCudos.class.toString());
  }

  @Option(names = {"--class-lists", "-c"})
  private List<String> classListGlob;
  
  @Option(names = {"--fat-jars", "-f"})
  private String fatJarsGlob;
  
  @Option(names = {"--out-dir", "-o"}, defaultValue = "_appcds/")
  private Path outDir;
  
  @Option(names = {"--exclusion", "-e"})
  private Set<String> exclusionGlobs = new HashSet<>();  
  
  @Option(names = {"--work-dir", "-w"})
  private Path root = Paths.get(System.getProperty("user.dir"));

  //<editor-fold desc="Entry point">
  public static void main(String[] args) {
    CommandLine.run(new JCudos(), args);
  }

  @Override
  public void run() {
    log.log(INFO, "{0} has been called: classListGlob={1}, fatJarsGlob={2}, outDirPath={3}, " +
                    "exclusions={4}, root={5}", MY_PRETTY_NAME, classListGlob, fatJarsGlob, outDir, exclusionGlobs, root);
    try {
      validateRootPath(root);    // throws an exception in case of validation fail
      // the following may throw FileAlreadyExistsException in case another process is already acting 
      outDir = occupyOutDir(root, outDir);

      // Stage A - Process class lists
      processClassLists(root, classListGlob, exclusionGlobs, outDir);
      // Stage B - Process every found 'fat' JAR
      List<String> libDirs = processFatJars(root, fatJarsGlob, exclusionGlobs, outDir);
      // Stage C - Create common (shared) archive
      List<Path> commonLibPaths = createCommonArchive(libDirs, outDir);
      // Stage D - Prepare application for running with AppCDS
      preparePrivateArgFiles(libDirs, commonLibPaths);
      
      log.log(INFO, "{0} execution took {1} ms.", MY_PRETTY_NAME, ManagementFactory.getRuntimeMXBean().getUptime());
      suppress(() -> Files.deleteIfExists(outDir.resolve(LOCK_FILE_NAME)));
      
    } catch (FileAlreadyExistsException lockException) {
      log.log(WARNING, "Directory ''{0}'' is already occupied by another {1} process. " +
              "Please re-launch this process again a few minutes later, or manually remove the lock file, " +
              "or specify other output directory with --out-dir option.", outDir, MY_PRETTY_NAME);
      // do not remove lockFile here as it may be requested by other jCuDoS processes
      System.exit(ALREADY_IN_PROGRESS_EXIT_CODE);     // to reveal the fact for calling side

    } catch (JCudosException jcudosException) {
      // don't print the stack trace as it is useless for this kind of exceptions
      suppress(() -> Files.deleteIfExists(outDir.resolve(LOCK_FILE_NAME)));
      System.exit(APPCDS_ERROR_EXIT_CODE);
      
    } catch (Throwable e) {
      e.printStackTrace();
      suppress(() -> Files.deleteIfExists(outDir.resolve(LOCK_FILE_NAME)));
      System.exit(INTERNAL_ERROR_EXIT_CODE);
    } 
  }
  //</editor-fold>

  //<editor-fold desc="Stage A">
  /**
   * Stage A - class lists processing
   * @param root root directory of microservices
   * @param classListGlob relative Glob pattern to find out files to process 
   * @param exclusionGlobs a set of excluding globs
   * @param outDir output directory path, e.g. {@code _shared/}
   * @throws IOException in case of any IO error
   */
  private void processClassLists(Path root, List<String> classListGlob, Set<String> exclusionGlobs, Path outDir) throws IOException {
    // A.1 - find common part among all class lists
    Collate collateCommand = new Collate();
    collateCommand.setArgs(classListGlob);
    collateCommand.setRoot(root);
    collateCommand.setExclusionGlobs(exclusionGlobs);
    var result = collateCommand.call();
    if (result == null) {
      log.log(ERROR, "No class lists found by Glob pattern ''{0}''. Exiting.", classListGlob);
      throw new JCudosException();
    }
    
    // A.2 - save the common part as separate list in output directory
    Path commonClassListPath = outDir.resolve(SHARED_CLASS_LIST_PATH);
    Files.createDirectories(commonClassListPath.getParent());
    Set<String> intersection = result.getIntersection();
    Files.write(commonClassListPath, intersection);
    
    log.log(INFO, "{0} class names saved into ''{1}''", intersection.size(), commonClassListPath);
  }
  //</editor-fold>

  //<editor-fold desc="Stage B">
  /**
   * Stage B - fat JARs processing
   * @param root root directory of microservices
   * @param fatJarsGlob relative Glob pattern to find out files to process
   * @param exclusionGlobs a set of excluding globs
   * @param outDir output directory path, e.g. {@code _shared/}
   * @return a list of string paths to {@code lib} directories created next to fat JARs
   */
  private List<String> processFatJars(Path root, String fatJarsGlob, Set<String> exclusionGlobs, Path outDir) {
    // B.(1-4)
    ProcessFatJars processCommand = new ProcessFatJars();
    processCommand.setRoot(root);
    processCommand.setFatJarsGlob(fatJarsGlob);
    processCommand.setExclusionGlobs(exclusionGlobs);
    processCommand.setOutDir(outDir);
    List<String> libOutDirPaths = processCommand.call();
    if (libOutDirPaths.isEmpty()) {
      log.log(ERROR, "No fat JARs were processed by Glob ''{0}'' in directory ''{1}''.", fatJarsGlob, root);
      throw new JCudosException();
    }
    
    log.log(INFO, "Processed {0} fat JARs.", libOutDirPaths.size());
    return libOutDirPaths;
  }
  //</editor-fold>

  //<editor-fold desc="Stage C">
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
    List<Path> commonLibPaths = copySharedLibs(libDirs, outDirPath, intersection);

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
    // leave file names only as all the rest is the same and is not interesting for further processing
    return intersection.stream()
            .map(Paths::get)
            .map(Path::getFileName)
            .map(Path::toString)
            .collect(toSet());
  }

  /**
   * C.2 - copy all common libs into shared AppCDS directory 
   */
  private List<Path> copySharedLibs(List<String> libDirs, Path outDirPath, Set<String> intersection) throws IOException {
    Path sourceLibDir = Paths.get(libDirs.get(0));  // as common part is the same in all dirs, we can take the first one
    Path targetLibDir = PathUtils.prepareDir(outDirPath.resolve(SHARED_ROOT).resolve(LIB_DIR_NAME));

    List<Path> commonLibPaths = Files.walk(sourceLibDir)
            .filter(sourceFile -> intersection.contains(sourceFile.getFileName().toString()))
            .map(sourceFile -> PathUtils.copyFile(sourceFile, targetLibDir))
            .filter(Objects::nonNull)
            .collect(toList());
    if (commonLibPaths.size() != intersection.size()) {
      log.log(WARNING, "Only {0} of {1} common libraries were copied. Shared archive may be incorrect!", 
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
    Path argFilePath = outDirPath.resolve(SHARED_ARGFILE_PATH);
    Files.writeString(argFilePath, argFileContent);
    log.log(INFO, "Common arg-file created: {0}", argFilePath);
  }

  /**
   * C.5 - execute java -Xshare:dump with all the accumulated data
   */
  private void executeJavaXShareDump(Path outDirPath) throws IOException, InterruptedException {
    PathUtils.prepareDir(outDirPath.resolve(SHARED_ARCHIVE_PATH.getParent()));
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
            "-XX:SharedClassListFile=" + SHARED_CLASS_LIST_PATH,
            "-XX:SharedArchiveFile=" + SHARED_ARCHIVE_PATH,
            "@" + SHARED_ARGFILE_PATH
    );
    var workDir = outDirPath.toFile();
    log.log(INFO, "Starting Java with ''{0}'' at directory ''{1}''...", String.join(" ", javaCommand), workDir);
    ProcessBuilder javaLauncher = new ProcessBuilder();
    javaLauncher.command(javaCommand);
    javaLauncher.directory(workDir);
    javaLauncher.inheritIO();
    var startTime = System.currentTimeMillis();
    int javaExitCode = javaLauncher.start().waitFor();
    var stopTime = System.currentTimeMillis();
    if (javaExitCode == 0) {
      log.log(INFO, "Shared archive has been created successfully in {0} ms.", (stopTime-startTime));
    } else {
      log.log(ERROR, "Failed to create shared archive (see log above). " +
                      "Java process exited with code {0}.", javaExitCode);
      throw new JCudosException();
    } 
  }
  //</editor-fold>

  //<editor-fold desc="Stage D">
  /**
   * Stage D - Preparation of applications' local arg-files
   * @param libDirs list of paths to extracted libs
   * @param commonLibPaths list of paths to common libs
   */
  private void preparePrivateArgFiles(List<String> libDirs, List<Path> commonLibPaths) throws IOException {
    // D.1 - remove all common libs from applications' local directories
    deleteCommonLibs(libDirs, commonLibPaths);
    
    // D.2 - compose app's own argfile
    Path jsaPath = outDir.resolve(SHARED_ARCHIVE_PATH);
    for (String libDir : libDirs) {
      Path libDirPath = Paths.get(libDir);
      List<Path> privateLibPaths = PathUtils.getDirListing(libDirPath);
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
    log.log(INFO, "Prepared {0} application(s) for running with AppCDS.", libDirs.size());
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
           .forEach(libDir -> PathUtils.deleteFilesByFilter(libDir, libFilter));
    log.log(INFO, "Removed {0} jars from each of {1} directories.", commonLibNames.size(), libDirs.size());
  }
  //</editor-fold>

  //<editor-fold desc="Auxiliary private methods">
  
  private void validateRootPath(Path root) {
    // check root dir path
    if (!root.isAbsolute()) {
      log.log(ERROR, "If ''root'' option is specified, it must be absolute. ''{0}'' is incorrect.", root);
      throw new JCudosException();
    }
  }

  /**
   * Resolves path to output directory against root path. Then creates output directory (including all its parents
   * if necessary) and puts a lock file into it. The latter is needed to prevent jCudos processes from performing 
   * simultaneously.
   * @param outDir output directory to create (may already exist)
   * @throws IOException in case of IO error during creating
   * @return (possibly changed) path to output directory
   */
  private Path occupyOutDir(Path root, Path outDir) throws IOException {
    // fix output dir path
    if (!outDir.isAbsolute()) {
      outDir = root.resolve(outDir);
    }
    // create output dir if necessary
    Files.createDirectories(outDir);
    // put a lock file 
    Files.createFile(outDir.resolve(LOCK_FILE_NAME));
    // return (possibly fixed) path
    return outDir;
  }
  //</editor-fold>
}
