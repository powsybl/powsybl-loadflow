/**
 * Copyright (c) 2020, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.openloadflow.network.util;

import com.powsybl.iidm.network.Load;
import com.powsybl.openloadflow.network.LfBus;
import com.powsybl.openloadflow.network.PerUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * @author Anne Tilloy <anne.tilloy at rte-france.com>
 */
public class LoadActivePowerDistributionStep implements ActivePowerDistribution.Step {

    private static final Logger LOGGER = LoggerFactory.getLogger(LoadActivePowerDistributionStep.class);

    private final boolean distributedOnConformLoad;

    private final boolean loadPowerFactorConstant;

    public LoadActivePowerDistributionStep(boolean distributedOnConformLoad, boolean loadPowerFactorConstant) {
        this.distributedOnConformLoad = distributedOnConformLoad;
        this.loadPowerFactorConstant = loadPowerFactorConstant;
    }

    @Override
    public String getElementType() {
        return "load";
    }

    @Override
    public List<ParticipatingElement> getParticipatingElements(Collection<LfBus> buses) {
        return buses.stream()
                .filter(bus -> bus.getLoadCount() > 0 && !(bus.isFictitious() || bus.isDisabled()))
                .map(bus -> new ParticipatingElement(bus, getParticipationFactor(bus)))
                .collect(Collectors.toList());
    }

    private double getParticipationFactor(LfBus bus) {
        return distributedOnConformLoad ? bus.getAbsVariableLoadTargetP() : bus.getAbsLoadTargetP();
    }

    @Override
    public double run(List<ParticipatingElement> participatingElements, int iteration, double remainingMismatch) {
        // normalize participation factors at each iteration start as some
        // loads might have reach zero and have been discarded.
        ParticipatingElement.normalizeParticipationFactors(participatingElements, "load");

        double done = 0d;
        int modifiedBuses = 0;
        int loadsAtMin = 0;
        Iterator<ParticipatingElement> it = participatingElements.iterator();
        while (it.hasNext()) {
            ParticipatingElement participatingBus = it.next();
            LfBus bus = (LfBus) participatingBus.getElement();
            double factor = participatingBus.getFactor();

            double loadTargetP = bus.getLoadTargetP();
            double newLoadTargetP = loadTargetP - remainingMismatch * factor;

            if (newLoadTargetP != loadTargetP) {
                LOGGER.info("Rescale '{}' active power target: {} -> {}",
                        bus.getId(), loadTargetP * PerUnit.SB, newLoadTargetP * PerUnit.SB);

                if (loadPowerFactorConstant) {
                    ensurePowerFactorConstant(bus, newLoadTargetP, distributedOnConformLoad);
                }

                bus.setLoadTargetP(newLoadTargetP);
                done += loadTargetP - newLoadTargetP;
                modifiedBuses++;
            }
        }

        LOGGER.debug("{} MW / {} MW distributed at iteration {} to {} loads ({} at min consumption)",
                done * PerUnit.SB, -remainingMismatch * PerUnit.SB, iteration, modifiedBuses, loadsAtMin);

        return done;
    }

    private static void ensurePowerFactorConstant(LfBus bus, double newLoadTargetP, boolean distributedOnConformLoad) {
        // if loadPowerFactorConstant is true, when updating targetP on loads,
        // we have to keep the power factor constant by updating targetQ.
        double newLoadTargetQ;
        if (bus.ensurePowerFactorConstantByLoad()) {
            double absLoadTargetP = bus.getAbsLoadTargetP() * PerUnit.SB;
            double absVariableLoadTargetP = bus.getAbsVariableLoadTargetP() * PerUnit.SB;
            double initialLoadTargetP = bus.getInitialLoadTargetP();
            newLoadTargetQ = 0;
            for (Load load : bus.getLoads()) {
                newLoadTargetQ += LoadUtil.getPowerFactor(load) * (load.getP0() / PerUnit.SB + (newLoadTargetP - initialLoadTargetP) * LoadUtil.getParticipationFactor(load, distributedOnConformLoad, absLoadTargetP, absVariableLoadTargetP));
            }
        } else {
            newLoadTargetQ = newLoadTargetP * bus.getLoadTargetQ() / bus.getLoadTargetP();
        }
        if (newLoadTargetQ != bus.getLoadTargetQ()) {
            LOGGER.info("Rescale '{}' reactive power target on load: {} -> {}",
                    bus.getId(), bus.getLoadTargetQ() * PerUnit.SB, newLoadTargetQ * PerUnit.SB);
            bus.setLoadTargetQ(newLoadTargetQ);
        }
    }
}
