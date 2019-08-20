package tech.toparvion.util.jcudos;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.Comparator;

import static java.nio.file.Files.isSameFile;

/**
 * @author Toparvion
 */
public final class Util {
  /**
   * buffer size used for reading and writing
   * @implNote Taken from JDK 12's {@link Files} class
   */
  private static final int BUFFER_SIZE = 8192;

  /**
   * Global mode flag for choosing between different approaches to comparing files with each other
   */
  public static boolean PRECISE_FILE_COMPARISON_MODE; 

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
   * Compares given paths in a "rough" manner, i.e. doesn't read the content of either file but checks (1) the names 
   * of the files and (2) their sizes. If both values are equal for both files then the files are considered equal.
   * @implNote This method implements the fastest file comparison strategy. Though it's not the most accurate, e.g. 
   * if one file happens to be a permutation of bytes of another, this method would consider them equal. To make sure
   * the content of files is really accounted, use precise (but slow) {@link #arePreciselyEqual(Path, Path)} method. 
   * @param one a path to one file
   * @param another a path to another file 
   * @return {@code true} if files are equal (see description for details)
   * @throws IOException in case of files access failures
   */
  public static boolean areRoughlyEqual(Path one, Path another) throws IOException {
    // first compare file names (none of timestamps are considered because they can vary slightly during build process)
    Path oneFileName = one.getFileName();
    Path anotherFileName = another.getFileName();
    boolean sameNames = oneFileName.equals(anotherFileName);
    if (!sameNames) {
      return false;   // it's enough to consider files different in this (rough) mode if they have different names 
    }
    // read the attributes of both files
    var oneAttributes = Files.readAttributes(one, BasicFileAttributes.class);
    var anotherAttributes = Files.readAttributes(another, BasicFileAttributes.class);
    // and take files' sizes to compare them
    long oneSize = oneAttributes.size();
    long anotherSize = anotherAttributes.size();
    // since names are equal (we've checked that before), the result now depends on sizes only
    return (oneSize == anotherSize);
  }

  /**
   * Compares given paths in a precise manner, i.e. {@linkplain Util#mismatch(java.nio.file.Path, java.nio.file.Path) checks} 
   * every byte of one file against corresponding byte of another until finds a difference. 
   * @implNote This method implements the most accurate comparison strategy. Though it is quite slow because requires
   * opening and reading the content of a file byte by byte. To gain better performance with a chance of error, use
   * {@link #areRoughlyEqual(Path, Path)} method.
   * @param one a path to one file
   * @param another a path to another file 
   * @return {@code true} if files are equal (see description for details)
   * @throws IOException in case of files access failures
   */
  public static boolean arePreciselyEqual(Path one, Path another) throws IOException {
    // this comparison doesn't account a chance that one file is a prefix of another (see mismatch method's javadoc)
    return (-1 == mismatch(one, another));
  }

  /**
   * Finds and returns the position of the first mismatched byte in the content
   * of two files, or {@code -1L} if there is no mismatch. The position will be
   * in the inclusive range of {@code 0L} up to the size (in bytes) of the
   * smaller file.
   *
   * <p> Two files are considered to match if they satisfy one of the following
   * conditions:
   * <ul>
   * <li> The two paths locate the {@linkplain Files#isSameFile(Path, Path) same file},
   *      even if two {@linkplain Path#equals(Object) equal} paths locate a file
   *      does not exist, or </li>
   * <li> The two files are the same size, and every byte in the first file
   *      is identical to the corresponding byte in the second file. </li>
   * </ul>
   *
   * <p> Otherwise there is a mismatch between the two files and the value
   * returned by this method is:
   * <ul>
   * <li> The position of the first mismatched byte, or </li>
   * <li> The size of the smaller file (in bytes) when the files are different
   *      sizes and every byte of the smaller file is identical to the
   *      corresponding byte of the larger file. </li>
   * </ul>
   *
   * <p> This method may not be atomic with respect to other file system
   * operations. This method is always <i>reflexive</i> (for {@code Path f},
   * {@code mismatch(f,f)} returns {@code -1L}). If the file system and files
   * remain static, then this method is <i>symmetric</i> (for two {@code Paths f}
   * and {@code g}, {@code mismatch(f,g)} will return the same value as
   * {@code mismatch(g,f)}).
   *
   * @param   path
   *          the path to the first file
   * @param   path2
   *          the path to the second file
   *
   * @return  the position of the first mismatch or {@code -1L} if no mismatch
   *
   * @throws  IOException
   *          if an I/O error occurs
   * @throws  SecurityException
   *          In the case of the default provider, and a security manager is
   *          installed, the {@link SecurityManager#checkRead(String) checkRead}
   *          method is invoked to check read access to both files.
   *
   * @since 12
   * @implNote For jCudos the method is entirely taken from JDK 12's {@link Files} class (to avoid making it a base JDK)
   */
  private static long mismatch(Path path, Path path2) throws IOException {
    if (isSameFile(path, path2)) {
      return -1;
    }
    byte[] buffer1 = new byte[BUFFER_SIZE];
    byte[] buffer2 = new byte[BUFFER_SIZE];
    try (InputStream in1 = Files.newInputStream(path);
         InputStream in2 = Files.newInputStream(path2);) {
      long totalRead = 0;
      while (true) {
        int nRead1 = in1.readNBytes(buffer1, 0, BUFFER_SIZE);
        int nRead2 = in2.readNBytes(buffer2, 0, BUFFER_SIZE);

        int i = Arrays.mismatch(buffer1, 0, nRead1, buffer2, 0, nRead2);
        if (i > -1) {
          return totalRead + i;
        }
        if (nRead1 < BUFFER_SIZE) {
          // we've reached the end of the files, but found no mismatch
          return -1;
        }
        totalRead += nRead1;
      }
    }
  }

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
}
