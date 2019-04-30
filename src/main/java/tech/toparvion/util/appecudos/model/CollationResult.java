package tech.toparvion.util.appecudos.model;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * @author Toparvion
 */
public class CollationResult {
  private final Set<String> merging;
  private final Set<String> intersection;
  private final Map<String, List<String>> owns;

  public CollationResult(Set<String> merging, Set<String> intersection, Map<String, List<String>> owns) {
    this.merging = merging;
    this.intersection = intersection;
    this.owns = owns;
  }

  public Set<String> getMerging() {
    return merging;
  }

  public Set<String> getIntersection() {
    return intersection;
  }

  public Map<String, List<String>> getOwns() {
    return owns;
  }

  @Override
  public String toString() {
    return "CollationResult{" +
            "merging=" + merging +
            ", intersection=" + intersection +
            ", owns=" + owns +
            '}';
  }
}
