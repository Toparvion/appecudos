package tech.toparvion.util.jcudos.subcommand;

import tech.toparvion.util.jcudos.Constants;
import tech.toparvion.util.jcudos.infra.JCudosVersionProvider;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.jar.*;

import static java.lang.System.Logger.Level.INFO;
import static java.lang.System.Logger.Level.TRACE;
import static picocli.CommandLine.Command;
import static picocli.CommandLine.Option;

/**
 * @author Toparvion
 */
@Command(name = "convert",
        mixinStandardHelpOptions = true,
        versionProvider = JCudosVersionProvider.class,
        description = "Converts given fat JAR into slim one.%nResulting file has the same name and '.slim.jar' extension.")
public class ConvertJar implements Runnable {
  private static final System.Logger log = System.getLogger(ConvertJar.class.toString());

  @Option(names = {"--input-jar", "-i"}, required = true, description = "Path to input fat JAR file")
  private Path fatJarPath;

  @Option(names = {"--output-dir", "-o"}, description = "Optional path to output directory for resulting slim JAR " +
      "file. Defaults to parent directory of input JAR file.")
  private Path slimJarDir;

  /**
   * @implNote Method does NOT check if given JAR is Spring Boot fat JAR.
   */
  @Override
  public void run() {
    try {
      // compose path to resulting (slim) JAR (that should be in the form of "<source-jar-name>.slim.jar")
      String targetJarName = fatJarPath.getFileName().toString().replaceAll("(?i)\\.(jar)$", ".slim.$1");
      if (slimJarDir == null) {
        slimJarDir = fatJarPath.getParent();
      }
      Path targetJarPath = slimJarDir.resolve(targetJarName);
      // open the source fat JAR for reading
      try (OutputStream targetOutStream = Files.newOutputStream(targetJarPath)) {
        Manifest manifest = new Manifest();
        Attributes attributes = manifest.getMainAttributes();
        attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attributes.putValue("Built-By", Constants.MY_PRETTY_NAME);
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
                log.log(TRACE, "Processing archive entry: {0}", sourceEntryName);
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
      log.log(INFO, "Converted fat jar ''{0}'' into slim one ''{1}''", fatJarPath, targetJarPath);
      
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
