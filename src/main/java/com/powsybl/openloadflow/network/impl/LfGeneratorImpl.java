/**
 * Copyright (c) 2019, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.openloadflow.network.impl;

import com.powsybl.iidm.network.Generator;
import com.powsybl.iidm.network.ReactiveLimits;
import com.powsybl.iidm.network.extensions.ActivePowerControl;
import com.powsybl.iidm.network.extensions.CoordinatedReactiveControl;
import com.powsybl.iidm.network.extensions.RemoteReactivePowerControl;
import com.powsybl.openloadflow.network.LfNetwork;
import com.powsybl.openloadflow.util.PerUnit;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * @author Geoffroy Jamgotchian <geoffroy.jamgotchian at rte-france.com>
 */
public final class LfGeneratorImpl extends AbstractLfGenerator {

    private final Ref<Generator> generatorRef;

    private boolean participating;

    private double droop;

    private LfGeneratorImpl(Generator generator, LfNetwork network, boolean breakers, double plausibleActivePowerLimit, boolean reactiveLimits,
                            LfNetworkLoadingReport report, double minPlausibleTargetVoltage, double maxPlausibleTargetVoltage) {
        super(network, generator.getTargetP());
        this.generatorRef = new Ref<>(generator);
        participating = true;
        droop = DEFAULT_DROOP;
        // get participation factor from extension
        ActivePowerControl<Generator> activePowerControl = generator.getExtension(ActivePowerControl.class);
        if (activePowerControl != null) {
            participating = activePowerControl.isParticipate() && activePowerControl.getDroop() != 0;
            if (activePowerControl.getDroop() != 0) {
                droop = activePowerControl.getDroop();
            }
        }

        if (!checkActivePowerControl(generator.getTargetP(), generator.getMinP(), generator.getMaxP(), plausibleActivePowerLimit, report)) {
            participating = false;
        }

        if (generator.isVoltageRegulatorOn()) {
            setVoltageControl(generator.getTargetV(), generator.getTerminal(), generator.getRegulatingTerminal(), breakers,
                    reactiveLimits, report, minPlausibleTargetVoltage, maxPlausibleTargetVoltage);
        }

        RemoteReactivePowerControl reactivePowerControl = generator.getExtension(RemoteReactivePowerControl.class);
        if (reactivePowerControl != null && reactivePowerControl.isEnabled() && !generator.isVoltageRegulatorOn()) {
            setReactivePowerControl(reactivePowerControl.getRegulatingTerminal(), reactivePowerControl.getTargetQ());
        }
    }

    public static LfGeneratorImpl create(Generator generator, LfNetwork network, boolean breakers, double plausibleActivePowerLimit,
                                         boolean reactiveLimits, LfNetworkLoadingReport report, double minPlausibleTargetVoltage,
                                         double maxPlausibleTargetVoltage) {
        Objects.requireNonNull(generator);
        Objects.requireNonNull(report);
        return new LfGeneratorImpl(generator, network, breakers, plausibleActivePowerLimit, reactiveLimits, report, minPlausibleTargetVoltage, maxPlausibleTargetVoltage);
    }

    private Generator getGenerator() {
        return generatorRef.get();
    }

    @Override
    public String getId() {
        return getGenerator().getId();
    }

    @Override
    public boolean isFictitious() {
        return getGenerator().isFictitious();
    }

    @Override
    public OptionalDouble getRemoteControlReactiveKey() {
        CoordinatedReactiveControl coordinatedReactiveControl = getGenerator().getExtension(CoordinatedReactiveControl.class);
        if (coordinatedReactiveControl == null) {
            return OptionalDouble.empty();
        }
        return OptionalDouble.of(coordinatedReactiveControl.getQPercent());
    }

    @Override
    public double getTargetQ() {
        return getGenerator().getTargetQ() / PerUnit.SB;
    }

    @Override
    public double getMinP() {
        return getGenerator().getMinP() / PerUnit.SB;
    }

    @Override
    public double getMaxP() {
        return getGenerator().getMaxP() / PerUnit.SB;
    }

    @Override
    protected Optional<ReactiveLimits> getReactiveLimits() {
        return Optional.of(getGenerator().getReactiveLimits());
    }

    @Override
    public boolean isParticipating() {
        return participating;
    }

    @Override
    public void setParticipating(boolean participating) {
        this.participating = participating;
    }

    @Override
    public double getDroop() {
        return droop;
    }

    @Override
    public void updateState() {
        var generator = getGenerator();
        generator.getTerminal()
                .setP(-targetP)
                .setQ(Double.isNaN(calculatedQ) ? -generator.getTargetQ() : -calculatedQ);
    }
}
