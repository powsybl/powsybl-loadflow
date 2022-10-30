/**
 * Copyright (c) 2019, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.openloadflow.network.impl;

import com.powsybl.iidm.network.*;
import com.powsybl.openloadflow.network.LfNetwork;
import com.powsybl.openloadflow.network.LfVscConverterStation;
import com.powsybl.openloadflow.util.PerUnit;

import java.util.Objects;
import java.util.Optional;

/**
 * @author Geoffroy Jamgotchian <geoffroy.jamgotchian at rte-france.com>
 */
public class LfVscConverterStationImpl extends AbstractLfGenerator implements LfVscConverterStation {

    private final Ref<VscConverterStation> stationRef;

    private final double lossFactor;

    public LfVscConverterStationImpl(VscConverterStation station, LfNetwork network, boolean breakers, boolean reactiveLimits, LfNetworkLoadingReport report,
                                     double minPlausibleTargetVoltage, double maxPlausibleTargetVoltage) {
        super(network, HvdcConverterStations.getConverterStationTargetP(station));
        this.stationRef = new Ref<>(station);
        this.lossFactor = station.getLossFactor();

        // local control only
        if (station.isVoltageRegulatorOn()) {
            setVoltageControl(station.getVoltageSetpoint(), station.getTerminal(), station.getRegulatingTerminal(),
                    breakers, reactiveLimits, report, minPlausibleTargetVoltage, maxPlausibleTargetVoltage);
        }
    }

    public static LfVscConverterStationImpl create(VscConverterStation station, LfNetwork network, boolean breakers, boolean reactiveLimits, LfNetworkLoadingReport report,
                                                   double minPlausibleTargetVoltage, double maxPlausibleTargetVoltage) {
        Objects.requireNonNull(station);
        return new LfVscConverterStationImpl(station, network, breakers, reactiveLimits, report, minPlausibleTargetVoltage, maxPlausibleTargetVoltage);
    }

    private VscConverterStation getStation() {
        return stationRef.get();
    }

    @Override
    public double getLossFactor() {
        return lossFactor;
    }

    @Override
    public String getId() {
        return getStation().getId();
    }

    @Override
    public double getTargetQ() {
        return getStation().getReactivePowerSetpoint() / PerUnit.SB;
    }

    @Override
    public double getMinP() {
        return -getStation().getHvdcLine().getMaxP() / PerUnit.SB;
    }

    @Override
    public double getMaxP() {
        return getStation().getHvdcLine().getMaxP() / PerUnit.SB;
    }

    @Override
    protected Optional<ReactiveLimits> getReactiveLimits() {
        return Optional.of(getStation().getReactiveLimits());
    }

    @Override
    public void updateState() {
        var station = getStation();
        station.getTerminal()
                .setP(-targetP)
                .setQ(Double.isNaN(calculatedQ) ? -station.getReactivePowerSetpoint() : -calculatedQ);
    }
}
