/*
 * Copyright (c) 2019-2025, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.ac.outerloop;

import com.powsybl.commons.report.ReportNode;
import com.powsybl.openloadflow.ac.AcOuterLoopContext;
import com.powsybl.openloadflow.lf.outerloop.OuterLoopResult;
import com.powsybl.openloadflow.lf.outerloop.OuterLoopStatus;
import com.powsybl.openloadflow.network.GeneratorVoltageControl;
import com.powsybl.openloadflow.network.LfBus;
import com.powsybl.openloadflow.network.LfNetwork;
import com.powsybl.openloadflow.network.VoltageControl;
import com.powsybl.openloadflow.util.PerUnit;
import com.powsybl.openloadflow.util.Reports;
import org.apache.commons.lang3.mutable.MutableInt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.Predicate;

/**
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
public class ReactiveLimitsOuterLoop implements AcOuterLoop {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReactiveLimitsOuterLoop.class);

    public static final String NAME = "ReactiveLimits";

    private static final double REALISTIC_VOLTAGE_MARGIN = 1.02;

    private static final Comparator<ControllerBusToPqBus> BY_NOMINAL_V_COMPARATOR = Comparator.comparingDouble(
        controllerBusToPqBus -> controllerBusToPqBus.controllerBus.getGeneratorVoltageControl()
            .map(vc -> -vc.getControlledBus().getNominalV())
            .orElse(-controllerBusToPqBus.controllerBus.getNominalV()));

    private static final Comparator<ControllerBusToPqBus> BY_TARGET_P_COMPARATOR = Comparator.comparingDouble(controllerBusToPqBus -> -controllerBusToPqBus.controllerBus.getTargetP());

    private static final Comparator<ControllerBusToPqBus> BY_ID_COMPARATOR = Comparator.comparing(controllerBusToPqBus -> controllerBusToPqBus.controllerBus.getId());

    public static final int MAX_SWITCH_PQ_PV_DEFAULT_VALUE = 3;

    private final int maxPqPvSwitch;
    private final double maxReactivePowerMismatch;
    private final double minRealisticVoltage;
    private final double maxRealisticVoltage;
    private final boolean robustMode;

    public ReactiveLimitsOuterLoop(int maxPqPvSwitch, double maxReactivePowerMismatch, boolean robustMode, double minRealisticVoltage, double maxRealisticVoltage) {
        this.maxPqPvSwitch = maxPqPvSwitch;
        this.maxReactivePowerMismatch = maxReactivePowerMismatch;
        this.minRealisticVoltage = minRealisticVoltage;
        this.maxRealisticVoltage = maxRealisticVoltage;
        this.robustMode = robustMode;

    }

    private static final class ContextData {

        private final Map<String, MutableInt> pvPqSwitchCount = new HashMap<>();

        void incrementPvPqSwitchCount(String busId) {
            pvPqSwitchCount.computeIfAbsent(busId, k -> new MutableInt(0))
                    .increment();
        }

        int getPvPqSwitchCount(String busId) {
            MutableInt counter = pvPqSwitchCount.get(busId);
            if (counter == null) {
                return 0;
            }
            return counter.getValue();
        }
    }

    @Override
    public String getName() {
        return NAME;
    }

    private static final class ControllerBusToPqBus {

        private final LfBus controllerBus;

        private final double q;

        private final double qLimit;

        private final LfBus.QLimitType limitType;

        private ControllerBusToPqBus(LfBus controllerBus, double q, double qLimit, LfBus.QLimitType limitType) {
            this.controllerBus = controllerBus;
            this.q = q;
            this.qLimit = qLimit;
            this.limitType = limitType;
        }
    }

    private static final class PqToPvBus {

        private final LfBus controllerBus;

        private final LfBus.QLimitType limitType;

        private PqToPvBus(LfBus controllerBus, LfBus.QLimitType limitType) {
            this.controllerBus = controllerBus;
            this.limitType = limitType;
        }
    }

    private boolean switchPvPq(List<ControllerBusToPqBus> pvToPqBuses, int remainingPvBusCount, ContextData contextData,
                               ReportNode reportNode) {
        boolean done = false;

        int modifiedRemainingPvBusCount = remainingPvBusCount;
        if (modifiedRemainingPvBusCount == 0) {
            // keep one bus PV, the strongest one which is one at the highest nominal level and highest active power
            // target
            ControllerBusToPqBus strongestPvToPqBus = pvToPqBuses.stream()
                    .min(BY_NOMINAL_V_COMPARATOR
                            .thenComparing(BY_TARGET_P_COMPARATOR)
                            .thenComparing(BY_ID_COMPARATOR)) // for stability of the sort
                    .orElseThrow(IllegalStateException::new);
            pvToPqBuses.remove(strongestPvToPqBus);
            modifiedRemainingPvBusCount++;
            LOGGER.warn("All PV buses should switch PQ, strongest one '{}' will stay PV", strongestPvToPqBus.controllerBus.getId());
            Reports.reportBusForcedToBePv(reportNode, strongestPvToPqBus.controllerBus.getId());
        }

        if (!pvToPqBuses.isEmpty()) {
            done = true;

            ReportNode summary = Reports.reportPvToPqBuses(reportNode, pvToPqBuses.size(), modifiedRemainingPvBusCount);

            boolean log = LOGGER.isTraceEnabled();

            for (ControllerBusToPqBus pvToPqBus : pvToPqBuses) {
                LfBus controllerBus = pvToPqBus.controllerBus;

                // switch PV -> PQ
                controllerBus.setGenerationTargetQ(pvToPqBus.qLimit);
                controllerBus.setQLimitType(pvToPqBus.limitType);
                controllerBus.setGeneratorVoltageControlEnabled(false);
                // increment PV -> PQ switch counter
                contextData.incrementPvPqSwitchCount(controllerBus.getId());

                switch (pvToPqBus.limitType) {
                    case MAX_Q :
                        Reports.reportPvToPqMaxQ(summary, controllerBus, pvToPqBus.q, pvToPqBus.qLimit, log, LOGGER);
                        break;
                    case MIN_Q:
                        Reports.reportPvToPqMinQ(summary, controllerBus, pvToPqBus.q, pvToPqBus.qLimit, log, LOGGER);
                        break;
                    case MIN_REALISTIC_V:
                        Reports.reportPvToPqMinRealisticV(summary, controllerBus, pvToPqBus.qLimit, minRealisticVoltage, log, LOGGER);
                        break;
                    case MAX_REALISTIC_V:
                        Reports.reportPvToPqMaxRealisticV(summary, controllerBus, pvToPqBus.qLimit, maxRealisticVoltage, log, LOGGER);
                        break;
                }
            }

        }

        LOGGER.info("{} buses switched PV -> PQ ({} bus remains PV)", pvToPqBuses.size(), modifiedRemainingPvBusCount);

        return done;
    }

    @Override
    public void initialize(AcOuterLoopContext context) {
        context.setData(new ContextData());
    }

    private static boolean switchPqPv(List<PqToPvBus> pqToPvBuses, ContextData contextData, ReportNode reportNode, int maxPqPvSwitch) {
        int pqPvSwitchCount = 0;

        boolean log = LOGGER.isTraceEnabled();

        List<ReportNode> pqPvNodes = new ArrayList<>();

        for (PqToPvBus pqToPvBus : pqToPvBuses) {
            LfBus controllerBus = pqToPvBus.controllerBus;

            int pvPqSwitchCount = contextData.getPvPqSwitchCount(controllerBus.getId());
            if (pvPqSwitchCount >= maxPqPvSwitch) {
                LOGGER.trace("Bus '{}' blocked PQ as it has reach its max number of PQ -> PV switch ({})",
                        controllerBus.getId(), pvPqSwitchCount);
            } else {
                controllerBus.setGeneratorVoltageControlEnabled(true);
                controllerBus.setGenerationTargetQ(0);
                controllerBus.setQLimitType(null);
                pqPvSwitchCount++;


                if (pqToPvBus.limitType.isMaxLimit()) {
                    pqPvNodes.add(Reports.reportPqToPvBusMaxLimit(controllerBus,
                            controllerBus.getGeneratorVoltageControl().map(VoltageControl::getControlledBus).orElse(null),
                            getBusTargetV(controllerBus),
                            log,
                            LOGGER));
                } else {
                    pqPvNodes.add(Reports.reportPqToPvBusMinLimit(controllerBus,
                            controllerBus.getGeneratorVoltageControl().map(VoltageControl::getControlledBus).orElse(null),
                            getBusTargetV(controllerBus),
                            log,
                            LOGGER));
                }
            }

            ReportNode summary = Reports.reportPqToPvBuses(reportNode, pqPvSwitchCount, pqToPvBuses.size() - pqPvSwitchCount);
            pqPvNodes.forEach(n -> summary.include(n));
        }

        LOGGER.info("{} buses switched PQ -> PV ({} buses blocked PQ because have reach max number of switch)",
                pqPvSwitchCount, pqToPvBuses.size() - pqPvSwitchCount);

        return pqPvSwitchCount > 0;
    }

    /**
     * A controller bus can be a controller bus with voltage control (1) or with remote reactive control (2).
     * (1) A bus PV bus can be switched to PQ in 3 cases:
     *  - if Q equals to Qmax
     *  - if Q equals to Qmin
     *  - if Q equals targetQ, if the robust mode is activated, in the case of remote voltage control
     *                         and the bus exceeds realistic limits
     *                         without exceeding reactive limits when in voltage control.
     *  (2) A remote reactive controller can reach its Q limits: the control is switch off.
     */
    private void checkControllerBus(LfBus controllerBus,
                                           List<ControllerBusToPqBus> buses,
                                           MutableInt remainingUnchangedBusCount) {
        double minQ = controllerBus.getMinQ();
        double maxQ = controllerBus.getMaxQ();
        double q = controllerBus.getQ().eval() + controllerBus.getLoadTargetQ();

        boolean remainsPV = true;
        boolean generatorRemoteController = isGeneratorRemoteController(controllerBus);

        if (q < minQ) {
            buses.add(new ControllerBusToPqBus(controllerBus, q, minQ, LfBus.QLimitType.MIN_Q));
            remainsPV = false;
        } else if (q > maxQ) {
            buses.add(new ControllerBusToPqBus(controllerBus, q, maxQ, LfBus.QLimitType.MAX_Q));
            remainsPV = false;
        }

        if (robustMode && generatorRemoteController && !remainsPV && (isUnrealisticLowVoltage(controllerBus) || isUnrealisticHighVoltage(controllerBus))) {
            controllerBus.setV(1);
        }

        // If Q not out of bounds, check generator local voltage for remote voltage control, which is another criteria for blocking the generator
        if (robustMode && remainsPV && generatorRemoteController) {
            // At this point Q bounds are not reached and is still larger than what causes unrealistic voltage.
            // Just deactivate remote voltage control and set generation targetQ to initial value
            // Set V to a safe 1 p.u. for next computation
            if (isUnrealisticLowVoltage(controllerBus)) {
                controllerBus.setV(1);
                buses.add(new ControllerBusToPqBus(controllerBus, q, getInitialGenerationTargetQ(controllerBus), LfBus.QLimitType.MIN_REALISTIC_V));
                remainsPV = false;
            } else if (isUnrealisticHighVoltage(controllerBus)) {
                controllerBus.setV(1);
                buses.add(new ControllerBusToPqBus(controllerBus, q, getInitialGenerationTargetQ(controllerBus), LfBus.QLimitType.MAX_REALISTIC_V));
                remainsPV = false;
            }
        }

        if (remainsPV) {
            remainingUnchangedBusCount.increment();
        }
    }

    private double getInitialGenerationTargetQ(LfBus controllerBus) {
        return controllerBus.getGenerators().stream().mapToDouble(g -> g.getTargetQ()).sum();
    }

    private boolean isGeneratorRemoteController(LfBus controllerBus) {
        return controllerBus.getGeneratorVoltageControl().map(c -> c.getControlledBus() != controllerBus).orElse(false);
    }

    private boolean isUnrealisticLowVoltage(LfBus controllerBus) {
        return controllerBus.getV() < this.minRealisticVoltage * REALISTIC_VOLTAGE_MARGIN;
    }

    private boolean isUnrealisticHighVoltage(LfBus controllerBus) {
        return controllerBus.getV() > this.maxRealisticVoltage / REALISTIC_VOLTAGE_MARGIN;
    }

    /**
     * A PQ bus can be switched to PV in 2 cases:
     *  - if Q is equal to Qmin and V is less than targetV: it means that the PQ bus can be unlocked in order to increase the reactive power and reach its targetV.
     *  - if Q is equal to Qmax and V is greater than targetV: it means that the PQ bus can be unlocked in order to decrease the reactive power and reach its targetV.
     * A PQ bus can have its Qmin or Qmax limit updated after a change in targetP of the generator or a change of the voltage magnitude of the bus.
     */
    private void checkPqBus(LfBus controllerCapableBus, List<PqToPvBus> pqToPvBuses, List<LfBus> busesWithUpdatedQLimits,
                                   double maxReactivePowerMismatch, boolean canSwitchPqToPv) {
        double minQ = controllerCapableBus.getMinQ(); // the actual minQ.
        double maxQ = controllerCapableBus.getMaxQ(); // the actual maxQ.
        double q = controllerCapableBus.getGenerationTargetQ();
        controllerCapableBus.getQLimitType().ifPresent(qLimitType -> {
            if (qLimitType.isMinLimit()) {
                if (getBusV(controllerCapableBus) < getBusTargetV(controllerCapableBus) && canSwitchPqToPv) {
                    // bus absorb too much reactive power
                    pqToPvBuses.add(new PqToPvBus(controllerCapableBus, LfBus.QLimitType.MIN_Q));
                } else if (qLimitType == LfBus.QLimitType.MIN_Q && Math.abs(minQ - q) > maxReactivePowerMismatch) {
                    LOGGER.trace("PQ bus {} with updated Q limits, previous minQ {} new minQ {}", controllerCapableBus.getId(), q, minQ);
                    controllerCapableBus.setGenerationTargetQ(minQ);
                    busesWithUpdatedQLimits.add(controllerCapableBus);
                }
            } else if (qLimitType.isMaxLimit()) {
                if (getBusV(controllerCapableBus) > getBusTargetV(controllerCapableBus) && canSwitchPqToPv) {
                    // bus produce too much reactive power
                    pqToPvBuses.add(new PqToPvBus(controllerCapableBus, LfBus.QLimitType.MAX_Q));
                } else if (qLimitType == LfBus.QLimitType.MAX_Q && Math.abs(maxQ - q) > maxReactivePowerMismatch) {
                    LOGGER.trace("PQ bus {} with updated Q limits, previous maxQ {} new maxQ {}", controllerCapableBus.getId(), q, maxQ);
                    controllerCapableBus.setGenerationTargetQ(maxQ);
                    busesWithUpdatedQLimits.add(controllerCapableBus);
                }
            }
        });
    }

    private boolean switchReactiveControllerBusPq(List<ControllerBusToPqBus> reactiveControllerBusesToPqBuses, ReportNode reportNode) {
        int switchCount = 0;

        ReportNode node = Reports.reportReactiveControllerBusesToPqBuses(reportNode, switchCount);

        boolean log = LOGGER.isTraceEnabled();

        for (ControllerBusToPqBus bus : reactiveControllerBusesToPqBuses) {
            LfBus controllerBus = bus.controllerBus;

            controllerBus.setGeneratorReactivePowerControlEnabled(false);
            controllerBus.setGenerationTargetQ(bus.qLimit);
            switchCount++;

            switch (bus.limitType) {
                case MAX_Q:
                    Reports.reportReactiveControllerBusesToPqMaxQ(node, controllerBus, bus.q, bus.qLimit, log, LOGGER);
                    break;
                case MIN_Q:
                    LOGGER.trace("Remote reactive power controller bus '{}' -> PQ, q={} < minQ={}", controllerBus.getId(), bus.q * PerUnit.SB,
                            bus.qLimit * PerUnit.SB);
                    break;
                case MIN_REALISTIC_V, MAX_REALISTIC_V:
                    LOGGER.trace("Switch bus '{}' PV -> PQ, q set to {} = targetQ - v is outside realistic voltage limits [{}pu,{}pu] when remote voltage is maintained",
                            controllerBus.getId(), bus.qLimit * PerUnit.SB, minRealisticVoltage, maxRealisticVoltage);

                    break;
            }

        }

        LOGGER.info("{} remote reactive power controller buses switched PQ", switchCount);

        return switchCount > 0;
    }

    private static double getBusTargetV(LfBus bus) {
        return bus.getGeneratorVoltageControl().map(GeneratorVoltageControl::getTargetValue).orElse(Double.NaN);
    }

    private static double getBusV(LfBus bus) {
        return bus.getGeneratorVoltageControl().map(vc -> vc.getControlledBus().getV()).orElse(Double.NaN);
    }

    public static List<LfBus> getReactivePowerControllerElements(LfNetwork network) {
        return network.getBuses().stream()
                .filter(LfBus::hasGeneratorReactivePowerControl)
                .flatMap(bus -> bus.getGeneratorReactivePowerControl().orElseThrow().getControllerBuses().stream())
                .filter(Predicate.not(LfBus::isDisabled))
                .toList();
    }

    @Override
    public OuterLoopResult check(AcOuterLoopContext context, ReportNode reportNode) {
        OuterLoopStatus status = OuterLoopStatus.STABLE;

        List<ControllerBusToPqBus> pvToPqBuses = new ArrayList<>();
        List<PqToPvBus> pqToPvBuses = new ArrayList<>();
        List<LfBus> busesWithUpdatedQLimits = new ArrayList<>();
        MutableInt remainingPvBusCount = new MutableInt();
        List<ControllerBusToPqBus> reactiveControllerBusesToPqBuses = new ArrayList<>();
        MutableInt remainingBusWithReactivePowerControlCount = new MutableInt();

        context.getNetwork().<LfBus>getControllerElements(VoltageControl.Type.GENERATOR).forEach(bus -> {
            if (bus.isGeneratorVoltageControlEnabled()) {
                checkControllerBus(bus, pvToPqBuses, remainingPvBusCount);
            } else {
                // we don't support switching PQ to PV for bus with one controller with slope.
                checkPqBus(bus, pqToPvBuses, busesWithUpdatedQLimits, maxReactivePowerMismatch, !bus.hasGeneratorsWithSlope());
            }
        });

        getReactivePowerControllerElements(context.getNetwork()).forEach(bus -> {
            if (bus.isGeneratorReactivePowerControlEnabled()) {
                // a bus that has a remote reactive generator power control, if its reactive limits are not respected,
                // will become a classical PQ bus at reactive limits.
                checkControllerBus(bus, reactiveControllerBusesToPqBuses, remainingBusWithReactivePowerControlCount);
            }
        });

        var contextData = (ContextData) context.getData();

        ReportNode iterationReportNode = reportNode;
        if (!pvToPqBuses.isEmpty() || !pqToPvBuses.isEmpty() || !busesWithUpdatedQLimits.isEmpty() || !reactiveControllerBusesToPqBuses.isEmpty()) {
            iterationReportNode = Reports.createOuterLoopIterationReporter(reportNode, context.getOuterLoopTotalIterations() + 1);
        }

        if (!pvToPqBuses.isEmpty() && switchPvPq(pvToPqBuses, remainingPvBusCount.intValue(), contextData, iterationReportNode)) {
            status = OuterLoopStatus.UNSTABLE;
        }
        if (!pqToPvBuses.isEmpty() && switchPqPv(pqToPvBuses, contextData, iterationReportNode, maxPqPvSwitch)) {
            status = OuterLoopStatus.UNSTABLE;
        }
        if (!busesWithUpdatedQLimits.isEmpty()) {
            LOGGER.info("{} buses blocked at a reactive limit have been adjusted because the reactive limit changed", busesWithUpdatedQLimits.size());
            Reports.reportBusesWithUpdatedQLimits(iterationReportNode, busesWithUpdatedQLimits.size());
            status = OuterLoopStatus.UNSTABLE;
        }
        if (!reactiveControllerBusesToPqBuses.isEmpty() && switchReactiveControllerBusPq(reactiveControllerBusesToPqBuses, iterationReportNode)) {
            status = OuterLoopStatus.UNSTABLE;
        }
        return new OuterLoopResult(this, status);
    }

    @Override
    public boolean canFixUnrealisticState() {
        return true;
    }
}
