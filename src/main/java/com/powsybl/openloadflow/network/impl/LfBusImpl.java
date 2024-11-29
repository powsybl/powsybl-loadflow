/**
 * Copyright (c) 2019, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.network.impl;

import com.powsybl.iidm.network.*;
import com.powsybl.iidm.network.extensions.LoadAsymmetrical;
import com.powsybl.iidm.network.extensions.ReferenceTerminals;
import com.powsybl.iidm.network.extensions.SlackTerminal;
import com.powsybl.openloadflow.network.*;
import com.powsybl.openloadflow.util.PerUnit;
import com.powsybl.security.results.BusResult;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
public class LfBusImpl extends AbstractLfBus {

    private final Ref<Bus> busRef;

    private final double nominalV;

    private final double lowVoltageLimit;

    private final double highVoltageLimit;

    private final boolean participating;

    private final boolean breakers;

    private final Country country;

    private final List<String> bbsIds;

    private final double fictitiousInjectionTargetP;

    private final double fictitiousInjectionTargetQ;

    protected LfBusImpl(Bus bus, LfNetwork network, double v, double angle, LfNetworkParameters parameters,
                        boolean participating) {
        super(network, v, angle, parameters.isDistributedOnConformLoad());
        this.busRef = Ref.create(bus, parameters.isCacheEnabled());
        nominalV = bus.getVoltageLevel().getNominalV();
        lowVoltageLimit = bus.getVoltageLevel().getLowVoltageLimit();
        highVoltageLimit = bus.getVoltageLevel().getHighVoltageLimit();
        this.participating = participating;
        this.breakers = parameters.isBreakers();
        country = bus.getVoltageLevel().getSubstation().flatMap(Substation::getCountry).orElse(null);
        fictitiousInjectionTargetP = bus.getFictitiousP0();
        fictitiousInjectionTargetQ = bus.getFictitiousQ0();
        if (bus.getVoltageLevel().getTopologyKind() == TopologyKind.NODE_BREAKER) {
            bbsIds = bus.getConnectedTerminalStream()
                    .map(Terminal::getConnectable)
                    .filter(BusbarSection.class::isInstance)
                    .map(Connectable::getId)
                    .collect(Collectors.toList());
        } else {
            bbsIds = Collections.emptyList();
        }
    }

    private static void createAsym(Bus bus, LfBusImpl lfBus) {
        double totalDeltaPa = 0;
        double totalDeltaQa = 0;
        double totalDeltaPb = 0;
        double totalDeltaQb = 0;
        double totalDeltaPc = 0;
        double totalDeltaQc = 0;
        for (Load load : bus.getLoads()) {
            var extension = load.getExtension(LoadAsymmetrical.class);
            if (extension != null) {
                totalDeltaPa += extension.getDeltaPa() / PerUnit.SB;
                totalDeltaQa += extension.getDeltaQa() / PerUnit.SB;
                totalDeltaPb += extension.getDeltaPb() / PerUnit.SB;
                totalDeltaQb += extension.getDeltaQb() / PerUnit.SB;
                totalDeltaPc += extension.getDeltaPc() / PerUnit.SB;
                totalDeltaQc += extension.getDeltaQc() / PerUnit.SB;
            }
        }
        lfBus.setAsym(new LfAsymBus(totalDeltaPa, totalDeltaQa, totalDeltaPb, totalDeltaQb, totalDeltaPc, totalDeltaQc));
    }

    public static LfBusImpl create(Bus bus, LfNetwork network, LfNetworkParameters parameters, boolean participating) {
        Objects.requireNonNull(bus);
        Objects.requireNonNull(parameters);
        var lfBus = new LfBusImpl(bus, network, bus.getV(), Math.toRadians(bus.getAngle()), parameters, participating);
        if (parameters.isAsymmetrical()) {
            createAsym(bus, lfBus);
        }
        return lfBus;
    }

    private Bus getBus() {
        return busRef.get();
    }

    @Override
    public String getId() {
        return getBus().getId();
    }

    @Override
    public String getVoltageLevelId() {
        return getBus().getVoltageLevel().getId();
    }

    @Override
    public boolean isFictitious() {
        return false;
    }

    @Override
    public double getNominalV() {
        return nominalV;
    }

    @Override
    public double getLowVoltageLimit() {
        return lowVoltageLimit / nominalV;
    }

    @Override
    public double getHighVoltageLimit() {
        return highVoltageLimit / nominalV;
    }

    @Override
    public void updateState(LfNetworkStateUpdateParameters parameters) {
        var bus = getBus();
        bus.setV(Math.max(v, 0.0)).setAngle(Math.toDegrees(angle));

        // update slack bus
        if (slack && parameters.isWriteSlackBus()) {
            SlackTerminal.attach(bus);
        }
        if (reference && parameters.isWriteReferenceTerminals() && parameters.getReferenceBusSelectionMode() == ReferenceBusSelectionMode.FIRST_SLACK) {
            bus.getConnectedTerminalStream().findFirst().ifPresent(ReferenceTerminals::addTerminal);
        }

        super.updateState(parameters);
    }

    @Override
    public boolean isParticipating() {
        return participating;
    }

    @Override
    public List<BusResult> createBusResults() {
        var bus = getBus();
        if (breakers) {
            if (bbsIds.isEmpty()) {
                return List.of(new BusResult(getVoltageLevelId(), bus.getId(), v, Math.toDegrees(angle)));
            } else {
                return bbsIds.stream()
                        .map(bbsId -> new BusResult(getVoltageLevelId(), bbsId, v, Math.toDegrees(angle)))
                        .collect(Collectors.toList());
            }
        } else {
            return bus.getVoltageLevel().getBusBreakerView().getBusesFromBusViewBusId(bus.getId())
                    .stream().map(b -> new BusResult(getVoltageLevelId(), b.getId(), v, Math.toDegrees(angle))).collect(Collectors.toList());
        }
    }

    @Override
    public Optional<Country> getCountry() {
        return Optional.ofNullable(country);
    }

    @Override
    public double getTargetP() {
        if (asym != null) {
            return getGenerationTargetP();
            // we use the detection of the asymmetry extension at bus to check if we are in asymmetrical calculation
            // in this case, load target is set to zero and the constant-balanced load model (in 3 phased representation) is replaced by a model depending on v1, v2, v0 (equivalent fortescue representation)
        }
        return super.getTargetP();
    }

    @Override
    public double getTargetQ() {
        if (asym != null) {
            return getGenerationTargetQ();
            // we use the detection of the asymmetry extension at bus to check if we are in asymmetrical calculation
            // in this case, load target is set to zero and the constant power load model (in 3 phased representation) is replaced by a model depending on v1, v2, v0 (equivalent fortescue representation)
        }
        return super.getTargetQ();
    }

    @Override
    public double getFictitiousInjectionTargetP() {
        return fictitiousInjectionTargetP;
    }

    @Override
    public double getFictitiousInjectionTargetQ() {
        return fictitiousInjectionTargetQ;
    }
}
