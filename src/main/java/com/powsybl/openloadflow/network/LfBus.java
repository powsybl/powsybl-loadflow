/**
 * Copyright (c) 2019, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.openloadflow.network;

import com.powsybl.openloadflow.network.impl.LfLoads;
import com.powsybl.openloadflow.util.Evaluable;
import com.powsybl.security.results.BusResults;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * @author Geoffroy Jamgotchian <geoffroy.jamgotchian at rte-france.com>
 */
public interface LfBus extends LfElement {

    String getVoltageLevelId();

    boolean isFictitious();

    boolean isSlack();

    void setSlack(boolean slack);

    boolean hasVoltageControllerCapability();

    boolean isVoltageControllerEnabled();

    boolean isVoltageControlled();

    List<LfGenerator> getGeneratorsControllingVoltageWithSlope();

    boolean hasGeneratorsWithSlope();

    void removeGeneratorSlopes();

    /**
     * Get the number of time, voltage control status has be set from true to false.
     *
     * @return the number of time, voltage control status has be set from true to false
     */
    int getVoltageControlSwitchOffCount();

    void setVoltageControlSwitchOffCount(int voltageControlSwitchOffCount);

    void setVoltageControllerEnabled(boolean voltageControl);

    Optional<VoltageControl> getVoltageControl();

    void removeVoltageControl();

    void setVoltageControl(VoltageControl voltageControl);

    Optional<ReactivePowerControl> getReactivePowerControl();

    void setReactivePowerControl(ReactivePowerControl reactivePowerControl);

    double getTargetP();

    double getTargetQ();

    double getLoadTargetP();

    double getInitialLoadTargetP();

    void setLoadTargetP(double loadTargetP);

    double getLoadTargetQ();

    void setLoadTargetQ(double loadTargetQ);

    boolean ensurePowerFactorConstantByLoad();

    double getGenerationTargetP();

    double getGenerationTargetQ();

    void setGenerationTargetQ(double generationTargetQ);

    double getMinQ();

    double getMaxQ();

    double getV();

    void setV(double v);

    Evaluable getCalculatedV();

    void setCalculatedV(Evaluable calculatedV);

    double getAngle();

    void setAngle(double angle);

    double getCalculatedQ();

    void setCalculatedQ(double calculatedQ);

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

    LfLoads getLfLoads();

    List<LfBranch> getBranches();

    void addBranch(LfBranch branch);

    void updateState(boolean reactiveLimits, boolean writeSlackBus, boolean distributedOnConformLoad, boolean loadPowerFactorConstant);

    Optional<DiscreteVoltageControl> getDiscreteVoltageControl();

    boolean isDiscreteVoltageControlled();

    void setDiscreteVoltageControl(DiscreteVoltageControl discreteVoltageControl);

    void setP(Evaluable p);

    Evaluable getP();

    void setQ(Evaluable q);

    Evaluable getQ();

    default boolean isParticipating() {
        return false;
    }

    BusResults createBusResult();

    /**
     * Find bus + parallel branches neighbors.
     */
    Map<LfBus, List<LfBranch>> findNeighbors();

    double getRemoteVoltageControlReactivePercent();

    void setRemoteVoltageControlReactivePercent(double remoteVoltageControlReactivePercent);
}
