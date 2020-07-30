/**
 * Copyright (c) 2020, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.openloadflow.network;

import com.powsybl.iidm.network.Bus;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.VoltageLevel;
import com.powsybl.iidm.network.extensions.SlackTerminal;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * @author Anne Tilloy <anne.tilloy at rte-france.com>
 */
public class NetworkSlackBusSelector implements SlackBusSelector {

    private static final SlackBusSelector DEFAULT_FALLBACK_SELECTOR = new MostMeshedSlackBusSelector();

    private final SlackBusSelector fallbackSelector;

    private final Set<String> slackBusIds = new HashSet<>();

    public NetworkSlackBusSelector(Network network) {
        this(network, DEFAULT_FALLBACK_SELECTOR);
    }

    public NetworkSlackBusSelector(Network network, SlackBusSelector fallbackSelector) {
        Objects.requireNonNull(network);
        this.fallbackSelector = Objects.requireNonNull(fallbackSelector);
        for (VoltageLevel vl : network.getVoltageLevels()) {
            SlackTerminal slackTerminal = vl.getExtension(SlackTerminal.class);
            if (slackTerminal != null) {
                Bus bus = slackTerminal.getTerminal().getBusView().getBus();
                if (bus != null) {
                    slackBusIds.add(bus.getId());
                }
            }
        }
    }

    @Override
    public LfBus select(List<LfBus> buses) {
        List<LfBus> slackBuses = buses.stream().filter(bus -> !bus.isFictitious() && slackBusIds.contains(bus.getId())).collect(Collectors.toList());
        if (slackBuses.isEmpty()) {
            // fallback to automatic selection
            return fallbackSelector.select(buses);
        } else if (slackBuses.size() == 1) {
            return slackBuses.get(0);
        } else {
            // fallback to automatic selection among slack buses
            return fallbackSelector.select(slackBuses);
        }
    }
}
