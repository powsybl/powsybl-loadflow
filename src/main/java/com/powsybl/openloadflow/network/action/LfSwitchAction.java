/**
 * Copyright (c) 2025, Coreso SA (https://www.coreso.eu/) and TSCNET Services GmbH (https://www.tscnet.eu/)
 * Copyright (c) 2022, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.network.action;

import com.powsybl.action.SwitchAction;
import com.powsybl.openloadflow.network.LfBranch;
import com.powsybl.openloadflow.network.LfNetwork;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author Bertrand Rix {@literal <bertrand.rix at artelys.com>}
 * @author Anne Tilloy {@literal <anne.tilloy at rte-france.com>}
 * @author Jean-Luc Bouchot {@literal <jlbouchot at gmail.com>}
 */
public class LfSwitchAction extends AbstractLfBranchAction<SwitchAction> {

    private static final Logger LOGGER = LoggerFactory.getLogger(LfSwitchAction.class);

    public LfSwitchAction(String id, SwitchAction action, LfNetwork lfNetwork) {
        super(id, action, lfNetwork);
    }

    @Override
    void findEnabledDisabledBranches(LfNetwork lfNetwork) {
        LfBranch branch = lfNetwork.getBranchById(action.getSwitchId());
        if (branch != null) {
            if (action.isOpen()) {
                setDisabledBranch(branch);
            } else {
                setEnabledBranch(branch);
            }
        } else {
            LOGGER.warn("Switch action {}: branch matching switch id {} not found", action.getId(), action.getSwitchId());
        }
    }
}
