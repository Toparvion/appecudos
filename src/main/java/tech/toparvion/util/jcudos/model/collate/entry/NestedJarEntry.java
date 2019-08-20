package tech.toparvion.util.jcudos.model.collate.entry;

import java.util.jar.JarEntry;

/**
 * A wrapper to represent a {@link JarEntry} in Collate's entries map.
 * 
 * @author Toparvion
 */
public class NestedJarEntry {
  private final int checksum;
  private final String name;
  private final long size;
  
  public NestedJarEntry(JarEntry jarEntry) {
    int crcInt;
    try {
      crcInt = Math.toIntExact(jarEntry.getCrc());
    } catch (Exception e) {
      crcInt = (int) jarEntry.getCrc();
    }
    checksum = crcInt;
    name = jarEntry.getName();
    size = jarEntry.getSize();
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    NestedJarEntry that = (NestedJarEntry) o;
    return this.name.equals(that.name) && (this.size == that.size);   // no 'precise' comparison mode is supported yet
  }

  @Override
  public int hashCode() {
    return checksum;
  }

  @Override
  public String toString() {
    return name;
  }
}
