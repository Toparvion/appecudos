package tech.toparvion.util.appecudos;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.util.Comparator;

/**
 * @author Toparvion
 */
public final class Util {
  private Util() { }

  public static Path prepareDir(Path desiredPath) throws IOException {
    if (Files.isDirectory(desiredPath)) {
      System.out.printf("Directory '%s' already exists. Cleaning it out...\n", desiredPath);
      long deletedEntriesCount = Files.walk(desiredPath)
              .sorted(Comparator.reverseOrder())
              .map(Path::toFile)
              .map(File::delete)
              .filter(isDeleted -> isDeleted)
              .count();
      System.out.printf("Directory '%s' deleted with %d entries.\n", desiredPath, (deletedEntriesCount-1));
    }
    return Files.createDirectories(desiredPath);
  }

  /**
   * Encloses given action to {@code try/catch} block and wraps {@link IOException} 
   * into {@link RuntimeException} if happens. 
   * @param action any action that may throw {@link IOException}, e.g.
   * {@link java.nio.file.Files#writeString(Path, CharSequence, OpenOption...) Files#writeString}
   */
  public static void suppress(ExplosiveVoidAction action) {
    try {
      action.act();
      
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  public static <T> T suppress(ExplosiveReturningAction<T> action) {
    try {
      return action.act();
    } catch (IOException e) {
      throw new RuntimeException(e);
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
}
