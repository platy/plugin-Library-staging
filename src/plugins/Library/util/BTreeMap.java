/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package plugins.Library.util;

import java.util.Comparator;
import java.util.Iterator;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.AbstractSet;
import java.util.AbstractMap;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.HashMap;
import java.util.Stack;
import java.util.ArrayList;
import java.util.NoSuchElementException;

/**
** General purpose B-tree implementation. '''This class is not a general-use
** {@link SortedMap}'''; for that use {@link TreeMap}.
**
** From [http://en.wikipedia.org/wiki/B-tree wikipedia]: A B-tree is a tree
** data structure that keeps data sorted and allows searches, insertions, and
** deletions in logarithmic amortized time. Unlike self-balancing binary search
** trees, it is optimized for systems that read and write large blocks of data.
** It is most commonly used in databases and filesystems.
**
** In B-trees, internal (non-leaf) nodes can have a variable number of child
** nodes within some pre-defined range. When data is inserted or removed from a
** node, its number of child nodes changes. In order to maintain the
** pre-defined range, internal nodes may be joined or split. Because a range of
** child nodes is permitted, B-trees do not need re-balancing as frequently as
** other self-balancing search trees, but may waste some space, since nodes are
** not entirely full.
**
** A B-tree is kept balanced by requiring that all leaf nodes are at the same
** depth. This depth will increase slowly as elements are added to the tree,
** but an increase in the overall depth is infrequent, and results in all leaf
** nodes being one more node further away from the root.
**
** A B-tree of order m (the maximum number of children for each node) is a tree
** which satisfies the following properties:
**
** * Every node has between m-1 and m/2-1 entries, except for the root node,
**   which has between 1 and m/2-1 entries.
** * All leaves are the same distance from the root.
** * Every non-leaf node with k entries has k+1 subnodes arranged in sorted
**   order between the entries (for details, see {@link Node}).
**
** B-trees have substantial advantages over alternative implementations when
** node access times far exceed access times within nodes. This usually occurs
** when most nodes are in secondary storage such as hard drives. By maximizing
** the number of child nodes within each internal node, the height of the tree
** decreases, balancing occurs less often, and efficiency increases. Usually
** this value is set such that each node takes up a full disk block or an
** analogous size in secondary storage.
**
** When {@link #NODE_MIN} is {@link #BTreeMap(int) set to} 2, the resulting
** tree is structurally and algorithmically equivalent to a 2-3-4 tree, which
** is itself equivalent to a red-black tree, which is the implementation of
** {@link TreeMap} in the standard Java Class Library. This class has extra
** overheads due to the B-tree generalisation of the structures and algorithms
** involved, and is therefore only meant to be used to represent a B-tree
** stored in secondary storage, as the above paragraph describes.
**
** NOTE: at the moment there is a slight bug in this implementation that causes
** indeterminate behaviour when used with a comparator that admits {@code null}
** keys. This is due to {@code null} having special semantics, meaning "end of
** map", for the {@link Node#lkey} and {@link Node#rkey} fields. This is NOT
** an urgent priority to fix, but might be done in the future.
**
** * '''TODO ConcurrentModificationException for the entrySet iterator'''
** * '''TODO better distribution algorithm for putAll'''
**
** @author infinity0
** @see TreeMap
** @see Comparator
** @see Comparable
*/
public class BTreeMap<K, V> extends AbstractMap<K, V>
implements Map<K, V>, SortedMap<K, V>/*, NavigableMap<K, V>, Cloneable, Serializable*/ {

	/**
	** Minimum number of children of each node.
	*/
	final protected int NODE_MIN;

	/**
	** Maximum number of children of each node. Equal to {@code NODE_MIN * 2}.
	*/
	final protected int NODE_MAX;

	/**
	** Minimum number of entries in each node. Equal to {@code NODE_MIN - 1}.
	*/
	final protected int ENT_MIN;

	/**
	** Maximum number of entries in each node. Equal to {@code NODE_MAX - 1}.
	*/
	final protected int ENT_MAX;

	/**
	** Comparator for this {@link SortedMap}.
	*/
	final protected Comparator<? super K> comparator;

	/**
	** Root node of the tree. The only node that can have less than ENT_MIN
	** entries.
	*/
	protected Node root = newNode(true);

	/**
	** Number of entries currently in the map.
	*/
	protected int size = 0;;

	/**
	** Creates a new empty map, sorted according to the given comparator, and
	** with each non-root node having the given minimum number of subnodes.
	**
	** @param cmp The comparator for the tree, or {@code null} to use the keys'
	**            {@link Comparable natural} ordering.
	** @param node_min Minimum number of subnodes in each node
	*/
	public BTreeMap(Comparator<? super K> cmp, int node_min) {
		if (node_min < 2) {
			throw new IllegalArgumentException("The minimum number of subnodes must be set to at least 2");
		}
		comparator = cmp;
		NODE_MIN = node_min;
		NODE_MAX = node_min<<1;
		ENT_MIN = NODE_MIN - 1;
		ENT_MAX = NODE_MAX - 1;
	}

	/**
	** Creates a new empty map, sorted according to the keys' {@link Comparable
	** natural} ordering, and with each non-root node having the given minimum
	** number of subnodes.
	**
	** @param node_min Minimum number of subnodes in each node
	**/
	public BTreeMap(int node_min) {
		this(null, node_min);
	}

	/**
	** Creates a new empty map, sorted according to the keys' {@link Comparable
	** natural} ordering, and with each non-root node having at least 256
	** subnodes.
	*/
	public BTreeMap() {
		this(null, 0x100);
	}

	/**
	** A B-tree node. It has the following structure:
	**
	** * Every entry in a non-leaf node has two adjacent subnodes, holding
	**   entries which are all strictly greater and smaller than the entry.
	** * Conversely, every subnode has two adjacent entries, which are strictly
	**   greater and smaller than every entry in the subnode. Note: edge
	**   subnodes' "adjacent" keys are not part of the node's entries, but
	**   inherited from some ancestor parent.)
	**
	**     ^                        Node                        ^
	**     |                                                    |
	**     |    V1    V2    V3    V4    V5    V6    V7    V8    |
	**     |    |     |     |     |     |     |     |     |     |
	**    lkey  K1    K2    K3    K4    K5    K6    K7    K8  rkey
	**      \  /  \  /  \  /  \  /  \  /  \  /  \  /  \  /  \  /
	**      Node  Node  Node  Node  Node  Node  Node  Node  Node
	**
	**    | - {@link #entries} mappings
	**    \ - {@link #rnodes} mappings (and the subnode's {@link #lkey})
	**    / - {@link #lnodes} mappings (and the subnode's {@link #rkey})
	**
	** @author infinity0
	*/
	protected class Node {

		/**
		** Whether this node is a leaf.
		*/
		final boolean isLeaf;

		/**
		** Map of entries in this node.
		*/
		final SortedMap<K, V> entries;

		/**
		** Map of entries to their immediate smaller nodes. The greatest node
		** is mapped to by {@link #rkey}.
		*/
		final Map<K, Node> lnodes;

		/**
		** Map of entries to their immediate greater nodes. The smallest node
		** is mapped to by {@link #lkey}.
		*/
		final Map<K, Node> rnodes;

		/**
		** Greatest key smaller than all keys in this node and subnodes. This
		** is {@code null} when the node is at the minimum-key end of the tree.
		*/
		K lkey = null;

		/**
		** Smallest key greater than all keys in this node and subnodes. This
		** is {@code null} when the node is at the maximum-key end of the tree.
		*/
		K rkey = null;

		/**
		** Creates a new node for the BTree, with a custom map to store the
		** entries.
		**
		** Note: it is assumed that the input map is empty; it is up to the
		** calling code to ensure that this holds, or if not, then at least put
		** the node into a consistent state before releasing it for use.
		**
		** @param leaf Whether to create a leaf node
		** @param map A {@link SortedMap} to use to store the entries
		*/
		Node(boolean leaf, SortedMap<K, V> map) {
			isLeaf = leaf;
			entries = map;
			if (leaf) {
				// we don't use sentinel Nil elements because that wastes memory due to
				// having to maintain dummy rnodes and lnodes maps.
				lnodes = null;
				rnodes = null;
			} else {
				lnodes = new HashMap<K, Node>(NODE_MAX);
				rnodes = new HashMap<K, Node>(NODE_MAX);
			}
		}

		/**
		** Creates a new node for the BTree
		**
		** @param leaf Whether to create a leaf node
		*/
		Node(boolean leaf) {
			this(leaf, new TreeMap<K, V>(comparator));
		}

		/**
		** Creates a new non-leaf node for the BTree.
		*/
		Node() {
			this(false);
		}

		/**
		** Number of entries the node contains. When descending through the
		** tree, either this method or {@link #isLeaf()} will be called at
		** least once for each node visited, before any merge / split / rotate
		** operations upon it.
		*/
		int size() {
			return entries.size();
		}

		/**
		** Whether the node is a leaf.  When descending through the B-tree,
		** either this method or {@link #size()} will be called at least once
		** for each node visited, before any merge / split / rotate operations
		** upon it.
		*/
		boolean isLeaf() {
			return isLeaf;
		}

		/**
		** Returns the greatest subnode smaller than the given node.
		**
		** Note: This method assumes that the input is an actual subnode of
		** this node; it is up to the calling code to ensure that this holds.
		**
		** @param node The node to lookup its neighbour for
		** @return The neighbour node, or null if the input node is at the edge
		** @throws NullPointerException if this is a leaf node
		*/
		Node nodeL(Node node) {
			assert(lnodes.get(node.rkey) == node
			    && rnodes.get(node.lkey) == node);

			return (compare2(lkey, node.lkey) == 0)? null: lnodes.get(node.lkey);
		}

		/**
		** Returns the smallest subnode greater than the given node.
		**
		** Note: This method assumes that the input is an actual subnode of
		** this node; it is up to the calling code to ensure that this holds.
		**
		** @param node The node to lookup its neighbour for
		** @return The neighbour node, or null if the input node is at the edge
		** @throws NullPointerException if this is a leaf node
		*/
		Node nodeR(Node node) {
			assert(lnodes.get(node.rkey) == node
			    && rnodes.get(node.lkey) == node);

			return (compare2(node.rkey, rkey) == 0)? null: rnodes.get(node.rkey);
		}

		/**
		** Returns the subnode a that given key belongs in. The key must not be
		** local to the node.
		**
		** Note: This method assumes that the input key is strictly between
		** {@link #lkey} and {@link #rkey}; it is up to the calling code to
		** ensure that this holds.
		**
		** @param key The key to select the node for
		** @return The subnode for the key, or null if the key is local to the
		**         node
		** @throws NullPointerException if this is a leaf node; or if the key
		**         is {@code null} and the entries map uses natural order, or
		**         its comparator does not tolerate {@code null} keys
		*/
		Node selectNode(K key) {
			assert(compare2(lkey, key) < 0 && compare2(key, rkey) < 0);

			SortedMap<K, V> tailmap = entries.tailMap(key);
			if (tailmap.isEmpty()) {
				return lnodes.get(rkey);
			} else {
				K next = tailmap.firstKey();
				return (compare(key, next) == 0)? null: lnodes.get(next);
			}
		}

		public String toTreeString(String istr) {
			String nistr = istr + "\t";
			StringBuilder s = new StringBuilder();
			s.append(istr).append('(').append(lkey).append(')').append('\n');
			if (isLeaf) {
				for (Map.Entry<K, V> en: entries.entrySet()) {
					s.append(istr).append(en.getKey()).append(" : ").append(en.getValue()).append('\n');
				}
			} else {
				s.append(rnodes.get(lkey).toTreeString(nistr));
				for (Map.Entry<K, V> en: entries.entrySet()) {
					s.append(istr).append(en.getKey()).append(" : ").append(en.getValue()).append('\n');
					s.append(rnodes.get(en.getKey()).toTreeString(nistr));
				}
			}
			s.append(istr).append('(').append(rkey).append(')').append('\n');
			return s.toString();
		}

		public String getName() {
			return "BTreeMap node " + getShortName();
		}

		public String getShortName() {
			return (lkey == null? "*": lkey) + "-" + (rkey == null? "*": rkey);
		}

	}

	public String toTreeString() {
		return root.toTreeString("");
	}

	/**
	** Compares two keys using the comparator for this tree, or the keys'
	** {@link Comparable natural} ordering if no comparator was given.
	**
	** @throws ClassCastException if the keys cannot be compared by the given
	**         comparator, or if they cannot be compared naturally (ie. they
	**         don't implement {@link Comparable})
	*/
	public int compare(K key1, K key2) {
		return (comparator != null)? comparator.compare(key1, key2): ((Comparable<K>)key1).compareTo(key2);
	}

	/**
	** A compare method with extend semantics, that takes into account the
	** special meaning of {@code null} for the {@link Node#lkey lkey} and
	** {@link Node#rkey rkey} fields.
	**
	** * If both keys are {@code null}, returns 0. Otherwise:
	** * If {@code key1} is {@code null}, it is treated as an {@link Node#lkey
	**   lkey} value, ie. smaller than all other values (method returns -1).
	** * If {@code key2} is {@code null}, it is treated as an {@link Node#rkey
	**   rkey} value, ie. greater than all other values (method returns -1).
	** * Otherwise, neither keys are {@code null}, and are compared normally.
	*/
	protected int compare2(K key1, K key2) {
		return (key1 == null)? ((key2 == null)? 0: -1):
		                       ((key2 == null)? -1: compare(key1, key2));
	}

	/**
	** Simulates an assertion.
	**
	** @param b The test condition
	** @throws IllegalStateException if the test condition is false
	*/
	final void verify(boolean b) {
		if (!b) { throw new IllegalStateException("Verification failed"); }
	}

	/**
	** Package-private debugging method. This one checks node integrity:
	**
	** * lkey < firstkey, lastkey < rkey
	**
	** For non-leaf nodes:
	**
	** * for each pair of adjacent keys (including two null keys either side of
	**   the list of keys), it is possible to traverse between the keys, and
	**   the single node between them, using the lnodes, rnodes maps and
	**   the lkey, rkey pointers.
	** * lnodes' and rnodes' size is 1 greater than that of entries. Ie. there
	**   is nothing else in the node beyond what we visited in the previous
	**   step.
	**
	** It also checks the BTree node constraints:
	**
	** * The node has at most ENT_MAX entries.
	** * The node has at least ENT_MIN entries, except the root.
	** * The root has at least 1 entry.
	**
	** @throws IllegalStateException if the constraints are not satisfied
	*/
	void verifyNodeIntegrity(Node node) {
		if (node.entries.isEmpty()) {
			verify(node == root && node.lkey == null && node.rkey == null);
			verify(node.isLeaf() && node.rnodes == null && node.lnodes == null);
			return;
		}

		verify(compare2(node.lkey, node.entries.firstKey()) < 0);
		verify(compare2(node.entries.lastKey(), node.rkey) < 0);

		if (!node.isLeaf()) {
			Iterator<K> it = node.entries.keySet().iterator();
			verify(it.hasNext());

			K prev = node.lkey;
			K curr = it.next();
			while (curr != node.rkey || prev != node.rkey) {
				Node node1 = node.rnodes.get(prev);
				verify(node1 != null && compare2(curr, node1.rkey) == 0);
				Node node2 = node.lnodes.get(curr);
				verify(node1 == node2 && compare2(node2.lkey, prev) == 0);
				prev = curr;
				curr = it.hasNext()? it.next(): node.rkey;
			}

			verify(node.size() + 1 == node.rnodes.size());
			verify(node.size() + 1 == node.lnodes.size());
		}

		verify(node.size() <= ENT_MAX);
		// don't test node == root since it might be a newly-created detached node
		verify(node.lkey == null && node.rkey == null && node.size() >= 1
		                    || node.size() >= ENT_MIN);
	}

	/**
	** Package-private debugging method. This one checks BTree constraints for
	** the subtree rooted at a given node:
	**
	** * All nodes follow the node constraints, listed above.
	** * All leaves appear in the same level
	**
	** @return depth of all leaves
	** @throws IllegalStateException if the constraints are not satisfied
	*/
	int verifyTreeIntegrity(Node node) {
		verifyNodeIntegrity(node);
		if (node.isLeaf()) {
			return 0;
		} else {
			// breath-first search would take up too much memory for a broad BTree
			int depth = -1;
			for (Node n: node.lnodes.values()) {
				int d = verifyTreeIntegrity(n);
				if (depth < 0) { depth = d; }
				verify(d == depth);
			}
			return depth + 1;
		}
	}

	/**
	** Package-private debugging method. This one checks BTree constraints for
	** the entire tree.
	**
	** @throws IllegalStateException if the constraints are not satisfied
	*/
	void verifyTreeIntegrity() {
		verifyTreeIntegrity(root);
	}

	/**
	** Creates a new node.
	*/
	protected Node newNode(boolean l) {
		return new Node(l);
	}

	/**
	** Split a maximal child node into two minimal nodes, using the median key
	** as the separator between these new nodes in the parent. If the parent is
	** {@code null}, creates a new {@link Node} and points {@link #root} to it.
	**
	** Note: This method assumes that the child is an actual subnode of the
	** parent, and that it is actually full. It is up to the calling code to
	** ensure that this holds.
	**
	** The exact implementation of this may change from time to time, so the
	** calling code should regard the input subnode as effectively destroyed,
	** and re-get the desired result subnode from calling the appropriate
	** get methods on the parent node.
	**
	** @param parent The node to (re)attach the split subnodes to.
	** @param child The subnode to split
	*/
	private K split(Node parent, Node child) {
		assert(child.size() == ENT_MAX);
		assert(parent == null? (child.lkey == null && child.rkey == null):
		                       (parent.size() < ENT_MAX
		                     && !parent.isLeaf()
		                     && parent.rnodes.get(child.lkey) == child
		                     && parent.lnodes.get(child.rkey) == child));

		if (parent == null) {
			assert(child.lkey == null && child.rkey == null);
			parent = root = newNode(false);
			parent.lnodes.put(null, child);
			parent.rnodes.put(null, child);
		}
		Node lnode = newNode(child.isLeaf());
		K mkey;

		if (child.isLeaf()) {
			// this is just the same as the code in the else block, but with leaf
			// references to rnodes and lnodes removed (since they are null)
			Iterator<Map.Entry<K, V>> it = child.entries.entrySet().iterator();
			for (int i=0; i<ENT_MIN; ++i) {
				Map.Entry<K, V> entry = it.next(); it.remove();
				K key = entry.getKey();

				lnode.entries.put(key, entry.getValue());
			}
			Map.Entry<K, V> median = it.next(); it.remove();
			mkey = median.getKey();

			lnode.lkey = child.lkey;
			lnode.rkey = child.lkey = mkey;

			parent.rnodes.put(lnode.lkey, lnode);
			parent.lnodes.put(child.rkey, child);
			parent.entries.put(mkey, median.getValue());
			parent.rnodes.put(mkey, child);
			parent.lnodes.put(mkey, lnode);

		} else {
			lnode.rnodes.put(child.lkey, child.rnodes.remove(child.lkey));
			Iterator<Map.Entry<K, V>> it = child.entries.entrySet().iterator();
			for (int i=0; i<ENT_MIN; ++i) {
				Map.Entry<K, V> entry = it.next(); it.remove();
				K key = entry.getKey();

				lnode.entries.put(key, entry.getValue());
				lnode.lnodes.put(key, child.lnodes.remove(key));
				lnode.rnodes.put(key, child.rnodes.remove(key));
			}
			Map.Entry<K, V> median = it.next(); it.remove();
			mkey = median.getKey();
			lnode.lnodes.put(mkey, child.lnodes.remove(mkey));

			lnode.lkey = child.lkey;
			lnode.rkey = child.lkey = mkey;

			parent.rnodes.put(lnode.lkey, lnode);
			parent.lnodes.put(child.rkey, child);
			parent.entries.put(mkey, median.getValue());
			parent.rnodes.put(mkey, child);
			parent.lnodes.put(mkey, lnode);
		}

		assert(parent.rnodes.get(mkey) == child);
		assert(parent.lnodes.get(mkey) == lnode);
		return mkey;
	}

	/**
	** Merge two minimal child nodes into a maximal node, using the key that
	** separates them in the parent as the key that joins the halfnodes in the
	** merged node. If the parent is the {@link #root} and the merge makes it
	** empty, point {@code root} to the new merged node.
	**
	** Note: This method assumes that both childs are actual subnodes of the
	** parent, that they are adjacent in the parent, and that they are actually
	** minimally full. It is up to the calling code to ensure that this holds.
	**
	** The exact implementation of this may change from time to time, so the
	** calling code should regard both input subnodes as effectively destroyed,
	** and re-get the desired result subnode from calling the appropriate
	** get methods on the parent node.
	**
	** @param parent The node to (re)attach the merge node to.
	** @param lnode The smaller subnode to merge
	** @param rnode The greater subnode to merge
	*/
	private K merge(Node parent, Node lnode, Node rnode) {
		assert(compare(lnode.rkey, rnode.lkey) == 0); // not compare2 since can't be at edges
		assert(lnode.isLeaf() && rnode.isLeaf() || !lnode.isLeaf() && !rnode.isLeaf());
		assert(lnode.size() == ENT_MIN);
		assert(rnode.size() == ENT_MIN);
		assert(parent == root && parent.size() > 0
		                      || parent.size() > ENT_MIN);
		assert(!parent.isLeaf() && parent.rnodes.get(lnode.rkey) == rnode
		                      && parent.lnodes.get(rnode.lkey) == lnode);

		K mkey = rnode.lkey; // same as lnode.rkey;

		if (rnode.isLeaf()) {
			// this is just the same as the code in the else block, but with leaf
			// references to rnodes and lnodes removed (since they are null)
			rnode.entries.putAll(lnode.entries);

			rnode.entries.put(mkey, parent.entries.remove(mkey));
			rnode.lkey = lnode.lkey;

			parent.rnodes.remove(mkey);
			parent.lnodes.remove(mkey);
			parent.rnodes.put(lnode.lkey, rnode);

		} else {
			rnode.entries.putAll(lnode.entries);
			rnode.lnodes.putAll(lnode.lnodes);
			rnode.rnodes.putAll(lnode.rnodes);

			rnode.entries.put(mkey, parent.entries.remove(mkey));
			rnode.lnodes.put(mkey, lnode.lnodes.get(mkey));
			rnode.rnodes.put(lnode.lkey, lnode.rnodes.get(lnode.lkey));
			rnode.lkey = lnode.lkey;

			parent.rnodes.remove(mkey);
			parent.lnodes.remove(mkey);
			parent.rnodes.put(lnode.lkey, rnode);

		}

		if (parent == root && parent.entries.isEmpty()) {
			assert(parent.lkey == null && parent.rkey == null
			    && rnode.lkey == null && rnode.rkey == null);
			root = rnode;
		}

		assert(parent.rnodes.get(rnode.lkey) == rnode);
		assert(parent.lnodes.get(rnode.rkey) == rnode);
		return mkey;
	}

	/**
	** Performs a rotate operation towards the smaller node. A rotate operation
	** is where:
	**
	** * an entry M from the parent node is moved to a subnode
	** * an entry S from an adjacent subnode is moved to fill gap left by M
	** * a subnode N associated with S is cut from M and attached to S
	**
	** with the operands chosen appropriately to maintain tree constraints.
	**
	** Here, M is the key that separates {@code lnode} and {@code rnode}, S is
	** the smallest key of {@code rnode}, and N its smallest subnode.
	**
	** Note: This method assumes that both childs are actual subnodes of the
	** parent, that they are adjacent in the parent, and that the child losing
	** an entry has more than {@link #ENT_MIN} entries. It is up to the calling
	** code to ensure that this holds.
	**
	** @param parent The parent node of the children.
	** @param lnode The smaller subnode, which accepts an entry from the parent
	** @param rnode The greater subnode, which loses an entry to the parent
	*/
	private K rotateL(Node parent, Node lnode, Node rnode) {
		assert(compare(lnode.rkey, rnode.lkey) == 0); // not compare2 since can't be at edges
		assert(lnode.isLeaf() && rnode.isLeaf() || !lnode.isLeaf() && !rnode.isLeaf());
		assert(rnode.size() >= lnode.size());
		assert(rnode.size() > ENT_MIN);
		assert(!parent.isLeaf() && parent.rnodes.get(lnode.rkey) == rnode
		                      && parent.lnodes.get(rnode.lkey) == lnode);

		K mkey = rnode.lkey;
		K skey = rnode.entries.firstKey();

		lnode.entries.put(mkey, parent.entries.remove(mkey));
		parent.entries.put(skey, rnode.entries.remove(skey));
		parent.rnodes.put(skey, parent.rnodes.remove(mkey));
		parent.lnodes.put(skey, parent.lnodes.remove(mkey));

		lnode.rkey = rnode.lkey = skey;

		if (!lnode.isLeaf()) {
			lnode.rnodes.put(mkey, rnode.rnodes.remove(mkey));
			lnode.lnodes.put(skey, rnode.lnodes.remove(skey));
		}

		assert(parent.rnodes.get(skey) == rnode);
		assert(parent.lnodes.get(skey) == lnode);
		return mkey;
	}

	/**
	** Performs a rotate operation towards the greater node. A rotate operation
	** is where:
	**
	** * an entry M from the parent node is moved to a subnode
	** * an entry S from an adjacent subnode is moved to fill gap left by M
	** * a subnode N associated with S is cut from M and attached to S
	**
	** with the operands chosen appropriately to maintain tree constraints.
	**
	** Here, M is the key that separates {@code lnode} and {@code rnode}, S is
	** the greatest key of {@code rnode}, and N its greatest subnode.
	**
	** Note: This method assumes that both childs are actual subnodes of the
	** parent, that they are adjacent in the parent, and that the child losing
	** an entry has more than {@link #ENT_MIN} entries. It is up to the calling
	** code to ensure that this holds.
	**
	** @param parent The parent node of the children.
	** @param lnode The smaller subnode, which loses an entry to the parent
	** @param rnode The greater subnode, which accepts an entry from the parent
	*/
	private K rotateR(Node parent, Node lnode, Node rnode) {
		assert(compare(lnode.rkey, rnode.lkey) == 0); // not compare2 since can't be at edges
		assert(lnode.isLeaf() && rnode.isLeaf() || !lnode.isLeaf() && !rnode.isLeaf());
		assert(lnode.size() >= rnode.size());
		assert(lnode.size() > ENT_MIN);
		assert(!parent.isLeaf() && parent.rnodes.get(lnode.rkey) == rnode
		                      && parent.lnodes.get(rnode.lkey) == lnode);

		K mkey = lnode.rkey;
		K skey = lnode.entries.lastKey();

		rnode.entries.put(mkey, parent.entries.remove(mkey));
		parent.entries.put(skey, lnode.entries.remove(skey));
		parent.lnodes.put(skey, parent.lnodes.remove(mkey));
		parent.rnodes.put(skey, parent.rnodes.remove(mkey));

		lnode.rkey = rnode.lkey = skey;

		if (!rnode.isLeaf()) {
			rnode.lnodes.put(mkey, lnode.lnodes.remove(mkey));
			rnode.rnodes.put(skey, lnode.rnodes.remove(skey));
		}

		assert(parent.rnodes.get(skey) == rnode);
		assert(parent.lnodes.get(skey) == lnode);
		return mkey;
	}

	/*========================================================================
	  public interface Map
	 ========================================================================*/

	@Override public int size() {
		return size;
	}

	@Override public boolean isEmpty() {
		return size == 0;
	}

	@Override public void clear() {
		root = newNode(true);
		size = 0;
	}

	/**
	** {@inheritDoc}
	**
	** This implementation just descends the tree, returning {@code true} if it
	** finds the given key.
	**
	** @throws ClassCastException key cannot be compared with the keys
	**         currently in the map
	** @throws NullPointerException key is {@code null} and this map uses
	**         natural order, or its comparator does not tolerate {@code null}
	**         keys
	*/
	@Override public boolean containsKey(Object k) {
		K key = (K) k;
		Node node = root;

		for (;;) {
			if (node.isLeaf()) {
				return node.entries.containsKey(key);
			}

			Node nextnode = node.selectNode(key);
			if (nextnode == null) {
				return true;
			}

			node = nextnode;
		}
	}

	/* provided by AbstractMap
	@Override public boolean containsValue(Object key) {
		throw new UnsupportedOperationException("not implemented");
	}*/

	/**
	** {@inheritDoc}
	**
	** This implementation just descends the tree, returning the value for the
	** given key if it can be found.
	**
	** @throws ClassCastException key cannot be compared with the keys
	**         currently in the map
	** @throws NullPointerException key is {@code null} and this map uses
	**         natural order, or its comparator does not tolerate {@code null}
	**         keys
	*/
	@Override public V get(Object k) {
		K key = (K) k;
		Node node = root;

		for (;;) {
			if (node.isLeaf()) {
				return node.entries.get(key);
			}

			Node nextnode = node.selectNode(key);
			if (nextnode == null) {
				return node.entries.get(key);
			}

			node = nextnode;
		}
	}

	/**
	** {@inheritDoc}
	**
	** This implementation uses the BTree single-pass insertion algorithm. We
	** descend the tree, restructuring it as needed so that we can insert an
	** entry into a leaf.
	**
	** Start at the root node. At each stage of the algorithm, we restructure
	** the tree so that the next node we reach has less than {@link #ENT_MAX}
	** entries, and the insertion can occur without breaking constraints.
	**
	** (*) If the number of entries is {@link #ENT_MAX}, then perform {@link
	** #split(Node, Node) split} on the node. Both the newly created nodes now
	** have less than {@link #ENT_MAX} entries; pick the appropriate halfnode
	** to for the rest of the operation, depending on the original input key.
	**
	** If the node is a leaf, insert the value and stop. Otherwise, if the key
	** is not already in the node, select the appropriate subnode and repeat
	** from (*).
	**
	** Otherwise, replace the value and stop.
	**
	** @throws ClassCastException key cannot be compared with the keys
	**         currently in the map
	** @throws NullPointerException key is {@code null} and this map uses
	**         natural order, or its comparator does not tolerate {@code null}
	**         keys
	*/
	@Override public V put(K key, V value) {
		Node node = root, parent = null;

		for (;;) {
			if (node.size() == ENT_MAX) {
				K median = split(parent, node);
				if (parent == null) { parent = root; }

				node = parent.selectNode(key);
				if (node == null) { return parent.entries.put(key, value); }
			}
			assert(node.size() < ENT_MAX);

			if (node.isLeaf()) {
				int sz = node.size();
				V v = node.entries.put(key, value);
				// update size cache
				if (node.size() != sz) { ++size; }
				return v;
			}

			Node nextnode = node.selectNode(key);
			if (nextnode == null) { // key is already in the node
				return node.entries.put(key, value);
			}

			parent = node;
			node = nextnode;
		}
	}

	/**
	** {@inheritDoc}
	**
	** This implementation uses the BTree single-pass deletion algorithm. We
	** descend the tree, restructuring it as needed so that we can delete the
	** entry from a leaf.
	**
	** Start at the root node. At each stage of the algorithm, we restructure
	** the tree so that the next node we reach has more than {@link #ENT_MIN}
	** entries, and the deletion can occur withot breaking constraints.
	**
	** (*) If the node is not the root, and if the number of entries is equal
	** to {@link #ENT_MIN}, select its two siblings (L and R). Perform one of
	** the following operations (ties being broken arbitrarily):
	**
	** * {@link #merge(Node, Node, Node) merge} with X, if X also has {@link
	**   #ENT_MIN} entries
	** * {@link #rotateL(Node, Node, Node) rotateL} with R, if the R subnode
	**   has more entries than L (or equal)
	** * {@link #rotateR(Node, Node, Node) rotateR} with L, if the L subnode
	**   has more entries than R (or equal)
	**
	** The selected node now has more than {@link #ENT_MIN} entries.
	**
	** If the node is a leaf, remove the value and stop. Otherwise, if the key
	** is not already in the node, select the appropriate subnode and repeat
	** from (*).
	**
	** Otherwise, select the two subnodes (L and R) that this key separates.
	** Perform one of the following operations (ties being broken arbitrarily):
	**
	** * {@link #merge(Node, Node, Node) merge}, if both subnodes have {@link
	**   #ENT_MIN} entries.
	** * {@link #rotateL(Node, Node, Node) rotateL}, if the R subnode has more
	**   entries than L (or equal)
	** * {@link #rotateR(Node, Node, Node) rotateR}, if the L subnode has more
	**   entries than R (or equal)
	**
	** The node that the key ended up in now has more than {@link #ENT_MIN}
	** entries (and will be selected for the next stage).
	**
	** @throws ClassCastException key cannot be compared with the keys
	**         currently in the map
	** @throws NullPointerException key is {@code null} and this map uses
	**         natural order, or its comparator does not tolerate {@code null}
	**         keys
	*/
	@Override public V remove(Object k) {
		K key = (K) k;
		Node node = root, parent = null;

		for (;;) {
			if (node != root && node.size() == ENT_MIN) {
				Node lnode = parent.nodeL(node), rnode = parent.nodeR(node);
				int L = (lnode == null)? -1: lnode.size();
				int R = (rnode == null)? -1: rnode.size();

				K kk = // in java, ?: must be used in a statement :|
				// lnode doesn't exist
				(L < 0)? ((R == ENT_MIN)? merge(parent, node, rnode):
				                          rotateL(parent, node, rnode)):
				// rnode doesn't exist
				(R < 0)? ((L == ENT_MIN)? merge(parent, lnode, node):
				                          rotateR(parent, lnode, node)):
				// pick the node with more entries
				(R > L)? rotateL(parent, node, rnode):
				(L > R)? rotateR(parent, lnode, node):
				// otherwise pick one at "random"
				(size&1) == 1? (R == ENT_MIN? merge(parent, node, rnode):
				                              rotateL(parent, node, rnode)):
				               (L == ENT_MIN? merge(parent, lnode, node):
				                              rotateR(parent, lnode, node));
				node = parent.selectNode(key);
				assert(node != null);
			}
			assert(node == root || node.size() >= ENT_MIN);

			if (node.isLeaf()) { // leaf node
				int sz = node.size();
				V v = node.entries.remove(key);
				// update size cache
				if (node.size() != sz) { --size; }
				return v;
			}

			Node nextnode = node.selectNode(key);
			if (nextnode == null) { // key is already in the node
				Node lnode = node.lnodes.get(key), rnode = node.rnodes.get(key);
				int L = lnode.size(), R = rnode.size();

				K kk =
				// both lnode and rnode must exist, so
				// pick the one with more entries
				(R > L)? rotateL(node, lnode, rnode):
				(L > R)? rotateR(node, lnode, rnode):
				// otherwise pick one at "random"
				(size&1) == 1? (R == ENT_MIN? merge(node, lnode, rnode):
				                              rotateL(node, lnode, rnode)):
				               (L == ENT_MIN? merge(node, lnode, rnode):
				                              rotateR(node, lnode, rnode));
				nextnode = node.selectNode(key);
				assert(nextnode != null);
			}

			parent = node;
			node = nextnode;
		}
	}

	/**
	** {@inheritDoc}
	**
	** This implementation iterates over the given map's {@code entrySet},
	** adding each mapping in turn, except for when {@code this} map is empty,
	** and the input map is a non-empty {@link SortedMap}. In this case, it
	** uses the BTree bulk-loading algorithm:
	**
	** * distribute all the entries of the map across the least number of nodes
	**   possible, excluding the entries that will act as separators between
	**   these nodes
	** * repeat for the separator entries, and use them to join the nodes from
	**   the previous level appropriately to form a node at this level
	** * repeat until there are no more entries on a level, at which point use
	**   the (single) node from the previous level as the root
	**
	** (The optimisation is also used if the input map is {@code this}.)
	**
	** @param t mappings to be stored in this map
	*/
	@Override public void putAll(Map<? extends K, ? extends V> t) {
		// t == this to support restructure()
		if (t.isEmpty()) {
			return;
		} else if (t == this || isEmpty() && t instanceof SortedMap) {
			SortedMap<K, V> map = (SortedMap<K, V>)t, nextmap;
			Map<K, Node> lnodes = null, nextlnodes;

			if (!(comparator == null && map.comparator() == null || comparator.equals(map.comparator()))) {
				super.putAll(map);
				return;
			}

			while (map.size() > 0) {
				// find the smallest k: k * ENT_MAX + (k-1) >= map.size()
				// ie. k * NODE_MAX >= map.size() + 1
				// this is the number of nodes at this level
				int k = (map.size() + NODE_MAX) / NODE_MAX;

				// number of entries in each node (or one less than this)
				int n = map.size() / k;
				// how many nodes will have n entries (as opposed to n-1)
				int leftovers = map.size() % k;

				// TODO find a better algorithm that distributes the weights
				// more evenly that just "heaviest first"
				assert(n >= ENT_MIN);

				nextlnodes = new HashMap<K, Node>(k<<1);
				nextmap = new TreeMap<K, V>();

				Iterator<Map.Entry<K, V>> it = map.entrySet().iterator();
				K prevkey = null;
				int i=0;
				for (; i<=leftovers; ++i) {
					// put n entries into a new leaf
					Map.Entry<K, V> en = makeNode(it, n, prevkey, lnodes, nextlnodes);
					if (en != null) { nextmap.put(prevkey = en.getKey(), en.getValue()); }
				}
				--n;
				for (; i<k; ++i) {
					Map.Entry<K, V> en = makeNode(it, n, prevkey, lnodes, nextlnodes);
					if (en != null) { nextmap.put(prevkey = en.getKey(), en.getValue()); }
				}

				lnodes = nextlnodes;
				map = nextmap;
			}

			assert(lnodes.size() == 1);
			root = lnodes.get(null);
			size = map.size();

		} else {
			super.putAll(t);
		}
	}

	/**
	** Helper method for the bulk-loading algorithm.
	**
	** @param it Iterator over the entries at the current level
	** @param n Number of entries to add to the map
	** @param prevkey Last key encountered before calling this method
	** @param lnodes Complete map of keys to their lnodes at this level
	** @param nextlnodes In-construction lnodes map for the next level
	*/
	private Map.Entry<K, V> makeNode(Iterator<Map.Entry<K, V>> it, int n, K prevkey, Map<K, Node> lnodes, Map<K, Node> nextlnodes) {
		Node node;
		if (lnodes == null) { // create leaf nodes
			node = newNode(true);
			node.lkey = prevkey;
			for (int i=0; i<n; ++i) {
				Map.Entry<K, V> en = it.next();
				K key = en.getKey();
				node.entries.put(key, en.getValue());
				prevkey = key;
			}
		} else {
			node = newNode(false);
			node.lkey = prevkey;
			for (int i=0; i<n; ++i) {
				Map.Entry<K, V> en = it.next();
				K key = en.getKey();
				node.entries.put(key, en.getValue());
				Node subnode = lnodes.get(key);
				node.rnodes.put(prevkey, subnode);
				node.lnodes.put(key, subnode);
				prevkey = key;
			}
		}

		Map.Entry<K, V> next;
		K key;
		if (it.hasNext()) {
			next = it.next();
			key = next.getKey();
		} else {
			next = null;
			key = null;
		}

		if (lnodes != null) {
			Node subnode = lnodes.get(key);
			node.rnodes.put(prevkey, subnode);
			node.lnodes.put(key, subnode);
		}
		node.rkey = key;
		nextlnodes.put(key, node);

		return next;
	}

	/**
	** Restructures the tree, distributing the entries evenly between the leaf
	** nodes. This method merely calls {@link #putAll(Map)} with {@code this}.
	*/
	public void restructure() {
		putAll(this);
	}

	// TODO maybe make this a WeakReference?
	private Set<Map.Entry<K, V>> entrySet = null;
	@Override public Set<Map.Entry<K, V>> entrySet() {
		if (entrySet == null) {
			entrySet = new AbstractSet<Map.Entry<K, V>>() {

				@Override public int size() { return BTreeMap.this.size(); }

				@Override public Iterator<Map.Entry<K, V>> iterator() {
					// URGENT - this does NOT yet throw ConcurrentModificationException
					// use a modCount counter
					return new Iterator<Map.Entry<K, V>>() {

						Stack<Node> nodestack = new Stack<Node>();
						Stack<Iterator<Map.Entry<K, V>>> itstack = new Stack<Iterator<Map.Entry<K, V>>>();

						Node cnode = BTreeMap.this.root;
						Iterator<Map.Entry<K, V>> centit = cnode.entries.entrySet().iterator();

						K lastkey = null;

						@Override public boolean hasNext() {
							for (Iterator<Map.Entry<K, V>> it: itstack) {
								if (it.hasNext()) { return true; }
							}
							if (centit.hasNext()) { return true; }
							return false;
						}

						@Override public Map.Entry<K, V> next() {
							if (cnode.isLeaf()) {
								while (!centit.hasNext()) {
									if (nodestack.empty()) {
										assert(itstack.empty());
										throw new NoSuchElementException();
									}
									cnode = nodestack.pop();
									centit = itstack.pop();
								}
							} else {
								while (!cnode.isLeaf()) {
									nodestack.push(cnode);
									itstack.push(centit);
									// lastkey initialised to null, so this will get the right node even at the
									// smaller edge of the map
									cnode = cnode.rnodes.get(lastkey);
									centit = cnode.entries.entrySet().iterator();
								}
							}
							Map.Entry<K, V> next = centit.next(); lastkey = next.getKey();
							return next;
						}

						@Override public void remove() {
							BTreeMap.this.remove(lastkey);
							// we need to find our position in the tree again, since there may have
							// been structural modifications to it.

							// OPTIMISE have a "structual modifications counter" that
							// split, merge, rotateL, rotateR increment, and do this only if it
							// changes
							nodestack.clear();
							itstack.clear();
							cnode = BTreeMap.this.root;
							centit = cnode.entries.entrySet().iterator();

							K stopkey = null;
							while(!cnode.isLeaf()) {
								stopkey = findstopkey(stopkey);
								nodestack.push(cnode);
								itstack.push(centit);
								// stopkey initialised to null, so this will get the right node even at the
								// smaller edge of the map
								cnode = cnode.rnodes.get(stopkey);
								centit = cnode.entries.entrySet().iterator();
							}

							lastkey = findstopkey(stopkey);
						}

						private K findstopkey(K stopkey) {
							SortedMap<K, V> headmap = cnode.entries.headMap(lastkey);
							if (!headmap.isEmpty()) {
								stopkey = headmap.lastKey();
								while (compare(centit.next().getKey(), stopkey) < 0);
							}
							return stopkey;
						}

					};
				}

				@Override public void clear() {
					BTreeMap.this.clear();
				}

				@Override public boolean contains(Object o) {
					if (!(o instanceof Map.Entry)) { return false; }
					Map.Entry e = (Map.Entry)o;
					Object value = BTreeMap.this.get(e.getKey());
					return value != null && value.equals(e.getValue());
				}

				@Override public boolean remove(Object o) {
					if (contains(o)) {
						Map.Entry e = (Map.Entry)o;
						BTreeMap.this.remove(e.getKey());
						return true;
					}
					return false;
				}

			};
		}
		return entrySet;
	}

	/* provided by AbstractMap
	@Override public Set<K> keySet() {
		throw new UnsupportedOperationException("not implemented");
	}*/

	/* provided by AbstractMap
	@Override public Collection<V> values() {
		throw new UnsupportedOperationException("not implemented");
	}*/

	/*========================================================================
	  public interface Map
	 ========================================================================*/

	@Override public Comparator<? super K> comparator() {
		return comparator;
	}

	@Override public K firstKey() {
		Node node = root;
		for (;;) {
			K fkey = node.entries.firstKey();
			if (node.isLeaf()) { return fkey; }
			node = node.lnodes.get(fkey);
		}
	}

	@Override public K lastKey() {
		Node node = root;
		for (;;) {
			K lkey = node.entries.lastKey();
			if (node.isLeaf()) { return lkey; }
			node = node.rnodes.get(lkey);
		}
	}

	@Override public SortedMap<K, V> headMap(K rkey) {
		throw new UnsupportedOperationException("not implemented");
	}

	@Override public SortedMap<K, V> tailMap(K lkey) {
		throw new UnsupportedOperationException("not implemented");
	}

	@Override public SortedMap<K, V> subMap(K lkey, K rkey) {
		throw new UnsupportedOperationException("not implemented");
	}

}
