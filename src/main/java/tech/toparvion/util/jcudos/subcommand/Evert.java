package tech.toparvion.util.jcudos.subcommand;

import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import tech.toparvion.util.jcudos.util.PathUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static java.lang.System.Logger.Level.*;
import static java.util.stream.Collectors.toList;
import static picocli.CommandLine.Command;
import static picocli.CommandLine.Help.Visibility.ALWAYS;
import static tech.toparvion.util.jcudos.Constants.*;

/**
 * Stage B
 * @author Toparvion
 * TODO extract nested JARs processing and start class saving into separate reusable subcommands
 */
@Command(name = "evert",
        mixinStandardHelpOptions = true,
        description = "Extracts all nested JARs and Start-Class attribute from specified Spring Boot 'fat' JARs")
public class Evert implements Callable<List<String>> {
  private static final System.Logger log = System.getLogger(Evert.class.getSimpleName());

  @Option(names = {"--root", "-r"}, paramLabel = "<workDir>")
  private Path root = Paths.get(System.getProperty("user.dir"));
  
  @Option(names = {"--exclusion", "-e"})
  private Set<String> exclusionGlobs = new HashSet<>();

  @Option(names = {"--out-dir", "-o"}, description = "Output directory. Defaults to the directory of every processed JAR.")
  private Path outDir = null;
  
  //@Nullable   // null means that argFile creation is disabled
  @Option(names = {"-arg-file-name", "-a"}, showDefaultValue = ALWAYS)
  private String argFilePath = APPCDS_ARGFILE_NAME;
  
  @Option(names = {"--jsa-arg-path", "-j"}, description = "Path to JSA file to include into arg-file.")
  private Path jsaPath = null;    // null means that SharedArchiveFile option shouldn't be included into argfile 

  @Parameters(paramLabel = "FAT_JARS", description = "Repeatable path to fat JARs to evert. Can be either a concrete " +
      "path to a single file or a Glob pattern covering multiple files at once.")
  private List<String> fatJarArgs;
  
  private Set<PathMatcher> exclusionMatchers = new HashSet<>();

  /**
   * @return list of string paths to all library dirs extracted during the process
   */
  @Override
  public List<String> call() {
    try {
      assert root.isAbsolute() : "--root must be absolute if specified";
      setupExclusionMatchers();
      List<String> allLibDirPaths = new ArrayList<>();
      for (String fatJarArg : fatJarArgs) {
        if (fatJarArg.contains("*") || fatJarArg.contains("{")) {                   // Glob pattern
          log.log(DEBUG, "Processing ''{0}'' as Glob pattern...", fatJarArg);
          PathMatcher pathMatcher = FileSystems.getDefault().getPathMatcher("glob:" + fatJarArg);
          // here we filter JAR files only, postponing detection of whether they are fat ones (see 'process' method)
          List<String> curLibDirPaths = Files.walk(root)
              .filter(pathMatcher::matches)
              .filter(this::filterOutExclusions)
              .map(this::evert)
              .filter(Objects::nonNull)
              .collect(toList());
          log.log(INFO, "Processing of ''{0}'' Glob pattern resulted in {1} entries.", fatJarArg);
          allLibDirPaths.addAll(curLibDirPaths);
          
        } else {                                                                    // single file
          log.log(DEBUG, "Processing ''{0}'' as a concrete path...", fatJarArg);
          var concretePath = PathUtils.absolutify(Paths.get(fatJarArg), root);
          if (!filterOutExclusions(concretePath)) {
            log.log(TRACE, "Path ''{0}'' has been skipped because of an exclusion filter.", concretePath);
            continue;
          }
          String curLibDirPath = evert(concretePath);
          if (curLibDirPath == null) {
            log.log(TRACE, "Path ''{0}'' has been skipped after processing due to an error. See log above.", concretePath);
            continue;
          }
          log.log(INFO, "Processing of ''{0}'' concrete path resulted in lib dir: {1}", fatJarArg, curLibDirPath);
          allLibDirPaths.add(curLibDirPath);
        }
      }
      // create argFile if necessary (usually this is needed for standalone command invocation only)
      if (argFilePath != null) {
        createArgFiles(argFilePath, allLibDirPaths, jsaPath);
      }

      log.log(INFO, "Overall processing of {0} path argument(s) resulted in {1} directory(es).",
          fatJarArgs.size(), allLibDirPaths.size());
      
      return allLibDirPaths;
      
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * 1. Checks if given JAR is a 'fat' one by searching for Start-Class attribute in its manifest <br/>
   * 2. Stores start class name in a text file <br/>
   * 3. Traverses fat JAR's content and extract all nested JARs <br/> 
   * 4. Converts fat JAR into slim one by means of {@link Convert} command
   * 
   * @param fatJarPath path to Spring Boot fat JAR
   * @return string path to lib directory containing all the extracted (formerly nested) JAR files; <br/> 
   *         can be {@code null} if the path cannot be processed 
   */
  // @Nullable
  private String evert(Path fatJarPath) {
    try {
      // B.1 - check if given JAR is a 'fat' one by searching for Start-Class attribute in its manifest
      String startClass = extractStartClass(fatJarPath);
      if (startClass == null) {
        log.log(WARNING, "File ''{0}'' is not a Spring Boot 'fat' JAR. Skipped.", fatJarPath);
        return null;
      }
      String appName = startClass.substring(startClass.lastIndexOf('.')+1).toLowerCase();
      // prepare local output dir (removing its content firstly if needed)
      if (outDir == null) {
        outDir = fatJarPath.getParent();
      }
      Path localOutDir = PathUtils.cleanOutDir(outDir.resolve(appName));
      
      // B.2 - store start class name in a text file
      Path startClassFile = localOutDir.resolve(START_CLASS_FILE_NAME);
      Files.writeString(startClassFile, startClass);
      
      // prepare 'lib' subdirectory to store extracted JARs and converted (slim) JAR
      Path localLibDir = Files.createDirectories(localOutDir.resolve(LIB_DIR_NAME));

      // B.3 - traverse fat JAR's content and extract all nested JARs
      extractNestedJars(fatJarPath, localLibDir);

      // B.4 - invoke conversion command to create 'slim' JAR from 'fat' one 
      Convert convertCommand = new Convert();
      convertCommand.setFatJarPath(fatJarPath);
      convertCommand.setSlimJarDir(localLibDir);
      convertCommand.run();
      log.log(INFO, "===================================================================");
      return localLibDir.toAbsolutePath().toString();
    
    } catch (IOException e) {
      e.printStackTrace();
      log.log(ERROR, "Failed to process JAR ''{0}''. Skipped.", fatJarPath);
      return null;
    }
  }

  private String extractStartClass(Path fatJarPath) throws IOException {
    try (JarFile jarFile = new JarFile(fatJarPath.toString(), false)) {
      String startClass = jarFile.getManifest().getMainAttributes().getValue(START_CLASS_ATTRIBUTE_NAME);
      if (startClass != null) {
        log.log(INFO, "Found Start-Class ''{0}'' in file ''{1}''.", startClass, fatJarPath);
        return startClass;
      }
    }
    return null;
  }

  private void extractNestedJars(Path fatJarPath, Path localLibDir) throws IOException {
    try (InputStream fis = Files.newInputStream(fatJarPath)) {
      int filesCount;
      try (ZipInputStream zis = new ZipInputStream(fis)) {
        filesCount = 0;
        ZipEntry nextEntry;
        while ((nextEntry = zis.getNextEntry()) != null) {
          String archivedEntryPath = nextEntry.getName();
          var isPathAcceptable = (archivedEntryPath.startsWith(BOOT_INF_DIR)
                  || archivedEntryPath.startsWith(WEB_INF_DIR))
                  && archivedEntryPath.toLowerCase().endsWith(".jar");
          if (!isPathAcceptable) {
            continue;
          }
          // System.out.printf("Processing archive entry: %s\n", archivedEntryPath);
          Path fileName = Paths.get(archivedEntryPath).getFileName();
          Path extractedEntryPath = localLibDir.resolve(fileName);
          try (OutputStream nextFileOutStream = Files.newOutputStream(extractedEntryPath)) {
            zis.transferTo(nextFileOutStream);
          }
          // System.out.printf("File '%s' extracted to '%s'\n", archivedEntryPath, extractedEntryPath);
          filesCount++;
        }
        zis.closeEntry();
      }
      log.log(INFO, "Extracted {0} files from fat JAR ''{1}'' to ''{2}''", filesCount, fatJarPath, localLibDir);
    } 
  }

  private void createArgFiles(String argFileName, List<String> allLibDirs, Path jsaPath) throws IOException {
    for (String libDir : allLibDirs) {
      Path libDirPath = Paths.get(libDir);
      List<Path> libFilePaths = PathUtils.getDirListing(libDirPath);
      int libsCount = libFilePaths.size();
      String classpath = libFilePaths.stream()
          .map(Path::toString)
          .map(path -> path.replace("\\", "\\\\"))  // to account for @arg-file quotation format 
          .collect(TO_CLASSPATH);
      String startClass = Files.readString(libDirPath.resolveSibling(START_CLASS_FILE_NAME));
      String argFileContent = String.format(SINGLE_ARGFILE_TEMPLATE, libsCount, (libsCount-1), classpath, startClass);
      if (jsaPath != null) {   // prepend the content with -XX:SharedArchiveFile option if path to the archive is known
        argFileContent = String.format(SINGLE_ARGFILE_PREFIX, jsaPath) + argFileContent;
        log.log(DEBUG, "Prepended ''{0}'' argfile with path to JSA file: ''{1}''", argFilePath, jsaPath);
      }
      Path argFilePath = libDirPath.resolveSibling(argFileName);
      Files.writeString(argFilePath, argFileContent);
      log.log(INFO, "Written {0} classpath entries to application arg-file ''{1}''.", libsCount, argFilePath);
    }
  }

  private void setupExclusionMatchers() {
    if (exclusionMatchers.isEmpty()) {
      exclusionGlobs.forEach(exclusionGlob -> 
              exclusionMatchers.add(FileSystems.getDefault().getPathMatcher("glob:" + exclusionGlob)));
    }
  }

  private boolean filterOutExclusions(Path path) {
    return exclusionMatchers.stream()
            .filter(matcher -> matcher.matches(path))
            .findAny()
            .isEmpty();
  }  

  public void setRoot(Path root) {
    this.root = root;
  }

  public void setFatJarArgs(List<String> fatJarArgs) {
    this.fatJarArgs = fatJarArgs;
  }

  public void setExclusionGlobs(Set<String> exclusionGlobs) {
    this.exclusionGlobs = exclusionGlobs;
  }

  public void setOutDir(Path outDir) {
    this.outDir = outDir;
  }

  public void setArgFilePath(/*@Nullable*/ String argFilePath) {
    this.argFilePath = argFilePath;
  }
}
