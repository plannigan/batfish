package org.batfish.atomicpredicates;

import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Streams;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Stream;
import net.sf.javabdd.BDD;

/**
 * A trie of BDDs, representing disjoint sets of headerspaces (atomic predicates). It is inspired by
 * DDNF, with two key differences: it stores BDDs instead of ternary bitvectors, and the children of
 * each node are disjoint subsets of the parent (rather than possibly overlapping subsets as in
 * DDNF). The latter property makes this a tree, while DDNFs are DAGs.
 *
 * <p>The DDNF as a whole represents a set of atomic predicates -- a partition of the full
 * headerspace into headerspaces such that for each one, the network does not distinguish between
 * any to headers in that space.
 *
 * <p>Each node represents the headerspace described by its BDD minus the headerspaces of its
 * children.
 */
public class BDDTrie {
  public static class BDDTrieException extends Exception {
    private String _message;
    private List<Integer> _parentPath;
    private Integer _childIndex;

    BDDTrieException(String message, List<Integer> parentPath, Integer childIndex) {
      _message = message;
      _parentPath = ImmutableList.copyOf(parentPath);
      _childIndex = childIndex;
    }
  }

  private class Node {
    // the original space.
    final BDD _headerspace;
    // the negation of the original headerspace
    final Supplier<BDD> _notHeaderspace;

    final int _id;

    /** Invariant: For all i, _children[i]._headerspace is a strict subset of _headerspace. */
    final List<Node> _children;

    Node(BDD headerspace) {
      this(headerspace, new ArrayList<>());
    }

    Node(BDD headerspace, List<Node> children) {
      _id = _allNodes.size();
      _allNodes.add(this);
      _children = children;
      _headerspace = headerspace;
      _notHeaderspace = Suppliers.memoize(_headerspace::not);
    }

    Stream<BDD> atomicPredicate() {
      BDD result = _headerspace;
      for (Node child : _children) {
        result = result.and(child._notHeaderspace.get());
      }
      return result.isZero() ? Stream.empty() : Stream.of(result);
    }

    Stream<BDD> atomicPredicates() {
      return Streams.concat(atomicPredicate(), _children.stream().flatMap(Node::atomicPredicates));
    }

    void checkInvariants(List<Integer> path) throws BDDTrieException {
      BDD childrenHeaderspaces = _headerspace.getFactory().zero();
      for (int i = 0; i < _children.size(); i++) {
        BDD childHeaderspace = _children.get(i)._headerspace;
        if (!childHeaderspace.imp(_headerspace).isOne()) {
          throw new BDDTrieException(
              "child headerspace is not a subset of parent headerspace", path, i);
        }
        if (_headerspace.imp(childHeaderspace).isOne()) {
          throw new BDDTrieException("child headerspace is equal to parent headerspace", path, i);
        }
        if (!childHeaderspace.and(childrenHeaderspaces).isZero()) {
          throw new BDDTrieException(
              "child headerspace intersects with a sibling's headerspace", path, i);
        }
        childrenHeaderspaces = childrenHeaderspaces.or(childHeaderspace);
      }
    }

    Stream<Integer> ids() {
      return Streams.concat(Stream.of(_id), _children.stream().flatMap(Node::ids));
    }

    Stream<Node> insert(BDD bdd) {
      // invariant: bdd is a subset of _headerspace
      assert !bdd.isZero() && bdd.imp(_headerspace).isOne();

      if (_headerspace.equals(bdd)) {
        return Stream.of(this);
      }

      // initialize lazily
      List<Node> bddChildren = null;
      Stream.Builder<Node> nodes = Stream.builder();

      for (Node child : _children) {
        if (bdd.equals(child._headerspace)) {
          nodes.accept(child);
          return nodes.build();
        }
        BDD intersection = bdd.and(child._headerspace);
        if (intersection.isZero()) {
          continue;
        } else if (intersection.equals(child._headerspace)) {
          /*
           * child._headerspace is a subset of bdd.
           * We want to insert a new node between this and child, but bdd might not be the right
           * headerspace (it may intersect other _children). So we have to wait until we've checked
           * all the other children.
           */
          if (bddChildren == null) {
            bddChildren = new ArrayList<>();
          }
          bddChildren.add(child);
          continue;
        } else if (intersection.equals(bdd)) {
          /*
           * bdd is a subset of child._headerspace.
           * since children headerspaces are disjoint, bddChildren must be empty;
           */
          assert bddChildren == null;

          // bdd is a strict subset of child._headerspace
          child.insert(bdd).forEach(nodes);
          return nodes.build();
        } else {
          /*
           * We know the intersection only intersects this child (because the children are
           * disjoint), so insert directly to it.
           */
          child.insert(intersection).forEach(nodes);

          // bdd intersects child. split.
          BDD bddMinusIntersection = bdd.and(child._notHeaderspace.get());

          assert !bddMinusIntersection.isZero();

          /*
           * The nonIntersection can only intersect children we haven't looked at yet, so continue
           * this scan with the nonIntersection instead of bdd.
           */
          bdd = bddMinusIntersection;

          /*
           * Either bddChildren is empty (i.e. bdd is not a superset of any previous children), or
           * every previous child that is a subset of bdd is also a subset of nonIntersection.
           */
          assert bddChildren == null
              || bddChildren
                  .stream()
                  .allMatch(bddChild -> bddChild._headerspace.imp(bddMinusIntersection).isOne());
        }
      }

      // bdd does not intersect any child headerspace. add a new child for it.
      if (bddChildren != null) {
        _children.removeAll(bddChildren);
        Node node = new Node(bdd, bddChildren);
        nodes.accept(node);
        _children.add(node);
      } else {
        Node node = new Node(bdd);
        nodes.accept(node);
        _children.add(node);
      }
      return nodes.build();
    }

    public Stream<Integer> atomicPredicates(BDD bdd) {
      assert bdd.imp(this._headerspace).isOne();

      if (bdd.equals(this._headerspace)) {
        return ids();
      }

      Stream.Builder<Integer> ids = Stream.builder();
      for (Node child : _children) {
        BDD intersection = child._headerspace.and(bdd);
        if (!intersection.isZero()) {
          child.atomicPredicates(intersection).forEach(ids);
          if (intersection.equals(bdd)) {
            // nothing left
            break;
          } else {
            bdd = bdd.and(child._notHeaderspace.get());
          }
        }
      }

      if (!bdd.isZero()) {
        // part of the input bdd is disjoint from the children. so it overlaps this
        ids.add(_id);
      }
      return ids.build();
    }
  }

  private List<Node> _allNodes;

  /** Remember the nodes that correspond to a BDD that has been inserted into the trie. */
  private Map<BDD, List<Node>> _bddNodes;

  private Node _root;

  public BDDTrie(Collection<BDD> bdds) {
    _allNodes = new ArrayList<>();
    _bddNodes = new HashMap<>();
    _root = new Node(bdds.iterator().next().getFactory().one());
    _allNodes.add(_root);
    for (BDD bdd : bdds) {
      if (!bdd.isZero()) {
        _bddNodes.put(bdd, _root.insert(bdd).collect(ImmutableList.toImmutableList()));
      }
    }
  }

  public Stream<BDD> atomicPredicates() {
    return _root.atomicPredicates();
  }

  public Stream<Integer> atomicPredicates(BDD bdd) {
    if (_bddNodes.containsKey(bdd)) {
      // bdd is one of the input BDDs.
      return _bddNodes.get(bdd).stream().flatMap(Node::ids);
    }

    return _root.atomicPredicates(bdd);
  }

  public void checkInvariants() throws BDDTrieException {
    _root.checkInvariants(new ArrayList<>());
  }
}