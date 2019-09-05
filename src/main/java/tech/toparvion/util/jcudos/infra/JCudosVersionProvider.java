package tech.toparvion.util.jcudos.infra;

import picocli.CommandLine;

import static tech.toparvion.util.jcudos.util.GeneralUtils.nvls;

/**
 * @author Toparvion
 */
public class JCudosVersionProvider implements CommandLine.IVersionProvider {
  @Override
  public String[] getVersion() {
    String myName = nvls(this.getClass().getPackage().getImplementationTitle(), "jcudos");
    String myVersion = "v" + nvls(this.getClass().getPackage().getImplementationVersion(), "SNAPSHOT");
    return new String[] {myName, myVersion};
  }
}
