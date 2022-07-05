/**
 * Copyright (c) 2019, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.openloadflow.network.impl;

import com.powsybl.iidm.network.*;
import com.powsybl.openloadflow.network.*;
import com.powsybl.openloadflow.util.Evaluable;
import com.powsybl.openloadflow.util.PerUnit;
import org.apache.commons.math3.util.FastMath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * @author Geoffroy Jamgotchian <geoffroy.jamgotchian at rte-france.com>
 */
public abstract class AbstractLfBranch extends AbstractElement implements LfBranch {

    private static final Logger LOGGER = LoggerFactory.getLogger(AbstractLfBranch.class);

    private final LfBus bus1;

    private final LfBus bus2;

    private final Map<LimitType, List<LfLimit>> limits1 = new EnumMap<>(LimitType.class);

    private final Map<LimitType, List<LfLimit>> limits2 = new EnumMap<>(LimitType.class);

    protected final PiModel piModel;

    protected DiscretePhaseControl discretePhaseControl;

    protected boolean phaseControlEnabled = false;

    protected TransformerVoltageControl voltageControl;

    protected double targetDeadBand;

    protected boolean voltageControlEnabled = false;

    protected boolean spanningTreeEdge = false;

    protected Evaluable a1;

    private ReactivePowerControl reactivePowerControl;

    protected AbstractLfBranch(LfNetwork network, LfBus bus1, LfBus bus2, PiModel piModel) {
        super(network);
        this.bus1 = bus1;
        this.bus2 = bus2;
        this.piModel = Objects.requireNonNull(piModel);
        this.piModel.setBranch(this);
    }

    protected static List<LfLimit> createSortedLimitsList(LoadingLimits loadingLimits, LfBus bus) {
        LinkedList<LfLimit> sortedLimits = new LinkedList<>();
        if (loadingLimits != null) {
            double toPerUnit = getScaleForLimitType(loadingLimits.getLimitType(), bus);

            for (LoadingLimits.TemporaryLimit temporaryLimit : loadingLimits.getTemporaryLimits()) {
                if (temporaryLimit.getAcceptableDuration() != 0) {
                    // it is not useful to add a limit with acceptable duration equal to zero as the only value plausible
                    // for this limit is infinity.
                    // https://javadoc.io/doc/com.powsybl/powsybl-core/latest/com/powsybl/iidm/network/CurrentLimits.html
                    double valuePerUnit = temporaryLimit.getValue() * toPerUnit;
                    sortedLimits.addFirst(LfLimit.createTemporaryLimit(temporaryLimit.getAcceptableDuration(), valuePerUnit));
                }
            }
            sortedLimits.addLast(LfLimit.createPermanentLimit(loadingLimits.getPermanentLimit() * toPerUnit));
        }
        if (sortedLimits.size() > 1) {
            // we only make that fix if there is more than a permanent limit attached to the branch.
            for (int i = sortedLimits.size() - 1; i > 0; i--) {
                // From the permanent limit to the most serious temporary limit.
                sortedLimits.get(i).setAcceptableDuration(sortedLimits.get(i - 1).getAcceptableDuration());
            }
            sortedLimits.getFirst().setAcceptableDuration(0);
        }
        return sortedLimits;
    }

    @Override
    public ElementType getType() {
        return ElementType.BRANCH;
    }

    @Override
    public LfBus getBus1() {
        return bus1;
    }

    @Override
    public LfBus getBus2() {
        return bus2;
    }

    public List<LfLimit> getLimits1(LimitType type, LoadingLimits loadingLimits) {
        return limits1.computeIfAbsent(type, v -> createSortedLimitsList(loadingLimits, bus1));
    }

    public List<LfLimit> getLimits2(LimitType type, LoadingLimits loadingLimits) {
        return limits2.computeIfAbsent(type, v -> createSortedLimitsList(loadingLimits, bus2));
    }

    @Override
    public PiModel getPiModel() {
        return piModel;
    }

    @Override
    public Optional<DiscretePhaseControl> getDiscretePhaseControl() {
        return Optional.ofNullable(discretePhaseControl);
    }

    @Override
    public void setDiscretePhaseControl(DiscretePhaseControl discretePhaseControl) {
        this.discretePhaseControl = discretePhaseControl;
    }

    @Override
    public boolean isPhaseController() {
        return discretePhaseControl != null && discretePhaseControl.getController() == this;
    }

    @Override
    public boolean isPhaseControlled() {
        return discretePhaseControl != null && discretePhaseControl.getControlled() == this;
    }

    @Override
    public boolean isPhaseControlEnabled() {
        return phaseControlEnabled;
    }

    @Override
    public void setPhaseControlEnabled(boolean phaseControlEnabled) {
        if (this.phaseControlEnabled != phaseControlEnabled) {
            this.phaseControlEnabled = phaseControlEnabled;
            for (LfNetworkListener listener : network.getListeners()) {
                listener.onTransformerPhaseControlChange(this, phaseControlEnabled);
            }
        }
    }

    protected void updateTapPosition(PhaseTapChanger ptc) {
        int tapPosition = Transformers.findTapPosition(ptc, Math.toDegrees(getPiModel().getA1()));
        ptc.setTapPosition(tapPosition);
    }

    protected void updateTapPosition(RatioTapChanger rtc, double ptcRho, double rho) {
        int tapPosition = Transformers.findTapPosition(rtc, ptcRho, rho);
        rtc.setTapPosition(tapPosition);
    }

    protected void checkTargetDeadband(double p) {
        double distance = Math.abs(p - discretePhaseControl.getTargetValue()); // in per unit system
        if (distance > discretePhaseControl.getTargetDeadband() / 2) {
            LOGGER.warn("The active power on side {} of branch {} ({} MW) is out of the target value ({} MW) +/- deadband/2 ({} MW)",
                    discretePhaseControl.getControlledSide(), getId(), Math.abs(p) * PerUnit.SB,
                    discretePhaseControl.getTargetValue() * PerUnit.SB, discretePhaseControl.getTargetDeadband() / 2 * PerUnit.SB);
        }
    }

    protected void checkTargetDeadband(RatioTapChanger rtc) {
        if (rtc.getTargetDeadband() != 0) {
            double nominalV = rtc.getRegulationTerminal().getVoltageLevel().getNominalV();
            double v = voltageControl.getControlled().getV();
            double distance = Math.abs(v - voltageControl.getTargetValue()); // in per unit system
            if (distance > rtc.getTargetDeadband() / 2) {
                LOGGER.warn("The voltage on bus {} ({} kV) is out of the target value ({} kV) +/- deadband/2 ({} kV)",
                        voltageControl.getControlled().getId(), v * nominalV, rtc.getTargetV(), rtc.getTargetDeadband() / 2);
            }
        }
    }

    protected static double getScaleForLimitType(LimitType type, LfBus bus) {
        switch (type) {
            case ACTIVE_POWER:
            case APPARENT_POWER:
                return 1.0 / PerUnit.SB;
            case CURRENT:
                return 1.0 / PerUnit.ib(bus.getNominalV());
            case VOLTAGE:
            default:
                throw new UnsupportedOperationException(String.format("Getting scale for limit type %s is not supported.", type));
        }
    }

    @Override
    public Optional<TransformerVoltageControl> getVoltageControl() {
        return Optional.ofNullable(voltageControl);
    }

    @Override
    public Optional<Double> getTargetDeadBand() {
        return Optional.ofNullable(targetDeadBand);
    }

    @Override
    public boolean isVoltageController() {
        return voltageControl != null;
    }

    @Override
    public void setVoltageControl(TransformerVoltageControl transformerVoltageControl) {
        this.voltageControl = transformerVoltageControl;
    }

    @Override
    public void setDeadBand(double deadbandValue) {
        this.targetDeadBand = deadbandValue;
    }

    @Override
    public boolean isVoltageControlEnabled() {
        return voltageControlEnabled;
    }

    public void setVoltageControlEnabled(boolean voltageControlEnabled) {
        if (this.voltageControlEnabled != voltageControlEnabled) {
            this.voltageControlEnabled = voltageControlEnabled;
            for (LfNetworkListener listener : network.getListeners()) {
                listener.onTransformerVoltageControlChange(this, voltageControlEnabled);
            }
        }
    }

    public double computeApparentPower1() {
        double p = getP1().eval();
        double q = getQ1().eval();
        return FastMath.sqrt(p * p + q * q);
    }

    @Override
    public double computeApparentPower2() {
        double p = getP2().eval();
        double q = getQ2().eval();
        return FastMath.sqrt(p * p + q * q);
    }

    @Override
    public boolean isZeroImpedanceBranch(boolean dc) {
        if (dc) {
            return FastMath.abs(piModel.getX()) < LOW_IMPEDANCE_THRESHOLD;
        } else {
            return piModel.getZ() < LOW_IMPEDANCE_THRESHOLD;
        }
    }

    @Override
    public void setSpanningTreeEdge(boolean spanningTreeEdge) {
        this.spanningTreeEdge = spanningTreeEdge;
    }

    @Override
    public boolean isSpanningTreeEdge() {
        return this.spanningTreeEdge;
    }

    @Override
    public Evaluable getA1() {
        return a1;
    }

    @Override
    public void setA1(Evaluable a1) {
        this.a1 = a1;
    }

    public Optional<ReactivePowerControl> getReactivePowerControl() {
        return Optional.ofNullable(reactivePowerControl);
    }

    @Override
    public void setReactivePowerControl(ReactivePowerControl pReactivePowerControl) {
        this.reactivePowerControl = Objects.requireNonNull(pReactivePowerControl);
    }

    @Override
    public boolean isConnectedAtBothSides() {
        return bus1 != null && bus2 != null;
    }

    @Override
    public void setMinZ(boolean dc) {
        if (piModel.setMinZ(LOW_IMPEDANCE_THRESHOLD, dc)) {
            LOGGER.trace("Branch {} has a low impedance, set to min {}", getId(), LOW_IMPEDANCE_THRESHOLD);
        }
    }
}
