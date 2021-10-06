/**
 * Copyright (c) 2021, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.openloadflow.util;

import com.powsybl.openloadflow.network.LfBus;
import com.powsybl.openloadflow.network.LfGenerator;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * @author Florian Dupuy <florian.dupuy at rte-france.com>
 */
public class BusState {
    private final double angle;
    private final double loadTargetP;
    private final double loadTargetQ;
    private final Map<String, Double> generatorsTargetP;
    private final boolean disabled;
    private final boolean isVoltageControllerEnabled;
    private final double generationTargetQ;
    private final double absVariableLoadTargetP;

    public BusState(LfBus b) {
        this.angle = b.getAngle();
        this.loadTargetP = b.getLoadTargetP();
        this.loadTargetQ = b.getLoadTargetQ();
        this.generatorsTargetP = b.getGenerators().stream().collect(Collectors.toMap(LfGenerator::getId, LfGenerator::getTargetP));
        this.disabled = b.isDisabled();
        this.isVoltageControllerEnabled = b.isVoltageControllerEnabled();
        this.generationTargetQ = b.getGenerationTargetQ();
        this.absVariableLoadTargetP = b.getLfLoads().getAbsVariableLoadTargetP();
    }

    public void restoreBusState(LfBus bus) {
        restoreBusActiveState(bus);
        bus.setLoadTargetQ(loadTargetQ);
        bus.setGenerationTargetQ(generationTargetQ);
        bus.setDisabled(disabled);
        bus.setVoltageControllerEnabled(isVoltageControllerEnabled);
        bus.setVoltageControlSwitchOffCount(0);
        bus.getLfLoads().setAbsVariableLoadTargetP(absVariableLoadTargetP);
    }

    public void restoreBusActiveState(LfBus bus) {
        bus.setAngle(angle);
        bus.setLoadTargetP(loadTargetP);
        bus.getGenerators().forEach(g -> {
            g.setTargetP(generatorsTargetP.get(g.getId()));
        });
        bus.getLfLoads().setAbsVariableLoadTargetP(absVariableLoadTargetP);
    }

    /**
     * Get the map of the states of given buses, indexed by the bus itself
     * @param buses the bus for which the state is returned
     * @return the map of the states of given buses, indexed by the bus itself
     */
    public static Map<LfBus, BusState> createBusStates(Collection<LfBus> buses) {
        Map<LfBus, BusState> busStates = new HashMap<>();
        addBusStates(buses, busStates);
        return busStates;
    }

    public static void addBusStates(Collection<LfBus> busesToSave, Map<LfBus, BusState> busStates) {
        busesToSave.forEach(lfBus -> busStates.put(lfBus, new BusState(lfBus)));
    }

    /**
     * Set the bus states based on the given map of states
     * @param busStates the map containing the bus states, indexed by buses
     */
    public static void restoreBusStates(Map<LfBus, BusState> busStates) {
        busStates.forEach((b, state) -> state.restoreBusState(b));
    }

    /**
     * Set the bus states based on the given map of states
     * @param busStates the map containing the bus states, indexed by buses
     */
    public static void restoreBusActiveStates(Map<LfBus, BusState> busStates) {
        busStates.forEach((b, state) -> state.restoreBusActiveState(b));
    }
}

