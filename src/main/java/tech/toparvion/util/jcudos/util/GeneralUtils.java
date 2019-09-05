package tech.toparvion.util.jcudos.util;

import java.io.IOException;
import java.nio.file.OpenOption;
import java.nio.file.Path;

/**
 * @author Toparvion
 */
public final class GeneralUtils {
  private GeneralUtils() { }

  /**
   * Encloses given action to {@code try/catch} block and wraps {@link IOException} 
   * into {@link RuntimeException} if happens. 
   * @param action any action that may throw {@link IOException}, e.g.
   * {@link java.nio.file.Files#writeString(Path, CharSequence, OpenOption...) Files#writeString}
   */
  public static void wrap(ExplosiveVoidAction action) {
    try {
      action.act();
      
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  public static <T> T wrap(ExplosiveReturningAction<T> action) {
    try {
      return action.act();
      
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  public static void suppress(ExplosiveVoidAction action) {
    try {
      action.act();
    
    } catch (IOException e) {
      e.printStackTrace();
      // and nothing more to do here
    }
  }

  @FunctionalInterface
  public interface ExplosiveVoidAction {

    void act() throws IOException;
  }
  
  @FunctionalInterface
  public interface ExplosiveReturningAction<T> {

    T act() throws IOException;
  }
  
  public static String nvls(String val, String def) {
    if (val == null) {
      return def;
    }
    return val;
  }
}
