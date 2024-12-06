/**
 * Copyright (c) 2024, Coreso SA (https://www.coreso.eu/) and TSCNET Services GmbH (https://www.tscnet.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.sa;

import com.powsybl.commons.extensions.AbstractExtension;
import com.powsybl.contingency.Contingency;
import com.powsybl.loadflow.LoadFlowParameters;

/**
 * @author Valentin Mouradian {@literal <valentin.mouradian at artelys.com>}
 */
public class ContingencyParameters extends AbstractExtension<Contingency> {

    private boolean distributedSlack;

    private boolean areaInterchangeControl;

    private LoadFlowParameters.BalanceType balanceType;

    public ContingencyParameters(boolean distributedSlack, boolean areaInterchangeControl, LoadFlowParameters.BalanceType balanceType) {
        this.distributedSlack = distributedSlack;
        this.areaInterchangeControl = areaInterchangeControl;
        this.balanceType = balanceType;
    }

    @Override
    public String getName() {
        return "contingency-parameters";
    }

    public boolean isDistributedSlack() {
        return distributedSlack;
    }

    public boolean isAreaInterchangeControl() {
        return areaInterchangeControl;
    }

    public LoadFlowParameters.BalanceType getBalanceType() {
        return balanceType;
    }
}
