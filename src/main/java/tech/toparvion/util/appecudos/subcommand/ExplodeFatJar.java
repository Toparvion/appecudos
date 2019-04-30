package tech.toparvion.util.appecudos.subcommand;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static picocli.CommandLine.Command;
import static picocli.CommandLine.Parameters;

/**
 * @author Toparvion
 */
@Command(name = "extract-fat-jar",
        mixinStandardHelpOptions = true,
        description = "Extracts all nested JAR files from specified Spring Boot 'fat' JAR file")
public class ExplodeFatJar implements Runnable {

  @Parameters(index = "0")
  private Path fatJarPath;

  @Parameters(index = "1")
  private Path targetDir;

  @Override
  public void run() {
    String startClass = getStartClassAttribute();
    if (startClass == null) {
      System.err.printf("File '%s' is not a Spring Boot 'fat' JAR. Skipped.\n", fatJarPath);
      return;
    }
    System.out.printf("Main Application Class: '%s'\n", startClass);
    try (InputStream fis = Files.newInputStream(fatJarPath)) {
      ZipInputStream zis = new ZipInputStream(fis);
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
        Path extractedEntryPath = targetDir.resolve(Paths.get(archivedEntryPath).getFileName());
        try (OutputStream nextFileOutStream = Files.newOutputStream(extractedEntryPath)) {
          zis.transferTo(nextFileOutStream);
        }
        System.out.printf("File '%s' extracted to '%s'\n", archivedEntryPath, extractedEntryPath);
      }
      zis.closeEntry();

    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  private String getStartClassAttribute() {
    String startClass = null;
    try (JarFile jarFile = new JarFile(fatJarPath.toString())) {
      startClass = jarFile.getManifest().getMainAttributes().getValue("Start-Class");
//      jarFile.stream()
//              .filter(this::nestedJarFilter)
//              .map(JarEntry::getName)
//              .forEach(System.out::println);
    } catch (IOException e) {
      e.printStackTrace();
    }
    return startClass;
  }


}
