package tech.toparvion.util.appecudos.subcommand;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.jar.*;

import static picocli.CommandLine.*;

/**
 * @author Toparvion
 */
@Command(name = "convert",
        mixinStandardHelpOptions = true,
        description = "Converts all found fat JARs to slim ones")
public class ConvertJar implements Runnable {

  private static final Attributes.Name START_CLASS_ATTRIBUTE_NAME = new Attributes.Name("Start-Class");
  
  @Option(names = {"-r", "--root"})
  private Path root;  
  
  @Parameters(paramLabel = "JARs", description = "Glob pattern to JARs to extract")
  private String jarsGlob;

  @Override
  public void run() {
    try {
      var pathMatcher = FileSystems.getDefault().getPathMatcher("glob:" + jarsGlob);
      if (root == null) {
        root = Paths.get(System.getProperty("user.dir"));
      }
      Files.walk(root)
              .filter(path -> pathMatcher.matches(root.relativize(path)))
              .filter(this::filterFatJar)
              //.peek(System.out::println)
              .forEach(this::convert);
      
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  private void convert(Path fatJarPath) {
    try {
      // open source fat JAR for reading
      String targetJarName = fatJarPath.getFileName().toString().replaceAll("(?i)\\.(jar)$", ".slim.$1");
      Path targetJarPath = fatJarPath.resolveSibling(targetJarName);
      try (OutputStream targetOutStream = Files.newOutputStream(targetJarPath)) {
        System.out.printf("Source archive: '%s', target archive: '%s'\n", fatJarPath, targetJarPath);
        Manifest manifest = new Manifest();
        Attributes attributes = manifest.getMainAttributes();
        attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attributes.putValue("Built-By", "APPeCuDoS");
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
    } catch (IOException e) {
      e.printStackTrace();
    }

  }

  private boolean filterFatJar(Path path) {
    try (JarFile jarFile = new JarFile(path.toFile())) {
      boolean isSbFatJar = jarFile.getManifest().getMainAttributes().containsKey(START_CLASS_ATTRIBUTE_NAME);
      if (!isSbFatJar) {
        System.err.printf("File '%s' is not Spring Boot 'fat' JAR.\n", path);
      }
      return isSbFatJar;

    } catch (IOException e) {
      e.printStackTrace();
      return false;
    }
  }
}
