package tech.toparvion.util.jcudos.model.collate.entry;

import tech.toparvion.util.jcudos.Constants;
import tech.toparvion.util.jcudos.Util;

import java.io.IOException;
import java.nio.file.Path;

/**
 * A wrapper to represent a {@link Path} in Collate's entries map. Delegates files comparing to {@link Util} class. 
 *
 * @author Toparvion
 */
public class PathEntry {
  private final Path path;
  private final boolean preciseFileComparisonMode;

  public PathEntry(Path path) {
    assert path != null;
    this.path = path;
    preciseFileComparisonMode = Constants.PRECISE_FILE_COMPARISON_MODE;
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) return true;
    if (other == null || getClass() != other.getClass()) return false;
    PathEntry otherEntry = (PathEntry) other;
    try {
      if (preciseFileComparisonMode) {
        return Util.arePreciselyEqual(this.path, otherEntry.path);

      } else {
        return Util.areRoughlyEqual(this.path, otherEntry.path);
      }
      
    } catch (IOException e) {
      e.printStackTrace();
      return false;
    }
  }

  @Override
  public int hashCode() {
    return path.hashCode();
  }

  @Override
  public String toString() {
    return path.toString();
  }
}
