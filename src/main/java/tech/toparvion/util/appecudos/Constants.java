package tech.toparvion.util.appecudos;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.jar.Attributes;
import java.util.stream.Collector;

import static java.util.stream.Collectors.joining;

/**
 * @author Toparvion
 */
public final class Constants {
  private Constants() { }

  public static final String COMMON_ARGFILE_INTRO = 
          "-classpath\n" +
          "# Common (shared) classpath\n";

  public static final String PRIVATE_ARGFILE_TEMPLATE =
                  "-XX:SharedArchiveFile=%s\n" +     // path to '_appcds/jsa/classes.jsa'    
                  "-classpath\n" +
                  "# the first %d entries are shared and the last %d entries (starting at line %d) are private\n" +
                  "%s\n" +
                  "# application's main class\n" +
                  "%s\n";

  public static final Collector<CharSequence, ?, String> TO_CLASSPATH = joining(":\\\n  ", " \"", "\"");

  public static final Path SHARED_ROOT = Paths.get("_shared/");
  public static final Path SHARED_CLASS_LIST_PATH = SHARED_ROOT.resolve("list/classes.list");
  public static final Path SHARED_ARGFILE_PATH = SHARED_ROOT.resolve("list/classpath.arg");
  public static final Path SHARED_ARCHIVE_PATH = SHARED_ROOT.resolve("jsa/classes.jsa");

  public static final String APPCDS_ARGFILE_NAME = "appcds.arg";
  public static final String START_CLASS_FILE_NAME = "start-class.txt";
  public static final Attributes.Name START_CLASS_ATTRIBUTE_NAME = new Attributes.Name("Start-Class");

  public static final String LIB_DIR_NAME = "lib";
}
