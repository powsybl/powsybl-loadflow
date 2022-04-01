/**
 * Copyright (c) 2019, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.openloadflow.ac;

import com.powsybl.commons.reporter.Report;
import com.powsybl.commons.reporter.Reporter;
import com.powsybl.commons.reporter.TypedValue;
import com.powsybl.openloadflow.ac.outerloop.OuterLoop;
import com.powsybl.openloadflow.ac.outerloop.OuterLoopContext;
import com.powsybl.openloadflow.ac.outerloop.OuterLoopStatus;
import com.powsybl.openloadflow.network.LfBus;
import com.powsybl.openloadflow.network.LfNetwork;
import com.powsybl.openloadflow.util.PerUnit;
import com.powsybl.openloadflow.network.VoltageControl;
import org.apache.commons.lang3.mutable.MutableInt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * @author Geoffroy Jamgotchian <geoffroy.jamgotchian at rte-france.com>
 */
public class ReactiveLimitsOuterLoop implements OuterLoop {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReactiveLimitsOuterLoop.class);

    private static final Comparator<PvToPqBus> BY_NOMINAL_V_COMPARATOR = Comparator.comparingDouble(
        pvToPqBus -> pvToPqBus.controllerBus.getVoltageControl()
            .map(vc -> -vc.getControlledBus().getNominalV())
            .orElse(-pvToPqBus.controllerBus.getNominalV()));

    private static final Comparator<PvToPqBus> BY_TARGET_P_COMPARATOR = Comparator.comparingDouble(pvToPqBus -> -pvToPqBus.controllerBus.getTargetP());

    private static final Comparator<PvToPqBus> BY_ID_COMPARATOR = Comparator.comparing(pvToPqBus -> pvToPqBus.controllerBus.getId());

    private static final int MAX_SWITCH_PQ_PV = 2;

    @Override
    public String getType() {
        return "Reactive limits";
    }

    private enum ReactiveLimitDirection {
        MIN,
        MAX
    }

    private static final class PvToPqBus {

        private final LfBus controllerBus;

        private final double q;

        private final double qLimit;

        private final ReactiveLimitDirection limitDirection;

        private PvToPqBus(LfBus controllerBus, double q, double qLimit, ReactiveLimitDirection limitDirection) {
            this.controllerBus = controllerBus;
            this.q = q;
            this.qLimit = qLimit;
            this.limitDirection = limitDirection;
        }
    }

    private boolean switchPvPq(List<PvToPqBus> pvToPqBuses, int remainingPvBusCount, Reporter reporter) {
        boolean done = false;

        int modifiedRemainingPvBusCount = remainingPvBusCount;
        if (modifiedRemainingPvBusCount == 0) {
            // keep one bus PV, the strongest one which is one at the highest nominal level and highest active power
            // target
            PvToPqBus strongestPvToPqBus = pvToPqBuses.stream()
                    .min(BY_NOMINAL_V_COMPARATOR
                            .thenComparing(BY_TARGET_P_COMPARATOR)
                            .thenComparing(BY_ID_COMPARATOR)) // for stability of the sort
                    .orElseThrow(IllegalStateException::new);
            pvToPqBuses.remove(strongestPvToPqBus);
            modifiedRemainingPvBusCount++;
            LOGGER.warn("All PV buses should switch PQ, strongest one '{}' will stay PV", strongestPvToPqBus.controllerBus.getId());
        }

        if (!pvToPqBuses.isEmpty()) {
            done = true;

            for (PvToPqBus pvToPqBus : pvToPqBuses) {
                // switch PV -> PQ
                pvToPqBus.controllerBus.setGenerationTargetQ(pvToPqBus.qLimit);
                pvToPqBus.controllerBus.setVoltageControlEnabled(false);

                if (LOGGER.isTraceEnabled()) {
                    if (pvToPqBus.limitDirection == ReactiveLimitDirection.MAX) {
                        LOGGER.trace("Switch bus '{}' PV -> PQ, q={} > maxQ={}", pvToPqBus.controllerBus.getId(), pvToPqBus.q * PerUnit.SB,
                                pvToPqBus.qLimit * PerUnit.SB);
                    } else {
                        LOGGER.trace("Switch bus '{}' PV -> PQ, q={} < minQ={}", pvToPqBus.controllerBus.getId(), pvToPqBus.q * PerUnit.SB,
                                pvToPqBus.qLimit * PerUnit.SB);
                    }
                }
            }
        }

        reporter.report(Report.builder()
            .withKey("switchPvPq")
            .withDefaultMessage("${pvToPqBuses} buses switched PV -> PQ ({remainingPvBuses} bus remains PV}")
            .withValue("pvToPqBuses", pvToPqBuses.size())
            .withValue("remainingPvBuses", modifiedRemainingPvBusCount)
            .withSeverity(TypedValue.INFO_SEVERITY)
            .build());
        LOGGER.info("{} buses switched PV -> PQ ({} bus remains PV}", pvToPqBuses.size(), modifiedRemainingPvBusCount);

        return done;
    }

    private static final class PqToPvBus {

        private final LfBus controllerBus;

        private final ReactiveLimitDirection limitDirection;

        private PqToPvBus(LfBus controllerBus, ReactiveLimitDirection limitDirection) {
            this.controllerBus = controllerBus;
            this.limitDirection = limitDirection;
        }
    }

    private boolean switchPqPv(List<PqToPvBus> pqToPvBuses, Reporter reporter) {
        int pqPvSwitchCount = 0;

        for (PqToPvBus pqToPvBus : pqToPvBuses) {
            LfBus controllerBus = pqToPvBus.controllerBus;

            if (controllerBus.getVoltageControlSwitchOffCount() >= MAX_SWITCH_PQ_PV) {
                LOGGER.trace("Bus '{}' blocked PQ as it has reach its max number of PQ -> PV switch ({})",
                        controllerBus.getId(), controllerBus.getVoltageControlSwitchOffCount());
            } else {
                controllerBus.setVoltageControlEnabled(true);
                controllerBus.setGenerationTargetQ(0);
                pqPvSwitchCount++;

                if (LOGGER.isTraceEnabled()) {
                    if (pqToPvBus.limitDirection == ReactiveLimitDirection.MAX) {
                        LOGGER.trace("Switch bus '{}' PQ -> PV, q=maxQ and v={} > targetV={}", controllerBus.getId(), controllerBus.getV(), getBusTargetV(controllerBus));
                    } else {
                        LOGGER.trace("Switch bus '{}' PQ -> PV, q=minQ and v={} < targetV={}", controllerBus.getId(), controllerBus.getV(), getBusTargetV(controllerBus));
                    }
                }
            }
        }

        reporter.report(Report.builder()
            .withKey("switchPqPv")
            .withDefaultMessage("${pqToPvBuses} buses switched PQ -> PV ({blockedPqBuses} buses blocked PQ because have reach max number of switch)")
            .withValue("pqToPvBuses", pqPvSwitchCount)
            .withValue("blockedPqBuses", pqToPvBuses.size() - pqPvSwitchCount)
            .withSeverity(TypedValue.INFO_SEVERITY)
            .build());
        LOGGER.info("{} buses switched PQ -> PV ({} buses blocked PQ because have reach max number of switch)",
                pqPvSwitchCount, pqToPvBuses.size() - pqPvSwitchCount);

        return pqPvSwitchCount > 0;
    }

    /**
     * A bus PV bus can be switched to PQ in 2 cases:
     *  - if Q equals to Qmax
     *  - if Q equals to Qmin
     */
    private void checkPvBus(LfBus controllerBus, List<PvToPqBus> pvToPqBuses, MutableInt remainingPvBusCount) {
        double minQ = controllerBus.getMinQ();
        double maxQ = controllerBus.getMaxQ();
        double q = controllerBus.getQ().eval() + controllerBus.getLoadTargetQ();
        if (q < minQ) {
            pvToPqBuses.add(new PvToPqBus(controllerBus, q, minQ, ReactiveLimitDirection.MIN));
        } else if (q > maxQ) {
            pvToPqBuses.add(new PvToPqBus(controllerBus, q, maxQ, ReactiveLimitDirection.MAX));
        } else {
            remainingPvBusCount.increment();
        }
    }

    /**
     * A PQ bus can be switched to PV in 2 cases:
     *  - if Q is equal to Qmin and V is less than targetV: it means that the PQ bus can be unlocked in order to increase the reactive power and reach its targetV.
     *  - if Q is equal to Qmax and V is greater than targetV: it means that the PQ bus can be unlocked in order to decrease the reactive power and reach its target V.
     */
    private void checkPqBus(LfBus controllerCapableBus, List<PqToPvBus> pqToPvBuses) {
        double minQ = controllerCapableBus.getMinQ();
        double maxQ = controllerCapableBus.getMaxQ();
        double q = controllerCapableBus.getGenerationTargetQ();
        double distanceToMaxQ = Math.abs(q - maxQ);
        double distanceToMinQ = Math.abs(q - minQ);
        if (distanceToMaxQ < distanceToMinQ  && controllerCapableBus.getV() > getBusTargetV(controllerCapableBus)) { // bus produce too much reactive power
            pqToPvBuses.add(new PqToPvBus(controllerCapableBus, ReactiveLimitDirection.MAX));
        }
        if (distanceToMaxQ > distanceToMinQ && controllerCapableBus.getV() < getBusTargetV(controllerCapableBus)) { // bus absorb too much reactive power
            pqToPvBuses.add(new PqToPvBus(controllerCapableBus, ReactiveLimitDirection.MIN));
        }
    }

    private double getBusTargetV(LfBus bus) {
        return bus.getVoltageControl().map(VoltageControl::getTargetValue).orElse(Double.NaN);
    }

    @Override
    public OuterLoopStatus check(OuterLoopContext context, Reporter reporter) {
        OuterLoopStatus status = OuterLoopStatus.STABLE;

        List<PvToPqBus> pvToPqBuses = new ArrayList<>();
        List<PqToPvBus> pqToPvBuses = new ArrayList<>();
        MutableInt remainingPvBusCount = new MutableInt();
        for (LfBus bus : context.getNetwork().getBuses()) {
            if (bus.isVoltageControlEnabled() && !bus.isDisabled()) {
                checkPvBus(bus, pvToPqBuses, remainingPvBusCount);
            } else if (bus.hasVoltageControllerCapability() && !bus.isDisabled()) {
                if (!bus.hasGeneratorsWithSlope()) {
                    checkPqBus(bus, pqToPvBuses);
                } else {
                    // we don't support switching PQ to PV for bus with one controller with slope.
                    LOGGER.warn("Controller bus '{}' wants to control back voltage with slope: not supported", bus.getId());
                }
            }
        }

        if (!pvToPqBuses.isEmpty() && switchPvPq(pvToPqBuses, remainingPvBusCount.intValue(), reporter)) {
            status = OuterLoopStatus.UNSTABLE;
        }
        if (!pqToPvBuses.isEmpty() && switchPqPv(pqToPvBuses, reporter)) {
            status = OuterLoopStatus.UNSTABLE;
        }

        return status;
    }

    @Override
    public void cleanup(LfNetwork network) {
        for (LfBus bus : network.getBuses()) {
            bus.setVoltageControlSwitchOffCount(0);
        }
    }
}
