package org.batfish.symbolic.abstraction;

import com.google.common.collect.Sets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import org.batfish.common.plugin.IBatfish;
import org.batfish.datamodel.BgpNeighbor;
import org.batfish.datamodel.Configuration;
import org.batfish.datamodel.HeaderSpace;
import org.batfish.datamodel.IpWildcard;
import org.batfish.datamodel.Prefix;
import org.batfish.datamodel.PrefixTrie;
import org.batfish.datamodel.RoutingProtocol;
import org.batfish.datamodel.routing_policy.RoutingPolicy;
import org.batfish.datamodel.routing_policy.expr.AbstractionPrefixSet;
import org.batfish.datamodel.routing_policy.expr.AsPathSetElem;
import org.batfish.datamodel.routing_policy.expr.BooleanExpr;
import org.batfish.datamodel.routing_policy.expr.BooleanExprs;
import org.batfish.datamodel.routing_policy.expr.BooleanExprs.StaticBooleanExpr;
import org.batfish.datamodel.routing_policy.expr.Conjunction;
import org.batfish.datamodel.routing_policy.expr.DestinationNetwork;
import org.batfish.datamodel.routing_policy.expr.Disjunction;
import org.batfish.datamodel.routing_policy.expr.ExplicitAsPathSet;
import org.batfish.datamodel.routing_policy.expr.MatchAsPath;
import org.batfish.datamodel.routing_policy.expr.MatchPrefixSet;
import org.batfish.datamodel.routing_policy.expr.MatchProtocol;
import org.batfish.datamodel.routing_policy.expr.Not;
import org.batfish.datamodel.routing_policy.expr.RegexAsPathSetElem;
import org.batfish.datamodel.routing_policy.statement.If;
import org.batfish.datamodel.routing_policy.statement.Statement;
import org.batfish.datamodel.routing_policy.statement.Statements;
import org.batfish.datamodel.routing_policy.statement.Statements.StaticStatement;
import org.batfish.symbolic.Graph;
import org.batfish.symbolic.GraphEdge;
import org.batfish.symbolic.Protocol;
import org.batfish.symbolic.collections.Table2;
import org.batfish.symbolic.utils.Tuple;

/*
 * Create a simpler network for use use by Batfish by adding
 * Filters on interfaces that correspond to the abstract network
 */
public class BatfishCompressor {

  private IBatfish _batfish;

  private Graph _graph;

  private String _internalRegex;

  public BatfishCompressor(IBatfish batfish) {
    _batfish = batfish;
    _graph = new Graph(batfish);
    _internalRegex = internalRegex();
  }

  /**
   * Merge two maps of filters. When there's collision take the union (to allow traffic matching
   * either filter).
   */
  private void addAll(
      Map<GraphEdge, Tuple<PrefixTrie, Boolean>> newMap,
      Map<GraphEdge, Tuple<PrefixTrie, Boolean>> oldMap) {
    for (Entry<GraphEdge, Tuple<PrefixTrie, Boolean>> entry : oldMap.entrySet()) {
      Tuple<PrefixTrie, Boolean> tup = newMap.get(entry.getKey());
      if (tup == null) {
        newMap.put(entry.getKey(), entry.getValue());
      } else {
        newMap.put(
            entry.getKey(),
            new Tuple<>(
                new PrefixTrie(
                    new TreeSet<>(
                        Sets.union(
                            tup.getFirst().getPrefixes(),
                            entry.getValue().getFirst().getPrefixes()))),
                // TODO how do we update the default correctly? and/or/etc?
                tup.getSecond()));
      }
    }
  }

  private Map<GraphEdge, Tuple<PrefixTrie, Boolean>> mergeFilters(
      Map<GraphEdge, Tuple<PrefixTrie, Boolean>> x, Map<GraphEdge, Tuple<PrefixTrie, Boolean>> y) {
    Map<GraphEdge, Tuple<PrefixTrie, Boolean>> newMap = new HashMap<>();
    addAll(newMap, x);
    addAll(newMap, y);
    return newMap;
  }

  /**
   * A slice is an abstracted network for a single destination EC. Given one destination EC, return
   * a mapping from each edge to a prefix trie that will filter traffic on that EC. We need separate
   * one for each one because they get mutated when we install the filters in the network.
   */
  private Map<GraphEdge, Tuple<PrefixTrie, Boolean>> processSlice(NetworkSlice slice) {
    Map<GraphEdge, Tuple<PrefixTrie, Boolean>> filters = new HashMap<>();

    // get the set of prefixes for this equivalence class.
    TreeSet<Prefix> prefixSet =
        slice
            .getHeaderSpace()
            .getDstIps()
            .stream()
            .map(IpWildcard::toPrefix)
            .collect(Collectors.toCollection(TreeSet::new));

    for (GraphEdge edge : slice.getGraph().getAllEdges()) {
      if (!edge.isAbstract() && !_graph.isLoopback(edge)) {
        // add a filter to restrict traffic to this equivalence class.
        filters.put(edge, new Tuple<>(new PrefixTrie(prefixSet), slice.getIsDefaultCase()));
      }
    }
    return filters;
  }

  private String internalRegex() {
    StringBuilder matchInternal = new StringBuilder("(,|\\\\{|\\\\}|^|\\$| )(");
    Collection<BgpNeighbor> neighbors = _graph.getEbgpNeighbors().values();
    Set<Integer> allAsns = new HashSet<>();
    for (BgpNeighbor n : neighbors) {
      Integer asn = n.getLocalAs();
      allAsns.add(asn);
    }
    int i = 0;
    for (Integer asn : allAsns) {
      i++;
      matchInternal.append(asn);
      if (i < allAsns.size()) {
        matchInternal.append("|");
      }
    }
    matchInternal.append(")$");
    return matchInternal.toString();
  }

  // PrefixTrie: capture the prefixes you are installing to allow traffic through. Restrict
  // to those prefixes
  // Boolean: are the prefixes for the default equivalence class?
  private List<Statement> applyFilters(
      List<Statement> statements, @Nullable Tuple<PrefixTrie, Boolean> tup) {
    If i = new If();
    List<Statement> newStatements = new ArrayList<>();
    List<Statement> falseStatements = new ArrayList<>();
    Statement reject = new StaticStatement(Statements.ExitReject);
    falseStatements.add(reject);
    if (tup == null) {
      StaticBooleanExpr sbe = new StaticBooleanExpr(BooleanExprs.False);
      i.setGuard(sbe);
    } else {
      AbstractionPrefixSet eps = new AbstractionPrefixSet(tup.getFirst());
      MatchPrefixSet match = new MatchPrefixSet(new DestinationNetwork(), eps);
      if (tup.getSecond()) {
        // These prefixes are the default equivalence class.
        // That means they aren't used in the network (no related to any destination prefix
        // configured in the network.
        // Add a filter that only allows traffic for those prefixes if it came from outside.
        // EXTERNAL = (protocol is bgp or ibgp) and (the AS path is not an internal path)
        // MATCH = destination matches the prefixTrie
        // GUARD = EXTERNAL or MATCH (only allow this traffic through)
        // TODO maybe GUARD should be EXTERNAL and MATCH. (ask Ryan)
        // TODO why do we need to check EXTERNAL?
        // we can always use this true branch. The false branch is an optimization for when
        // we compress to a particular EC and don't care about external stuff that doesn't match
        // the EC.

        // TODO for now, always take the true branch. Until we understand what it's doing and
        // why/when it should
        List<AsPathSetElem> elements = new ArrayList<>();
        elements.add(new RegexAsPathSetElem(_internalRegex));
        ExplicitAsPathSet expr = new ExplicitAsPathSet(elements);
        MatchAsPath matchPath = new MatchAsPath(expr);
        MatchProtocol mpBgp = new MatchProtocol(RoutingProtocol.BGP);
        MatchProtocol mpIbgp = new MatchProtocol(RoutingProtocol.IBGP);
        Disjunction d = new Disjunction();
        List<BooleanExpr> disjuncts = new ArrayList<>();
        disjuncts.add(mpBgp);
        disjuncts.add(mpIbgp);
        d.setDisjuncts(disjuncts);
        Not n = new Not(matchPath);
        Conjunction c = new Conjunction();
        List<BooleanExpr> conjuncts = new ArrayList<>();
        conjuncts.add(d);
        conjuncts.add(n);
        c.setConjuncts(conjuncts);
        Disjunction pfxOrExternal = new Disjunction();
        List<BooleanExpr> exprs = new ArrayList<>();
        exprs.add(match);
        exprs.add(c);
        pfxOrExternal.setDisjuncts(exprs);
        i.setGuard(pfxOrExternal);
      } else {
        // Not default equivalence class, so just let traffic through if dest matches the prefixTrie
        i.setGuard(match);
      }
    }
    i.setFalseStatements(falseStatements);
    i.setTrueStatements(statements);
    newStatements.add(i);
    return newStatements;
  }

  private Map<String, Configuration> applyFilters(
      Table2<String, GraphEdge, Tuple<PrefixTrie, Boolean>> filtersByRouter) {
    Map<String, Configuration> newConfigs = new HashMap<>();
    for (Entry<String, Configuration> entry : _graph.getConfigurations().entrySet()) {
      String router = entry.getKey();
      Map<GraphEdge, Tuple<PrefixTrie, Boolean>> filters = filtersByRouter.get(router);
      if (filters != null) {
        Configuration config = entry.getValue();
        // Include this config in the compressed network.
        newConfigs.put(router, config);

        // Mutate the config by adding import/export filters
        for (GraphEdge ge : _graph.getEdgeMap().get(router)) {
          Tuple<PrefixTrie, Boolean> tup = filters.get(ge);

          RoutingPolicy ipol = _graph.findImportRoutingPolicy(router, Protocol.BGP, ge);
          if (ipol != null) {
            RoutingPolicy newIpol = new RoutingPolicy(ipol.getName(), config);
            newIpol.setStatements(applyFilters(ipol.getStatements(), tup));
            config.getRoutingPolicies().put(newIpol.getName(), newIpol);
          }

          RoutingPolicy epol = _graph.findExportRoutingPolicy(router, Protocol.BGP, ge);
          if (epol != null) {
            RoutingPolicy newEpol = new RoutingPolicy(epol.getName(), config);
            newEpol.setStatements(applyFilters(epol.getStatements(), tup));
            config.getRoutingPolicies().put(newEpol.getName(), newEpol);
          }
        }
      }
    }
    return newConfigs;
  }

  public Map<String, Configuration> compress(HeaderSpace h) {
    DestinationClasses dcs = DestinationClasses.create(_batfish, h, true);
    ArrayList<Supplier<NetworkSlice>> ecs = NetworkSlice.allSlices(dcs, 0);
    Optional<Map<GraphEdge, Tuple<PrefixTrie, Boolean>>> opt =
        ecs.stream().map(Supplier::get).map(this::processSlice).reduce(this::mergeFilters);
    if (!opt.isPresent()) {
      return new HashMap<>();
    }
    Map<GraphEdge, Tuple<PrefixTrie, Boolean>> filters = opt.get();
    Table2<String, GraphEdge, Tuple<PrefixTrie, Boolean>> filtersByRouter = new Table2<>();
    for (Entry<GraphEdge, Tuple<PrefixTrie, Boolean>> entry : filters.entrySet()) {
      GraphEdge ge = entry.getKey();
      filtersByRouter.put(ge.getRouter(), ge, entry.getValue());
    }
    return applyFilters(filtersByRouter);
  }
}
