/**
 * Copyright (c) 2019, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.openloadflow.network.impl;

import com.powsybl.iidm.network.MinMaxReactiveLimits;
import com.powsybl.iidm.network.ReactiveLimits;
import com.powsybl.iidm.network.ReactiveLimitsKind;
import com.powsybl.iidm.network.StaticVarCompensator;
import com.powsybl.iidm.network.extensions.StandbyAutomaton;
import com.powsybl.iidm.network.extensions.VoltagePerReactivePowerControl;
import com.powsybl.openloadflow.OpenLoadFlowParameters;
import com.powsybl.openloadflow.network.LfNetwork;
import com.powsybl.openloadflow.network.LfStaticVarCompensator;
import com.powsybl.openloadflow.util.PerUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.Optional;

/**
 * @author Geoffroy Jamgotchian <geoffroy.jamgotchian at rte-france.com>
 */
public final class LfStaticVarCompensatorImpl extends AbstractLfGenerator implements LfStaticVarCompensator {

    protected static final Logger LOGGER = LoggerFactory.getLogger(LfStaticVarCompensatorImpl.class);

    private final Ref<StaticVarCompensator> svcRef;

    private final ReactiveLimits reactiveLimits;

    double nominalV;

    private double slope = 0;

    double targetQ = 0;

    private StandByAutomaton standByAutomaton;

    private double b0 = 0.0;

    private LfStaticVarCompensatorImpl(StaticVarCompensator svc, LfNetwork network, AbstractLfBus bus, boolean voltagePerReactivePowerControl,
                                       boolean breakers, boolean reactiveLimits, LfNetworkLoadingReport report,
                                       double minPlausibleTargetVoltage, double maxPlausibleTargetVoltage, OpenLoadFlowParameters.ReactiveRangeCheckMode reactiveRangeCheckMode) {
        super(network, 0);
        this.svcRef = new Ref<>(svc);
        this.nominalV = svc.getTerminal().getVoltageLevel().getNominalV();
        this.reactiveLimits = new MinMaxReactiveLimits() {

            @Override
            public double getMinQ() {
                double v = bus.getV() * nominalV;
                return svcRef.get().getBmin() * v * v;
            }

            @Override
            public double getMaxQ() {
                double v = bus.getV() * nominalV;
                return svcRef.get().getBmax() * v * v;
            }

            @Override
            public ReactiveLimitsKind getKind() {
                return ReactiveLimitsKind.MIN_MAX;
            }

            @Override
            public double getMinQ(double p) {
                return getMinQ();
            }

            @Override
            public double getMaxQ(double p) {
                return getMaxQ();
            }
        };

        if (svc.getRegulationMode() == StaticVarCompensator.RegulationMode.VOLTAGE) {
            setVoltageControl(svc.getVoltageSetpoint(), svc.getTerminal(), svc.getRegulatingTerminal(), breakers,
                    reactiveLimits, report, minPlausibleTargetVoltage, maxPlausibleTargetVoltage, reactiveRangeCheckMode);
            if (voltagePerReactivePowerControl && svc.getExtension(VoltagePerReactivePowerControl.class) != null) {
                if (svc.getExtension(StandbyAutomaton.class) == null) {
                    this.slope = svc.getExtension(VoltagePerReactivePowerControl.class).getSlope() * PerUnit.SB / nominalV;
                } else {
                    LOGGER.warn("Static var compensator {} has VoltagePerReactivePowerControl" +
                            " and StandbyAutomaton extensions: VoltagePerReactivePowerControl extension ignored", svc.getId());
                }
            }
            StandbyAutomaton standbyAutomaton = svc.getExtension(StandbyAutomaton.class);
            if (standbyAutomaton != null) {
                if (standbyAutomaton.getB0() != 0.0) {
                    // a static var compensator with an extension stand by automaton includes an offset of B0,
                    // whatever it is in stand by or not.
                    b0 = standbyAutomaton.getB0();
                }
                if (standbyAutomaton.isStandby()) {
                    standByAutomaton = new StandByAutomaton(standbyAutomaton.getHighVoltageThreshold() / nominalV,
                                                            standbyAutomaton.getLowVoltageThreshold() / nominalV,
                                                            standbyAutomaton.getHighVoltageSetpoint() / nominalV,
                                                            standbyAutomaton.getLowVoltageSetpoint() / nominalV);
                    generatorControlType = GeneratorControlType.MONITORING_VOLTAGE;
                }
            }
        }
        if (svc.getRegulationMode() == StaticVarCompensator.RegulationMode.REACTIVE_POWER) {
            targetQ = -svc.getReactivePowerSetpoint();
        }
    }

    public static LfStaticVarCompensatorImpl create(StaticVarCompensator svc, LfNetwork network, AbstractLfBus bus, boolean voltagePerReactivePowerControl,
                                                    boolean breakers, boolean reactiveLimits, LfNetworkLoadingReport report,
                                                    double minPlausibleTargetVoltage, double maxPlausibleTargetVoltage, OpenLoadFlowParameters.ReactiveRangeCheckMode reactiveRangeCheckMode) {
        Objects.requireNonNull(svc);
        return new LfStaticVarCompensatorImpl(svc, network, bus, voltagePerReactivePowerControl, breakers, reactiveLimits,
                report, minPlausibleTargetVoltage, maxPlausibleTargetVoltage, reactiveRangeCheckMode);
    }

    private StaticVarCompensator getSvc() {
        return svcRef.get();
    }

    @Override
    public String getId() {
        return getSvc().getId();
    }

    @Override
    public double getTargetQ() {
        return targetQ / PerUnit.SB;
    }

    @Override
    public double getMinP() {
        return -Double.MAX_VALUE;
    }

    @Override
    public double getMaxP() {
        return Double.MAX_VALUE;
    }

    @Override
    protected Optional<ReactiveLimits> getReactiveLimits() {
        return Optional.of(reactiveLimits);
    }

    @Override
    public void updateState() {
        double vSquare = bus.getV() * bus.getV() * nominalV * nominalV;
        double newTargetQ = Double.isNaN(targetQ) ? 0 : -targetQ;
        double q = Double.isNaN(calculatedQ) ? newTargetQ : -calculatedQ;
        getSvc().getTerminal()
                .setP(0)
                .setQ(q - b0 * vSquare);
    }

    @Override
    public double getSlope() {
        return this.slope;
    }

    @Override
    public void setSlope(double slope) {
        this.slope = slope;
    }

    @Override
    public double getB0() {
        return b0;
    }

    @Override
    public Optional<StandByAutomaton> getStandByAutomaton() {
        return Optional.ofNullable(standByAutomaton);
    }
}
