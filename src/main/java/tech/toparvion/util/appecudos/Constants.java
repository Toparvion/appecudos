package tech.toparvion.util.appecudos;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * @author Toparvion
 */
public final class Constants {
  private Constants() { }

  public static final Path START_CLASS_FQN_PATH = Paths.get("start-class.txt");
  public static final String START_CLASS_ATTRIBUTE_NAME = "Start-Class";
}
