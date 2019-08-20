package tech.toparvion.util.jcudos.model.collate;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static java.util.stream.Collectors.toSet;

/**
 * @author Toparvion
 */
public class CollationResult {
  private final Set<String> merging;
  private final Set<String> intersection;
  private final Map<String, List<?>> owns;      // convert to Map<String, List<String>> if necessary

  public CollationResult(Set<?> merging, Set<?> intersection, Map<String, List<?>> owns) {
    this.merging = merging.stream()
        .map(Object::toString)
        .collect(toSet());
    this.intersection = intersection.stream()
        .map(Object::toString)
        .collect(toSet());
    this.owns = owns;
  }

  public Set<String> getMerging() {
    return merging;
  }

  public Set<String> getIntersection() {
    return intersection;
  }

  public Map<String, List<?>> getOwns() {
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
