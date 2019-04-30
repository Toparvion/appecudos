package tech.toparvion.util.appecudos;

import picocli.CommandLine;
import tech.toparvion.util.appecudos.subcommand.*;

import java.time.LocalDateTime;

import static picocli.CommandLine.Command;

/**
 * @author Toparvion
 */
@Command(name = "appecudos", 
        mixinStandardHelpOptions = true, version = "APPeCuDoS v1.0",
        subcommands = {
                ListAllClasses.class,
                ListMergedClasses.class,
                Collate.class,
                CopyFilesByList.class,
                ExplodeFatJar.class,
                ConvertJar.class
        })
public class APPeCuDos implements Runnable {

  public static void main(String[] args) {
    CommandLine.run(new APPeCuDos(), args);
  }

  @Override
  public void run() {
    System.out.printf("Main command has been called: %s.", LocalDateTime.now());
  }
}
