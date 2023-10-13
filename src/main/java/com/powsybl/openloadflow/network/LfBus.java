/**
 * Copyright (c) 2019, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.openloadflow.network;

import com.powsybl.iidm.network.Country;
import com.powsybl.openloadflow.util.Evaluable;
import com.powsybl.security.results.BusResult;

import java.util.*;

/**
 * @author Geoffroy Jamgotchian <geoffroy.jamgotchian at rte-france.com>
 */
public interface LfBus extends LfElement {

    enum QLimitType {
        MIN_Q,
        MAX_Q
    }

    String getVoltageLevelId();

    boolean isFictitious();

    boolean isSlack();

    void setSlack(boolean slack);

    boolean isReference();

    void setReference(boolean reference);

    /**
     * Get list of all voltage controls (generator + transformer + shunt) linked to this bus.
     */
    List<VoltageControl<?>> getVoltageControls();

    /**
     * Check if this bus is voltage controlled so either by a generator, a transformer or a shunt.
     */
    boolean isVoltageControlled();

    boolean isVoltageControlled(VoltageControl.Type type);

    Optional<VoltageControl<?>> getVoltageControl(VoltageControl.Type type);

    /**
     * Get the highest priority voltage control connected to a bus of the zero impedance subgraph to which this bus
     * belong.
     */
    Optional<VoltageControl<?>> getHighestPriorityMainVoltageControl();

    // generator voltage control
    Optional<GeneratorVoltageControl> getGeneratorVoltageControl();

    void setGeneratorVoltageControl(GeneratorVoltageControl generatorVoltageControl);

    boolean isGeneratorVoltageControlled();

    boolean isGeneratorVoltageControlEnabled();

    void setGeneratorVoltageControlEnabled(boolean generatorVoltageControlEnabled);

    // generator reactive power control

    List<LfGenerator> getGeneratorsControllingVoltageWithSlope();

    boolean hasGeneratorsWithSlope();

    void removeGeneratorSlopes();

    Optional<ReactivePowerControl> getReactivePowerControl();

    void setReactivePowerControl(ReactivePowerControl reactivePowerControl);

    boolean hasReactivePowerControl();

    double getTargetP();

    double getTargetQ();

    double getLoadTargetP();

    double getLoadTargetQ();

    void invalidateGenerationTargetP();

    double getGenerationTargetP();

    double getGenerationTargetQ();

    void setGenerationTargetQ(double generationTargetQ);

    double getMinQ();

    double getMaxQ();

    Optional<QLimitType> getQLimitType();

    void setQLimitType(QLimitType qLimitType);

    double getV();

    void setV(double v);

    Evaluable getCalculatedV();

    void setCalculatedV(Evaluable calculatedV);

    double getAngle();

    void setAngle(double angle);

    /**
     * Get nominal voltage in Kv.
     * @return nominal voltage in Kv
     */
    double getNominalV();

    default double getLowVoltageLimit() {
        return Double.NaN;
    }

    default double getHighVoltageLimit() {
        return Double.NaN;
    }

    List<LfGenerator> getGenerators();

    Optional<LfShunt> getShunt();

    Optional<LfShunt> getControllerShunt();

    Optional<LfShunt> getSvcShunt();

    Optional<LfLoad> getLoad();

    List<LfBranch> getBranches();

    void addBranch(LfBranch branch);

    void addHvdc(LfHvdc hvdc);

    void updateState(LfNetworkStateUpdateParameters parameters);

    // transformer voltage control

    Optional<TransformerVoltageControl> getTransformerVoltageControl();

    void setTransformerVoltageControl(TransformerVoltageControl transformerVoltageControl);

    boolean isTransformerVoltageControlled();

    // shunt voltage control

    Optional<ShuntVoltageControl> getShuntVoltageControl();

    void setShuntVoltageControl(ShuntVoltageControl shuntVoltageControl);

    boolean isShuntVoltageControlled();

    void setP(Evaluable p);

    Evaluable getP();

    void setQ(Evaluable q);

    Evaluable getQ();

    default boolean isParticipating() {
        return false;
    }

    default List<BusResult> createBusResults() {
        return Collections.emptyList();
    }

    /**
     * Find bus + parallel branches neighbors.
     */
    Map<LfBus, List<LfBranch>> findNeighbors();

    double getRemoteVoltageControlReactivePercent();

    void setRemoteVoltageControlReactivePercent(double remoteVoltageControlReactivePercent);

    /**
     * Get active power mismatch.
     * Only make sens for slack bus.
     */
    double getMismatchP();

    default Optional<Country> getCountry() {
        return Optional.empty();
    }

    void setZeroImpedanceNetwork(LoadFlowModel loadFlowModel, LfZeroImpedanceNetwork zeroImpedanceNetwork);

    LfZeroImpedanceNetwork getZeroImpedanceNetwork(LoadFlowModel loadFlowModel);

    LfAsymBus getAsym();

    void setAsym(LfAsymBus asym);
}
