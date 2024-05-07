/**
 * Copyright (c) 2019, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.network;

import com.powsybl.commons.report.ReportNode;

import java.util.List;

/**
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
public interface LfNetworkLoader<T> {

    /**
     * Load the given network object
     * @param network the network to load
     * @param parameters parameters used to load the network
     * @param reportNode the report node used for functional logs
     * @return the list of LfNetwork, sorted by ascending connected components number then by ascending synchronous
     * components number (hence sorted by descending connected components size then by descending synchronous components
     * size)
     */
    List<LfNetwork> load(T network, LfTopoConfig topoConfig, LfNetworkParameters parameters, ReportNode reportNode);
}
