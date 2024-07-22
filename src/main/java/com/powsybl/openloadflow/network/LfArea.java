package com.powsybl.openloadflow.network;

import com.powsybl.openloadflow.util.Evaluable;

import java.util.Set;
import java.util.function.Supplier;

public interface LfArea extends PropertyBag {
    String getId();

    double getInterchangeTarget();

    double getInterchange();

    Set<LfBus> getBuses();

    void addBus(LfBus bus);

    void addBoundaryP(Supplier<Evaluable> p);

    LfNetwork getNetwork();

}
