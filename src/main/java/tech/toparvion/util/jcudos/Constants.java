package tech.toparvion.util.jcudos;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.jar.Attributes;
import java.util.stream.Collector;

import static java.lang.String.format;
import static java.util.stream.Collectors.joining;

/**
 * @author Toparvion
 */
@SuppressWarnings("WeakerAccess")     // access level is left maximum for simplicity 
public final class Constants {
  private Constants() { }

  public static final String MY_NAME = "jcudos";
  public static final String MY_PRETTY_NAME = "jCuDoS";

  private static final String PATH_SEPARATOR = System.getProperty("path.separator");
  private static final String NEW_LINE = System.getProperty("line.separator");

  public static final String COMMON_ARGFILE_INTRO = 
          "-classpath" + NEW_LINE + 
          "# Common (shared) classpath" + NEW_LINE;

  /**
   * Argfile template to be used in processing of multiple different applications
   */
  public static final String PRIVATE_ARGFILE_TEMPLATE =
      "-XX:SharedArchiveFile=%s" + NEW_LINE +     // path to '_appcds/jsa/classes.jsa'    
          "-classpath" + NEW_LINE + 
          "# the first %d entries are shared and the last %d entries (starting at line %d) are private" +
          NEW_LINE + "%s" + NEW_LINE + 
          "# application's main class" + NEW_LINE + 
          "%s" + NEW_LINE +
          "# Carefully generated with " + MY_PRETTY_NAME + NEW_LINE;

  /**
   * Argfile template to be used in processing of single application
   */
  public static final String SINGLE_ARGFILE_TEMPLATE =
      "-classpath" + NEW_LINE + 
          "# there are %d entries in this classpath: 1 for the app itself and %d for its libraries " + NEW_LINE + 
          "%s" + NEW_LINE + 
          "# application's main class" + NEW_LINE + 
          "%s" + NEW_LINE +
          "# Carefully generated with " + MY_PRETTY_NAME + NEW_LINE;
  /** Optional prefix to be used when JSA archive path is already known */
  public static final String SINGLE_ARGFILE_PREFIX = "-XX:SharedArchiveFile=%s" + NEW_LINE;

  public static final Collector<CharSequence, ?, String> TO_CLASSPATH = 
          joining(format("%s\\%s  ", PATH_SEPARATOR, NEW_LINE), " \"", "\"");

  public static final Path SHARED_ROOT = Paths.get("_shared/");
  public static final Path SHARED_CLASS_LIST_PATH = SHARED_ROOT.resolve("list/classes.list");
  public static final Path SHARED_ARGFILE_PATH = SHARED_ROOT.resolve("list/classpath.arg");
  public static final Path SHARED_ARCHIVE_PATH = SHARED_ROOT.resolve("jsa/classes.jsa");

  public static final String SPRING_BOOT_START_CLASS_ATTRIBUTE = "Start-Class";
  public static final String APPCDS_ARGFILE_NAME = "appcds.arg";
  public static final String START_CLASS_FILE_NAME = "start-class.txt";
  public static final Attributes.Name START_CLASS_ATTRIBUTE_NAME = new Attributes.Name(SPRING_BOOT_START_CLASS_ATTRIBUTE);
  
  public static final String JDK_MAIN_CLASS_ATTRIBUTE = "Main-Class";
  public static final List<String> BASE_ATTRIBUTES_NAMES = List.of(JDK_MAIN_CLASS_ATTRIBUTE, SPRING_BOOT_START_CLASS_ATTRIBUTE);
  
  public static final String BOOT_INF_DIR = "BOOT-INF/";
  public static final String WEB_INF_DIR = "WEB-INF/";

  public static final String LIB_DIR_NAME = "lib";
  public static final String LOCK_FILE_NAME = ".lock";

  public enum ListConversion { ON, OFF, AUTO }
  
  /**
   * A combination of tags used in JVM Unified Logging Framework when tracing the class loading.
   * @see <a href="https://docs.oracle.com/en/java/javase/11/tools/java.html#GUID-BE93ABDC-999C-4CB5-A88B-1994AAAC74D5">JVM Unified Logging Framework</a> 
   */
  public static final String CLASSLOADING_TRACE_TAGS = "class,load";

  /**
   * Global mode flag for choosing between different approaches to comparing files with each other.
   * Precisely speaking it's not a constant because it has no {@code final} modifier but in fact, once assigned, its 
   * value stays the same for the whole runtime and thus can be considered constant.
   */
  public static boolean PRECISE_FILE_COMPARISON_MODE;

  // application exit codes
  
  public static final int ALREADY_IN_PROGRESS_EXIT_CODE = 1;
  public static final int APPCDS_ERROR_EXIT_CODE        = 2;
  public static final int INTERNAL_ERROR_EXIT_CODE      = 3;
}
