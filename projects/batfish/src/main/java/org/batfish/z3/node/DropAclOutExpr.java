package org.batfish.z3.node;

import org.batfish.z3.Synthesizer;

public class DropAclOutExpr extends PacketRelExpr {

  public static final String NAME = "R_drop_acl_out";

  public DropAclOutExpr(Synthesizer synthesizer) {
    super(synthesizer, NAME);
  }
}
