package tech.toparvion.util.jcudos.util;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static java.nio.file.StandardCopyOption.COPY_ATTRIBUTES;

/**
 * @author Toparvion
 */
public final class PathUtils {
  private PathUtils() { }

  /**
   * Recursively removes all the content from specified directory if it exists. The directory itself remains but 
   * becomes empty.  
   * @param dirPath directory to clean 
   * @return path to cleaned directory
   * @throws IOException in case of IO errors during clearing
   */
  public static Path prepareDir(Path dirPath) throws IOException {
    if (Files.isDirectory(dirPath)) {
      System.out.printf("Directory '%s' already exists. Cleaning it out...\n", dirPath);
      long deletedEntriesCount = Files.walk(dirPath)
              .sorted(Comparator.reverseOrder())
              .map(Path::toFile)
              .map(File::delete)
              .filter(isDeleted -> isDeleted)
              .count();
      System.out.printf("Directory '%s' deleted with %d entries.\n", dirPath, (deletedEntriesCount-1));
    }
    return Files.createDirectories(dirPath);
  }

  /**
   * @param dirPath a directory to get index for
   * @return a list of paths that comprise the specified directory content
   * @throws IOException in case of IO errors
   */
  public static List<Path> getDirListing(Path dirPath) throws IOException {
    List<Path> listing = new ArrayList<>();
    try (DirectoryStream<Path> dirStream = Files.newDirectoryStream(dirPath)) {
      dirStream.forEach(listing::add);
    }
    return listing;
  }

  /**
   * Copies specified file into specified directory keeping the same name
   * @param sourceFile path to file to copy
   * @param targetLibDir path to directory to copy the file into
   * @return path to copied file
   */
  public static Path copyFile(Path sourceFile, Path targetLibDir) {
    try {
      Path targetFile = targetLibDir.resolve(sourceFile.getFileName());
      Files.copy(sourceFile, targetFile, COPY_ATTRIBUTES);
      return targetFile;

    } catch (IOException e) {
      e.printStackTrace();
      return null;
    }
  }

  /**
   * Deletes from specified directory all the files that match specified filter 
   * @param libDir directory to delete files from
   * @param libFilter filter to select files to delete
   */
  public static void deleteFilesByFilter(Path libDir, DirectoryStream.Filter<Path> libFilter) {
    try (DirectoryStream<Path> dirStream = Files.newDirectoryStream(libDir, libFilter)) {
      for (Path path : dirStream) {
        Files.deleteIfExists(path);
      }

    } catch (IOException e) {
      e.printStackTrace();
    }
  }
}
