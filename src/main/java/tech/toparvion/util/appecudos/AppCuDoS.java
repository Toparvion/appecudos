package tech.toparvion.util.appecudos;

import picocli.CommandLine;
import picocli.CommandLine.Option;
import tech.toparvion.util.appecudos.model.CollationResult;
import tech.toparvion.util.appecudos.subcommand.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;

import static java.lang.System.Logger.Level.ERROR;
import static java.lang.System.Logger.Level.INFO;
import static picocli.CommandLine.Command;

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
                ExplodeFatJar.class,
                ConvertJar.class
        })
public class AppCuDoS implements Runnable {
  private static final System.Logger log;
  static {
    System.setProperty("java.util.logging.SimpleFormatter.format", "%1$tF %1$tT %4$s %2$s - %5$s%6$s%n");
    log = System.getLogger(AppCuDoS.class.toString());
  }

  private static final Path COMMON_CLASS_LIST_PATH = Paths.get("list/classes.list");

  @Option(names = {"--class-lists", "-c"}, required = true)
  private String classListGlob;
  
  @Option(names = {"--fat-jars", "-f"}, required = true)
  private String fatJarsGlob;
  
  @Option(names = {"--common-out-dir", "-o"}, required = true)
  private Path outDirPath;
  
  @Option(names = {"--root", "-r"})
  private Path root;

  public static void main(String[] args) {
    CommandLine.run(new AppCuDoS(), args);
  }

  @Override
  public void run() {
    fixPaths();
    log.log(INFO, "AppCuDoS has been called: classListGlob={0}, fatJarsGlob={1}, commonDirPath={2}, root={3}", 
            classListGlob, fatJarsGlob, outDirPath, root);
    try {
      processClassLists(root, classListGlob);
      
    } catch (IOException e) {
      e.printStackTrace();
      System.exit(1);
    }
  }

  /**
   * Stage A - class lists processing
   * @param root root directory of microcevices
   * @param classListGlob relative Glob pattern to find out files to process 
   * @throws IOException in case of any IO error
   */
  private void processClassLists(Path root, String classListGlob) throws IOException {
    // A.1 - find common part among all class lists
    Collate collateCommand = new Collate();
    collateCommand.setArgs(List.of(classListGlob));
    collateCommand.setRoot(root);
    CollationResult result = collateCommand.call();
    
    // A.2 - save the common part as separate list in output directory
    Path commonClassListPath = outDirPath.resolve(COMMON_CLASS_LIST_PATH);
    Files.createDirectories(commonClassListPath.getParent());
    Set<String> merging = result.getMerging();
    Files.write(commonClassListPath, merging);
    
    log.log(INFO, "{0} class names saved into ''{1}''", merging.size(), commonClassListPath);
  }

  /**
   * Stage B - fat JARs processing
   * @param root root directory of microcevices
   * @param fatJarsGlob relative Glob pattern to find out files to process
   * @throws IOException in case of any IO error
   */
  private void processFatJars(Path root, String fatJarsGlob) throws IOException {
    // TODO прикрутить ExplodeFatJar
    
  }

  private void fixPaths() {
    // fix root dir path
    if (root != null) {
      if (!root.isAbsolute()) {
        log.log(ERROR, "If ''root'' option is specified, it must be absolute. ''{0}'' is not correct.", root);
        System.exit(1);
      }
    } else {
      root = Paths.get(System.getProperty("user.dir"));
    }

    // fix output dir path
    if (!outDirPath.isAbsolute()) {
      outDirPath = root.resolve(outDirPath);
    }
  }
}
