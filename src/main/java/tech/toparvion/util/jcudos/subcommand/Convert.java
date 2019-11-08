package tech.toparvion.util.jcudos.subcommand;

import tech.toparvion.util.jcudos.Constants;
import tech.toparvion.util.jcudos.infra.JCudosVersionProvider;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.*;
import java.util.zip.ZipEntry;

import static java.lang.System.Logger.Level.*;
import static picocli.CommandLine.Command;
import static picocli.CommandLine.Option;
import static tech.toparvion.util.jcudos.Constants.BOOT_INF_DIR;
import static tech.toparvion.util.jcudos.Constants.WEB_INF_DIR;

/**
 * @author Toparvion
 */
@Command(name = "convert",
        mixinStandardHelpOptions = true,
        versionProvider = JCudosVersionProvider.class,
        description = "Converts given fat JAR into slim one.%nResulting file has the same name and '.slim.jar' extension.")
public class Convert implements Runnable {
  private static final System.Logger log = System.getLogger(Convert.class.getSimpleName());

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
        try (InputStream sourceInStream = Files.newInputStream(fatJarPath)) {
          try (JarInputStream sourceJarInStream = new JarInputStream(sourceInStream)) {
            // Manifest manifest = sourceJarInStream.getManifest();   <- this does not work (return null) because:
            /* JarInputStream relies on the fact that MANIFEST.MF is one of the first entries in the JAR, but in case of 
             fat JAR this is not true because Spring Boot composes the archive in its own way. Particularly, the first
             entry of the fat JAR is 'org/' directory. That is why jCuDoS have to manually detect and read the manifest.
             The same reason makes jCuDoS write the manifest as an ordinary entry and don't rely on JarOutputStream's
             constructor (because it will immediately write out the manifest which is not ready yet). */
            try (JarOutputStream targetJarOutStream = new JarOutputStream(targetOutStream/*, manifest*/)) {
              JarEntry sourceJarEntry;
              // traverse source JAR entries and process each one 
              while ((sourceJarEntry = sourceJarInStream.getNextJarEntry()) != null) {
                String sourceEntryName = sourceJarEntry.getName();
                // JAR manifest file needs special handling
                if (JarFile.MANIFEST_NAME.equalsIgnoreCase(sourceEntryName)) {
                  convertManifest(sourceJarInStream, targetJarOutStream);
                  continue;
                }
                // nothing but classes are interesting for us here
                var isPathAcceptable = sourceEntryName.startsWith(BOOT_INF_DIR + "classes/")
                                    || sourceEntryName.startsWith(WEB_INF_DIR + "classes/");
                if (!isPathAcceptable) {
                  continue;
                }
                log.log(TRACE, "Processing archive entry: {0}", sourceEntryName);
                Path sourceEntryPath = Paths.get(sourceEntryName);
                if (sourceEntryPath.getNameCount() <= 2) {
                  continue;   // to omit preceding 'BOOT-INF/classes/' and similar directories in the target JAR 
                }
                // turn 'BOOT-INF\classes\org\something\SomeClass.class' into 'org/something/SomeClass.class' 
                String targetEntryPath = sourceEntryPath.subpath(2, sourceEntryPath.getNameCount())
                                                        .toString()
                                                        .replace('\\', '/');
                if (sourceJarEntry.isDirectory()) {
                  String targetDirectoryName = targetEntryPath + "/";
                  JarEntry targetJarEntry = new JarEntry(targetDirectoryName);
                  targetJarOutStream.putNextEntry(targetJarEntry);
                } else {
                  JarEntry targetJarEntry = new JarEntry(targetEntryPath);
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

  /**
   * Reads the manifest from given JAR input stream, clears it from unnecessary attributes and stores into target JAR
   * output stream.
   * @implNote The method assumes that given {@code sourceJarInStream} is already positioned on manifest entry.
   * @param sourceJarInStream input stream of JAR to read from
   * @param targetJarOutStream output stream of JAR to write to
   * @throws IOException in case of any input/output failures 
   */
  private void convertManifest(JarInputStream sourceJarInStream, JarOutputStream targetJarOutStream) throws IOException {
    Manifest manifest = new Manifest(sourceJarInStream);
    List<String> attributesToRemove = new ArrayList<>(Constants.BASE_ATTRIBUTES_NAMES);
    Attributes mainAttributes = manifest.getMainAttributes();
    log.log(DEBUG, "Loaded manifest with {0} attributes.", mainAttributes.size());
    // filter out all SpringBoot-related attributes as they are not needed in slim JAR 
    mainAttributes.keySet()
                  .stream()
                  .map(Object::toString)
                  .filter(name -> name.startsWith("Spring-Boot-"))
                  .forEach(attributesToRemove::add);
    attributesToRemove.stream()
                      .map(Attributes.Name::new)
                      .forEach(mainAttributes::remove);
    mainAttributes.putValue("Created-By", Constants.MY_PRETTY_NAME);
    ZipEntry manifestEntry = new ZipEntry(JarFile.MANIFEST_NAME);
    targetJarOutStream.putNextEntry(manifestEntry);
    manifest.write(targetJarOutStream);
    targetJarOutStream.closeEntry();
    log.log(INFO, "Found, cleaned and wrote manifest with {0} attributes.", mainAttributes.size());
  }

  @SuppressWarnings("WeakerAccess")   // can be called from parent task (JCudos)
  public void setFatJarPath(Path fatJarPath) {
    this.fatJarPath = fatJarPath;
  }

  @SuppressWarnings("WeakerAccess")   // can be called from parent task (JCudos)
  public void setSlimJarDir(Path slimJarDir) {
    this.slimJarDir = slimJarDir;
  }
}
