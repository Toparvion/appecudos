package tech.toparvion.util.jcudos.util;

import java.nio.file.*;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * @author Toparvion
 * @since v0.12
 */
public class Traverser {
  
  private enum ArgType {FILE, DIR, GLOB}

  /**
   * Traverses file subtrees starting from given {@code pathArgument}s and collects all the matching file paths into 
   * single set. The matching is determined both by the format of given paths and by exclusions. Every path can one of: 
   * <ul>
   *   <li>An absolute path to a concrete file</li>
   *   <li>A relative path to a concrete file (will be resolved against current working directory)</li>
   *   <li>Either absolute or relative path to a directory (the non-recursive listing of the directory will be used 
   *   as a list of paths to take)</li>
   *   <li>Either absolute or relative Glob pattern covering arbitrary number of files and directories</li>
   * </ul>   
   * @param pathArguments list of paths to inspect
   * @param exclusionGlobs a list of exclusion Glob patterns (like {@code **\noapp\**})
   * @return collection of unique paths matching given criteria
   */
  public static Set<Path> traverse(List<String> pathArguments, List<String> exclusionGlobs) {
    if (pathArguments.isEmpty()) {
      return Set.of();
    }
    
    Set<Path> resultPaths = new LinkedHashSet<>();

    Set<PathMatcher> exclusionMatchers = new HashSet<>();
    exclusionGlobs.forEach(exclusionGlob ->
        exclusionMatchers.add(FileSystems.getDefault().getPathMatcher("glob:" + exclusionGlob)));

    for (String pathArg : pathArguments) {
      ArgType argType;

      Path path = null;
      try {
        path = Paths.get(pathArg);
        argType = Files.isDirectory(path)
            ? ArgType.DIR
            : ArgType.FILE;

      } catch (InvalidPathException e) {
        argType = ArgType.GLOB;
      }

      switch (argType) {
        case DIR:
          exclusionGlobs.addAll(takeDirectory(path, exclusionMatchers));
          break;
          
        case FILE:
          resultPaths.add(path);    // is absolutifying not really needed?
          break;
          
        case GLOB:
          //resultPaths.addAll(takeGlob(pathArg, exclusionMatchers));
          break;
          
        default:
          throw new IllegalArgumentException("Unknown type of path argument " + pathArg);
      }

    }
    return null;
  }

  private static Set<String> takeDirectory(Path dirPath, Set<PathMatcher> exclusionMatchers) {
    return null;
  }

}
