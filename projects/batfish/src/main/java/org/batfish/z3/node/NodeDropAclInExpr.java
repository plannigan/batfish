package org.batfish.z3.node;

import org.batfish.z3.Synthesizer;

public class NodeDropAclInExpr extends NodePacketRelExpr {

  public static final String BASE_NAME = "R_node_drop_acl_in";

  public NodeDropAclInExpr(Synthesizer synthesizer, String nodeArg) {
    super(synthesizer, BASE_NAME, nodeArg);
  }
}
