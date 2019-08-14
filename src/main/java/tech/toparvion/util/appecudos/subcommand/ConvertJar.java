package tech.toparvion.util.appecudos.subcommand;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.jar.*;

import static picocli.CommandLine.Command;
import static picocli.CommandLine.Option;

/**
 * @author Toparvion
 */
@Command(name = "convert",
        mixinStandardHelpOptions = true,
        description = "Converts given fat JAR to slim one")
public class ConvertJar implements Runnable {

  @Option(names = {"--input-jar", "-i"}, required = true)
  private Path fatJarPath;

  @Option(names = {"--output-dir", "-o"})
  private Path slimJarDir;

  /**
   * @implNote Method does NOT check if given JAR is Spring Boot fat JAR.
   */
  @Override
  public void run() {
    try {
      // compose path to resulting (slim) JAR
      String targetJarName = fatJarPath.getFileName().toString().replaceAll("(?i)\\.(jar)$", ".slim.$1");
      if (slimJarDir == null) {
        slimJarDir = fatJarPath.getParent();
      }
      Path targetJarPath = slimJarDir.resolve(targetJarName);
      // open source fat JAR for reading
      try (OutputStream targetOutStream = Files.newOutputStream(targetJarPath)) {
        Manifest manifest = new Manifest();
        Attributes attributes = manifest.getMainAttributes();
        attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attributes.putValue("Built-By", "jCuDoS");
        try (JarOutputStream targetJarOutStream = new JarOutputStream(targetOutStream, manifest)) {
          try (InputStream sourceInStream = Files.newInputStream(fatJarPath)) {
            try (JarInputStream sourceJarInStream = new JarInputStream(sourceInStream)) {
              JarEntry sourceJarEntry;
              while ((sourceJarEntry = sourceJarInStream.getNextJarEntry()) != null) {
                String sourceEntryName = sourceJarEntry.getName();
                var isPathAcceptable = sourceEntryName.startsWith("BOOT-INF/classes/")
                                    || sourceEntryName.startsWith("WEB-INF/classes/");
                if (!isPathAcceptable) {
                  continue;
                }
                // System.out.printf("Processing archive entry: %s\n", sourceEntryName);
                Path sourceEntryPath = Paths.get(sourceEntryName);
                if (sourceEntryPath.getNameCount() <= 2) {
                  continue;
                }
                Path targetEntryPath = sourceEntryPath.subpath(2, sourceEntryPath.getNameCount());
                if (sourceJarEntry.isDirectory()) {
                  String targetDirectoryName = targetEntryPath.toString() + "/";
                  JarEntry targetJarEntry = new JarEntry(targetDirectoryName);
                  targetJarOutStream.putNextEntry(targetJarEntry);
                } else {
                  JarEntry targetJarEntry = new JarEntry(targetEntryPath.toString());
                  targetJarOutStream.putNextEntry(targetJarEntry);
                  sourceJarInStream.transferTo(targetJarOutStream);
                  targetJarOutStream.closeEntry();
                }
              }
            }
          }
        }
      }
      System.out.printf("Converted fat jar '%s' to slim one '%s'\n", fatJarPath, targetJarPath);
      
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  public void setFatJarPath(Path fatJarPath) {
    this.fatJarPath = fatJarPath;
  }

  public void setSlimJarDir(Path slimJarDir) {
    this.slimJarDir = slimJarDir;
  }
}
