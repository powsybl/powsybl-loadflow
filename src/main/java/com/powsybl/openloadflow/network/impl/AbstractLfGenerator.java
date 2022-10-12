/**
 * Copyright (c) 2019, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.openloadflow.network.impl;

import com.powsybl.iidm.network.*;
import com.powsybl.openloadflow.network.*;
import com.powsybl.openloadflow.util.PerUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * @author Geoffroy Jamgotchian <geoffroy.jamgotchian at rte-france.com>
 */
public abstract class AbstractLfGenerator extends AbstractPropertyBag implements LfGenerator {

    private static final Logger LOGGER = LoggerFactory.getLogger(AbstractLfGenerator.class);

    private static final double POWER_EPSILON_SI = 1e-4;

    protected static final double DEFAULT_DROOP = 4; // why not

    protected double targetP;

    protected LfBus bus;

    protected double calculatedQ = Double.NaN;

    protected double targetV = Double.NaN;

    protected GeneratorControlType generatorControlType = GeneratorControlType.OFF;

    protected String controlledBusId;

    protected String controlledBranchId;

    protected ReactivePowerControl.ControlledSide controlledBranchSide;

    protected double remoteTargetQ = Double.NaN;

    protected AbstractLfGenerator(double targetP) {
        this.targetP = targetP;
    }

    @Override
    public String getOriginalId() {
        return getId();
    }

    public LfBus getBus() {
        return bus;
    }

    public void setBus(LfBus bus) {
        this.bus = bus;
    }

    @Override
    public boolean isFictitious() {
        return false;
    }

    @Override
    public double getTargetP() {
        return targetP / PerUnit.SB;
    }

    @Override
    public void setTargetP(double targetP) {
        double newTargetP = targetP * PerUnit.SB;
        if (newTargetP != this.targetP) {
            double oldTargetP = this.targetP;
            this.targetP = newTargetP;
            for (LfNetworkListener listener : bus.getNetwork().getListeners()) {
                listener.onGenerationActivePowerTargetChange(this, oldTargetP, newTargetP);
            }
        }
    }

    @Override
    public double getTargetV() {
        return targetV;
    }

    @Override
    public GeneratorControlType getGeneratorControlType() {
        return generatorControlType;
    }

    @Override
    public void setGeneratorControlType(GeneratorControlType generatorControlType) {
        this.generatorControlType = Objects.requireNonNull(generatorControlType);
    }

    @Override
    public boolean hasRemoteReactivePowerControl() {
        return generatorControlType == GeneratorControlType.REMOTE_REACTIVE_POWER;
    }

    @Override
    public OptionalDouble getRemoteControlReactiveKey() {
        return OptionalDouble.empty();
    }

    protected abstract Optional<ReactiveLimits> getReactiveLimits();

    @Override
    public double getMinQ() {
        return getReactiveLimits()
                .map(limits -> limits.getMinQ(targetP) / PerUnit.SB)
                .orElse(-Double.MAX_VALUE);
    }

    @Override
    public double getMaxQ() {
        return getReactiveLimits()
                .map(limits -> limits.getMaxQ(targetP) / PerUnit.SB)
                .orElse(Double.MAX_VALUE);
    }

    @Override
    public double getMaxRangeQ() {
        double maxRangeQ = Double.NaN;
        ReactiveLimits reactiveLimits = getReactiveLimits().orElse(null);
        if (reactiveLimits != null) {
            switch (reactiveLimits.getKind()) {
                case CURVE:
                    ReactiveCapabilityCurve reactiveCapabilityCurve = (ReactiveCapabilityCurve) reactiveLimits;
                    for (ReactiveCapabilityCurve.Point point : reactiveCapabilityCurve.getPoints()) {
                        if (Double.isNaN(maxRangeQ)) {
                            maxRangeQ = point.getMaxQ() - point.getMinQ();
                        } else {
                            maxRangeQ = Math.max(maxRangeQ, point.getMaxQ() - point.getMinQ());
                        }
                    }
                    break;

                case MIN_MAX:
                    MinMaxReactiveLimits minMaxReactiveLimits = (MinMaxReactiveLimits) reactiveLimits;
                    maxRangeQ = minMaxReactiveLimits.getMaxQ() - minMaxReactiveLimits.getMinQ();
                    break;

                default:
                    throw new IllegalStateException("Unknown reactive limits kind: " + reactiveLimits.getKind());
            }
            return maxRangeQ / PerUnit.SB;
        } else {
            return Double.MAX_VALUE;
        }
    }

    @Override
    public double getCalculatedQ() {
        return calculatedQ / PerUnit.SB;
    }

    @Override
    public void setCalculatedQ(double calculatedQ) {
        this.calculatedQ = calculatedQ * PerUnit.SB;
    }

    @Override
    public LfBus getControlledBus(LfNetwork lfNetwork) {
        return lfNetwork.getBusById(controlledBusId);
    }

    protected void setVoltageControl(double targetV, Terminal terminal, Terminal regulatingTerminal, boolean breakers,
                                     boolean reactiveLimits, LfNetworkLoadingReport report, double minPlausibleTargetVoltage,
                                     double maxPlausibleTargetVoltage) {
        if (!checkVoltageControlConsistency(reactiveLimits, report)) {
            return;
        }
        Bus controlledBus = breakers ? regulatingTerminal.getBusBreakerView().getBus() : regulatingTerminal.getBusView().getBus();
        if (controlledBus == null) {
            LOGGER.warn("Regulating terminal of LfGenerator {} is out of voltage: voltage control discarded", getId());
            return;
        }
        boolean inSameSynchronousComponent = breakers
                ? regulatingTerminal.getBusBreakerView().getBus().getSynchronousComponent().getNum() == terminal.getBusBreakerView().getBus().getSynchronousComponent().getNum()
                : regulatingTerminal.getBusView().getBus().getSynchronousComponent().getNum() == terminal.getBusView().getBus().getSynchronousComponent().getNum();
        if (!inSameSynchronousComponent) {
            LOGGER.warn("Regulating terminal of LfGenerator {} is not in the same synchronous component: voltage control discarded", getId());
            return;
        }
        if (!checkTargetV(targetV / regulatingTerminal.getVoltageLevel().getNominalV(), report, minPlausibleTargetVoltage, maxPlausibleTargetVoltage)) {
            return;
        }
        this.controlledBusId = controlledBus.getId();
        this.targetV = targetV / regulatingTerminal.getVoltageLevel().getNominalV();
        this.generatorControlType = GeneratorControlType.VOLTAGE;
    }

    protected boolean checkVoltageControlConsistency(boolean reactiveLimits, LfNetworkLoadingReport report) {
        boolean consistency = true;
        if (reactiveLimits) {
            double maxRangeQ = getMaxRangeQ();
            if (maxRangeQ < PlausibleValues.MIN_REACTIVE_RANGE / PerUnit.SB) {
                LOGGER.trace("Discard generator '{}' from voltage control because max reactive range ({}) is too small", getId(), maxRangeQ);
                report.generatorsDiscardedFromVoltageControlBecauseMaxReactiveRangeIsTooSmall++;
                consistency = false;
            }
        }
        if (Math.abs(getTargetP()) < POWER_EPSILON_SI && getMinP() > 0) {
            LOGGER.trace("Discard generator '{}' from voltage control because not started (targetP={} MW, minP={} MW)", getId(), getTargetP(), getMinP());
            report.generatorsDiscardedFromVoltageControlBecauseNotStarted++;
            consistency = false;
        }
        if (getTargetP() < getMinP() && getMinP() > 0) {
            LOGGER.trace("Discard starting generator '{}' from voltage control (targetP={} MW, minP={} MW)", getId(), getTargetP(), getMinP());
            report.generatorsDiscardedFromVoltageControlBecauseStarting++;
            consistency = false;
        }
        return consistency;
    }

    protected boolean checkTargetV(double targetV, LfNetworkLoadingReport report, double minPlausibleTargetVoltage,
                                double maxPlausibleTargetVoltage) {
        // check that targetV has a plausible value (wrong nominal voltage issue)
        if (targetV < minPlausibleTargetVoltage) {
            LOGGER.trace("Generator '{}' has an inconsistent target voltage: {} pu. The target voltage is limited to {}",
                getId(), targetV, minPlausibleTargetVoltage);
            report.generatorsWithInconsistentTargetVoltage++;
            return false;
        } else if (targetV > maxPlausibleTargetVoltage) {
            LOGGER.trace("Generator '{}' has an inconsistent target voltage: {} pu. The target voltage is limited to {}",
                getId(), targetV, maxPlausibleTargetVoltage);
            report.generatorsWithInconsistentTargetVoltage++;
            return false;
        }
        return true;
    }

    protected void setReactivePowerControl(Terminal regulatingTerminal, double targetQ) {
        Connectable<?> connectable = regulatingTerminal.getConnectable();
        if (connectable instanceof Line) {
            Line l = (Line) connectable;
            this.controlledBranchSide = l.getTerminal(Branch.Side.ONE) == regulatingTerminal ?
                    ReactivePowerControl.ControlledSide.ONE : ReactivePowerControl.ControlledSide.TWO;
            this.controlledBranchId = l.getId();
        } else if (connectable instanceof TwoWindingsTransformer) {
            TwoWindingsTransformer l = (TwoWindingsTransformer) connectable;
            this.controlledBranchSide = l.getTerminal(Branch.Side.ONE) == regulatingTerminal ?
                    ReactivePowerControl.ControlledSide.ONE : ReactivePowerControl.ControlledSide.TWO;
            this.controlledBranchId = l.getId();
        } else {
            LOGGER.error("Generator '{}' is controlled by an instance of {}: not supported",
                    getId(), connectable.getClass());
            return;
        }
        this.generatorControlType = GeneratorControlType.REMOTE_REACTIVE_POWER;
        this.remoteTargetQ = targetQ / PerUnit.SB;
    }

    @Override
    public LfBranch getControlledBranch(LfNetwork lfNetwork) {
        return lfNetwork.getBranchById(controlledBranchId);
    }

    @Override
    public ReactivePowerControl.ControlledSide getControlledBranchSide() {
        return controlledBranchSide;
    }

    @Override
    public double getRemoteTargetQ() {
        return remoteTargetQ;
    }

    @Override
    public void setParticipating(boolean participating) {
        // nothing to do
    }

    protected boolean checkActivePowerControl(double targetP, double minP, double maxP, double plausibleActivePowerLimit,
                                              LfNetworkLoadingReport report) {
        boolean participating = true;
        if (Math.abs(targetP) < POWER_EPSILON_SI) {
            LOGGER.trace("Discard generator '{}' from active power control because targetP ({}) equals 0",
                    getId(), targetP);
            report.generatorsDiscardedFromActivePowerControlBecauseTargetEqualsToZero++;
            participating = false;
        }
        if (targetP > maxP) {
            LOGGER.trace("Discard generator '{}' from active power control because targetP ({}) > maxP ({})",
                    getId(), targetP, maxP);
            report.generatorsDiscardedFromActivePowerControlBecauseTargetPGreaterThanMaxP++;
            participating = false;
        }
        if (targetP < minP && minP > 0) {
            LOGGER.trace("Discard generator '{}' from active power control because targetP ({}) < minP ({})",
                    getId(), targetP, minP);
            report.generatorsDiscardedFromActivePowerControlBecauseTargetPLowerThanMinP++;
            participating = false;
        }
        if (maxP > plausibleActivePowerLimit) {
            LOGGER.trace("Discard generator '{}' from active power control because maxP ({}) > {}} MW",
                    getId(), maxP, plausibleActivePowerLimit);
            report.generatorsDiscardedFromActivePowerControlBecauseMaxPNotPlausible++;
            participating = false;
        }
        if ((maxP - minP) < POWER_EPSILON_SI) {
            LOGGER.trace("Discard generator '{}' from active power control because maxP ({} MW) equals minP ({} MW)",
                    getId(), maxP, minP);
            report.generatorsDiscardedFromActivePowerControlBecauseMaxPEqualsMinP++;
            participating = false;
        }
        return participating;
    }

    @Override
    public String toString() {
        return getId();
    }
}
