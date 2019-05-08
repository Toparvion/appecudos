package tech.toparvion.util.appecudos.subcommand;

import picocli.CommandLine.Option;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.*;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static picocli.CommandLine.Command;
import static tech.toparvion.util.appecudos.Constants.START_CLASS_ATTRIBUTE_NAME;

/**
 * @author Toparvion
 */
@Command(name = "extract-fat-jar",
        mixinStandardHelpOptions = true,
        description = "Extracts all nested JAR files from specified Spring Boot 'fat' JAR file")
public class ExplodeFatJar implements Runnable {

  @Option(names = {"--root", "-r"})
  private Path root = Paths.get(System.getProperty("user.dir"));

  @Option(names = {"--fat-jars", "-j"}, required = true)
  private String fatJarsGlob;

  @Option(names = {"--out-dir", "-o"})
  private Path outDir = Paths.get("appcds");
  
  @Override
  public void run() {
    try {
      assert root.isAbsolute() : "root must be an absolute path";
      PathMatcher pathMatcher = FileSystems.getDefault().getPathMatcher("glob:" + fatJarsGlob);
      Files.walk(root)
              .filter(path -> pathMatcher.matches(root.relativize(path)))
              .forEach(this::explode);
      
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private void explode(Path fatJarPath) {
    String startClass = getStartClassAttribute(fatJarPath);
    if (startClass == null) {
      System.err.printf("File '%s' is not a Spring Boot 'fat' JAR. Skipped.\n", fatJarPath);
      return;
    }
    System.out.printf("Main Application Class: '%s'\n", startClass);
    // TODO извлечь имя класса в текстовый файл
    
    try (InputStream fis = Files.newInputStream(fatJarPath)) {
      Path localOutDir = Files.createDirectories(fatJarPath.resolveSibling(outDir));
      ZipInputStream zis = new ZipInputStream(fis);
      ZipEntry nextEntry;
      int filesCount = 0;
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
        Path extractedEntryPath = localOutDir.resolve(fileName);
        try (OutputStream nextFileOutStream = Files.newOutputStream(extractedEntryPath)) {
          zis.transferTo(nextFileOutStream);
        }
//        System.out.printf("File '%s' extracted to '%s'\n", archivedEntryPath, extractedEntryPath);
        filesCount++;
      }
      zis.closeEntry();
      System.out.printf("Extracted %d files from fat JAR '%s' to '%s'\n", filesCount, fatJarPath, localOutDir);

    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  private String getStartClassAttribute(Path fatJarPath) {
    String startClass = null;
    try (JarFile jarFile = new JarFile(fatJarPath.toString())) {
      startClass = jarFile.getManifest().getMainAttributes().getValue(START_CLASS_ATTRIBUTE_NAME);
      
    } catch (IOException e) {
      e.printStackTrace();
    }
    return startClass;
  }


}
