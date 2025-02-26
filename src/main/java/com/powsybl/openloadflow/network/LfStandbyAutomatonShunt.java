/**
 * Copyright (c) 2019, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.network;

import com.powsybl.openloadflow.util.PerUnit;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 * @author Anne Tilloy {@literal <anne.tilloy at rte-france.com>}
 */
public final class LfStandbyAutomatonShunt extends AbstractLfShunt {

    private final LfStaticVarCompensator svc;

    private double b;

    private LfStandbyAutomatonShunt(LfStaticVarCompensator svc) {
        super(svc.getBus(), svc.getBus().getNetwork());
        this.svc = svc;
        double zb = PerUnit.zb(svc.getBus().getNominalV());
        b = svc.getB0() * zb;
    }

    public static LfStandbyAutomatonShunt create(LfStaticVarCompensator svc) {
        return new LfStandbyAutomatonShunt(Objects.requireNonNull(svc));
    }

    @Override
    public ElementType getType() {
        return ElementType.SHUNT_COMPENSATOR;
    }

    @Override
    public String getId() {
        return svc.getId() + "_standby_automaton_b0";
    }

    @Override
    public List<String> getOriginalIds() {
        return List.of(svc.getOriginalId());
    }

    @Override
    public double getB() {
        return b;
    }

    @Override
    public void setB(double b) {
        if (b != this.b) {
            this.b = b;
            for (LfNetworkListener listener : getNetwork().getListeners()) {
                listener.onShuntSusceptanceChange(this, b);
            }
        }
    }

    private static UnsupportedOperationException createUnsupportedForStandbyAutomatonShuntException() {
        throw new UnsupportedOperationException("Unsupported for a SVC standby automaton shunt");
    }

    @Override
    public double getG() {
        return 0;
    }

    @Override
    public void setG(double g) {
        throw createUnsupportedForStandbyAutomatonShuntException();
    }

    @Override
    public boolean hasVoltageControlCapability() {
        return false;
    }

    @Override
    public void setVoltageControlCapability(boolean voltageControlCapability) {
        throw createUnsupportedForStandbyAutomatonShuntException();
    }

    @Override
    public boolean isVoltageControlEnabled() {
        return false;
    }

    @Override
    public void setVoltageControlEnabled(boolean voltageControlEnabled) {
        throw createUnsupportedForStandbyAutomatonShuntException();
    }

    @Override
    public Optional<ShuntVoltageControl> getVoltageControl() {
        return Optional.empty();
    }

    @Override
    public void setVoltageControl(ShuntVoltageControl voltageControl) {
        throw createUnsupportedForStandbyAutomatonShuntException();
    }

    @Override
    public double dispatchB() {
        throw createUnsupportedForStandbyAutomatonShuntException();
    }

    @Override
    public void updateState(LfNetworkStateUpdateParameters parameters) {
        // nothing to do
    }

    @Override
    public void reInit() {
        // nothing to do
    }

    @Override
    public List<Controller> getControllers() {
        return Collections.emptyList();
    }
}
