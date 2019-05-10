package tech.toparvion.util.appecudos.subcommand;

import picocli.CommandLine.Option;
import tech.toparvion.util.appecudos.Util;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.*;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static java.util.stream.Collectors.toList;
import static picocli.CommandLine.Command;
import static tech.toparvion.util.appecudos.Constants.*;

/**
 * @author Toparvion
 * TODO extract nested JARs processing and start class saving into separate reusable subcommands
 */
@Command(name = "process-fat-jars",
        mixinStandardHelpOptions = true,
        description = "Extracts all nested JARs and Start-Class attribute from specified Spring Boot 'fat' JARs")
public class ProcessFatJars implements Callable<List<String>> {

  @Option(names = {"--root", "-r"})
  private Path root = Paths.get(System.getProperty("user.dir"));

  @Option(names = {"--fat-jars", "-j"}, required = true)
  private String fatJarsGlob;

  @Option(names = {"--out-dir", "-o"})
  private Path outDir = Paths.get("appcds");
  
  @Override
  public List<String> call() {
    try {
      assert root.isAbsolute() : "--root must be absolute if specified";
      assert !outDir.isAbsolute() : "--out-dir must NOT be absolute if specified";
      PathMatcher pathMatcher = FileSystems.getDefault().getPathMatcher("glob:" + fatJarsGlob);
      // here we filter JAR files only, postponing detection if they fat or not to 'process' method
      return Files.walk(root)
              .filter(path -> pathMatcher.matches(root.relativize(path)))
              .map(this::process)
              .filter(Objects::nonNull)
              .collect(toList());
      
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private String process(Path fatJarPath) {
    try {
      // B.1 - check if given JAR is 'fat' by searching for Start-Class in its manifest 
      String startClass = extractStartClass(fatJarPath);
      if (startClass == null) {
        System.err.printf("File '%s' is not a Spring Boot 'fat' JAR. Skipped.\n", fatJarPath);
        return null;
      }
      // prepare local output dir (removing its content firstly if needed) 
//       Path localOutDir = fatJarPath.resolveSibling(outDir);
       Path localOutDir = Util.prepareDir(fatJarPath.resolveSibling(outDir));
      
      // B.2 - store start class name in a text file
      Path startClassFile = localOutDir.resolve(START_CLASS_FILE_NAME);
      Files.writeString(startClassFile, startClass);
      
      // prepare 'lib' subdirectory to store extracted JARs and converted (slim) JAR
      Path localLibDir = Files.createDirectories(localOutDir.resolve(LIB_DIR_NAME));

      // B.3 - traverse fat JAR's content and extract all nested JARs
      extractNestedJars(fatJarPath, localLibDir);

      // B.4 - invoke conversion command to create 'slim' JAR from 'fat' one 
      ConvertJar convertCommand = new ConvertJar();
      convertCommand.setFatJarPath(fatJarPath);
      convertCommand.setSlimJarDir(localLibDir);
      convertCommand.run();
      System.out.println("===================================================");
      return localLibDir.toAbsolutePath().toString();
    
    } catch (IOException e) {
      e.printStackTrace();
      System.err.printf("Failed to process JAR '%s'. Skipped.\n", fatJarPath);
      return null;
    }
  }

  private void extractNestedJars(Path fatJarPath, Path localLibDir) throws IOException {
    try (InputStream fis = Files.newInputStream(fatJarPath)) {
      int filesCount;
      try (ZipInputStream zis = new ZipInputStream(fis)) {
        filesCount = 0;
        ZipEntry nextEntry;
        while ((nextEntry = zis.getNextEntry()) != null) {
          String archivedEntryPath = nextEntry.getName();
          var isPathAcceptable = (archivedEntryPath.startsWith("BOOT-INF/")
                  || archivedEntryPath.startsWith("WEB-INF/"))
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
  //        System.out.printf("File '%s' extracted to '%s'\n", archivedEntryPath, extractedEntryPath);
          filesCount++;
        }
        zis.closeEntry();
      }
      System.out.printf("Extracted %d files from fat JAR '%s' to '%s'\n", filesCount, fatJarPath, localLibDir);
    } 
  }

  private String extractStartClass(Path fatJarPath) throws IOException {
    try (JarFile jarFile = new JarFile(fatJarPath.toString(), false)) {
      String startClass = jarFile.getManifest().getMainAttributes().getValue(START_CLASS_ATTRIBUTE_NAME);
      if (startClass != null) {
        System.out.printf("Found Start-Class '%s'.\n", startClass);
        return startClass;
      }
    } 
    return null;
  }


  public void setRoot(Path root) {
    this.root = root;
  }

  public void setFatJarsGlob(String fatJarsGlob) {
    this.fatJarsGlob = fatJarsGlob;
  }
}
