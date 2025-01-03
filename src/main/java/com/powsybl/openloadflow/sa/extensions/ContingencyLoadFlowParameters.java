/**
 * Copyright (c) 2024, Coreso SA (https://www.coreso.eu/) and TSCNET Services GmbH (https://www.tscnet.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.sa.extensions;

import com.powsybl.commons.extensions.AbstractExtension;
import com.powsybl.contingency.Contingency;
import com.powsybl.loadflow.LoadFlowParameters;

import java.util.Optional;

/**
 * @author Valentin Mouradian {@literal <valentin.mouradian at artelys.com>}
 */
public class ContingencyLoadFlowParameters extends AbstractExtension<Contingency> {

    private Boolean distributedSlack = null;

    private Boolean areaInterchangeControl = null;

    private LoadFlowParameters.BalanceType balanceType = null;

    public ContingencyLoadFlowParameters() {
    }

    @Override
    public String getName() {
        return "contingency-load-flow-parameters";
    }

    public Optional<Boolean> isDistributedSlack() {
        return Optional.ofNullable(distributedSlack);
    }

    public Optional<Boolean> isAreaInterchangeControl() {
        return Optional.ofNullable(areaInterchangeControl);
    }

    public Optional<LoadFlowParameters.BalanceType> getBalanceType() {
        return Optional.ofNullable(balanceType);
    }

    public ContingencyLoadFlowParameters setDistributedSlack(Boolean distributedSlack) {
        this.distributedSlack = distributedSlack;
        return this;
    }

    public ContingencyLoadFlowParameters setAreaInterchangeControl(Boolean areaInterchangeControl) {
        this.areaInterchangeControl = areaInterchangeControl;
        return this;
    }

    public ContingencyLoadFlowParameters setBalanceType(LoadFlowParameters.BalanceType balanceType) {
        this.balanceType = balanceType;
        return this;
    }
}
