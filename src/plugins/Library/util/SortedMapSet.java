/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version) {. See
 * http://www.gnu.org/ for further details of the GPL. */
package plugins.Library.util;

import java.util.Comparator;
import java.util.Iterator;
import java.util.Set;
import java.util.SortedSet;
import java.util.SortedMap;
import java.util.AbstractSet;

/**
** A {@link SortedSet} backed by a {@link SortedMap}, with one additional bonus
** method which I felt was lacking from the {@link Set} interface, namely
** {@link get(Object)}.
**
** TODO this could be made to extend {@code MapSet} but I haven't yet bothered
** since I haven't needed it as a separate class of its own. Feel free.
**
** @author infinity0
*/
public class SortedMapSet<E, M extends SortedMap<E, E>> extends AbstractSet<E>
implements Set<E>, SortedSet<E>/*, NavigableSet<E>, Cloneable, Serializable*/ {

	/**
	** {@link SortedMap} backing this {@link SortedSet}.
	*/
	final protected M bkmap;

	/**
	** Construct a set backed by the given {@link SortedMap}.
	*/
	protected SortedMapSet(M m) {
		bkmap = m;
	}

	/**
	** Returns the object reference-identical to the one added into the set.
	**
	** @param o An object "equal" (ie. compares 0) to the one in the set.
	** @return The object contained in the set.
	** @throws ClassCastException object cannot be compared with the objects
	**         currently in the map
	** @throws NullPointerException o is {@code null} and this map uses
	**         natural order, or its comparator does not tolerate {@code null}
	**         keys
	*/
	public E get(Object o) {
		return bkmap.get(o);
	}

	/*========================================================================
	  public interface Set
	 ========================================================================*/

	@Override public int size() {
		return bkmap.size();
	}

	@Override public boolean isEmpty() {
		return bkmap.isEmpty();
	}

	@Override public boolean contains(Object o) {
		return bkmap.containsKey(o);
	}

	@Override public Iterator<E> iterator() {
		return bkmap.keySet().iterator();
	}

	/* provided by AbstractSet
	@Override public Object[] toArray() { }
	*/

	/* provided by AbstractSet
	@Override public <T> T[] toArray(T[] a) { }
	*/

	@Override public boolean add(E o) {
		if (o == null) {
			// BTreeMap doesn't support null keys at the time of coding, but this may change
			return !bkmap.containsKey(null)? bkmap.put(null, null) == null: false;
		}
		return bkmap.put(o, o) == null;
	}

	@Override public boolean remove(Object o) {
		if (o == null) {
			// BTreeMap doesn't support null keys at the time of coding, but this may change
			return bkmap.containsKey(null)? bkmap.remove(null) == null: false;
		}
		return bkmap.remove(o) == o;
	}

	/* provided by AbstractSet
	@Override public boolean containsAll(Collection<?> c) { }
	@Override public boolean addAll(Collection<? extends E> c) { }
	@Override public boolean retainAll(Collection<?> c) { }
	@Override public boolean removeAll(Collection<?> c) { }
	*/

	@Override public void clear() {
		bkmap.clear();
	}

	/* provided by AbstractSet
	@Override public boolean equals(Object o) { }
	@Override public int hashCode() { }
	*/

	/*========================================================================
	  public interface SortedSet
	 ========================================================================*/

	@Override public Comparator<? super E> comparator() {
		return bkmap.comparator();
	}

	@Override public E first() {
		return bkmap.firstKey();
	}

	@Override public E last() {
		return bkmap.lastKey();
	}

	@Override public SortedSet<E> headSet(E to) {
		return new SortedMapSet<E, SortedMap<E, E>>(bkmap.headMap(to));
	}

	@Override public SortedSet<E> tailSet(E fr) {
		return new SortedMapSet<E, SortedMap<E, E>>(bkmap.tailMap(fr));
	}

	@Override public SortedSet<E> subSet(E fr, E to) {
		return new SortedMapSet<E, SortedMap<E, E>>(bkmap.subMap(fr, to));
	}

}