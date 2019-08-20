package tech.toparvion.util.jcudos.subcommand;

import tech.toparvion.util.jcudos.Constants;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static java.nio.file.StandardCopyOption.COPY_ATTRIBUTES;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toList;
import static picocli.CommandLine.Command;
import static picocli.CommandLine.Option;

/**
 * @author Toparvion
 */
@Command(name = "copy-by-list",
        mixinStandardHelpOptions = true,
        description = "Copies files specified as a list")
public class CopyFilesByList implements Runnable {

  @Option(names = {"-b", "--base-dir", "--basedir"}, description = "Path to directory against which paths from list file will be resolved")
  private Path baseDir;
  
  @Option(names = {"-l", "--list"}, description = "Path to list of file names to copy")
  private Path list;
  
  @Option(names = {"-a", "--arg-file", "--argfile"}, description = "Path to arg file with classpath for 'java -Xshare:dump'")
  private Path argFilePath;

  @Option(names = {"-t", "--target"}, description = "Path to directory to copy specified files")
  private Path targetDir;

  @Override
  public void run() {
    try {
      if (baseDir == null) {
        baseDir = Paths.get(System.getProperty("user.dir"));
      }
      List<String> lines = Files.readAllLines(list);
      System.out.printf("Loaded %d file names from list %s\n", lines.size(), list);
      List<Path> jarList = lines.stream()
              .map(Paths::get)
              .map(fileName -> baseDir.resolve(fileName))
              .collect(toList());
      jarList.forEach(this::copy);
      System.out.printf("Loaded files copied to directory %s\n", targetDir);

      if (argFilePath == null) {
        return;
      }
      if (!argFilePath.isAbsolute()) {
        argFilePath = targetDir.resolveSibling(argFilePath);
      }
      String classpath = jarList.stream()
              .map(Path::toString)
              .collect(joining(":\\\n  ", " \"", "\""));
      String argFileContent = Constants.COMMON_ARGFILE_INTRO + classpath;
      Files.writeString(argFilePath, argFileContent);
      System.out.printf("Arg file created: %s\n", argFilePath);

    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  private void copy(Path sourcePath) {
    Path targetPath = targetDir.resolve(sourcePath.getFileName());
    try {
      Files.copy(sourcePath, targetPath, COPY_ATTRIBUTES, REPLACE_EXISTING);
      
    } catch (IOException e) {
      System.out.printf("Failed to copy file '%s' to '%s'. Skipped.", sourcePath, targetPath);
      e.printStackTrace();
    }
  }
}
